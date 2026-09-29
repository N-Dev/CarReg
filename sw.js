/* PlateSight service worker
 * - Caches the app, models and AI runtime so the app works offline after the first launch.
 * - Adds cross-origin isolation headers so the AI runtime can use several CPU cores.
 * - Serves ONNX Runtime Web from the app's own origin (the copy published with the site, or the CDN).
 * - Receives photos/videos shared to the installed app (Web Share Target).
 * Versions come from js/config.js; the publish workflow stamps the build id below and in the config,
 * so every published change reaches installed phones without manual version bumps.
 */
// build: dev
importScripts('js/config.js');
const CFG = self.PS_CONFIG;
const VERSION = CFG.app;
const ORT_VERSION = CFG.ort.version;
const ORT_CDN = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/`;

const SHELL_CACHE = `ps-shell-${VERSION}`;
const MODEL_CACHE = 'ps-models';
const ORT_CACHE = `ps-ort-${ORT_VERSION}`;
const SHARE_CACHE = 'ps-share';

// Every file the app needs offline (the unit tests check nothing is missing).
const SHELL = [
  './', 'index.html', 'app.css', 'manifest.webmanifest', 'models/ocr.json',
  'js/config.js', 'js/main.js', 'js/ctx.js', 'js/board.js', 'js/overlay.js', 'js/boot.js',
  'js/scan.js', 'js/adaptive.js', 'js/photo.js', 'js/video.js', 'js/history-view.js', 'js/detail.js',
  'js/settings-view.js', 'js/debug.js', 'js/fieldtest.js', 'js/zip.js', 'js/cards.js',
  'js/engine.js', 'js/engine-worker.js', 'js/tracker.js', 'js/formats.js', 'js/store.js', 'js/ui.js',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-512.png', 'icons/favicon-32.png', 'icons/apple-touch-icon.png',
];

const SCOPE = new URL(self.registration.scope);
const MODEL_URLS = new Set(Object.values(CFG.models).map((m) => new URL(`${m.file}?v=${m.rev}`, SCOPE).href));
const MODEL_BYTES = new Map(Object.values(CFG.models).map((m) => [new URL(m.file, SCOPE).pathname, m.bytes]));

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL_CACHE);
    await cache.addAll(SHELL.map((u) => new Request(new URL(u, SCOPE), { cache: 'reload' })));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const models = await caches.open(MODEL_CACHE);
    // One-time move from the old cache layout (1.0.x), so updating doesn't re-download 11 MB of models.
    if (await caches.has('ps-models-v1')) {
      const old = await caches.open('ps-models-v1');
      for (const m of Object.values(CFG.models)) {
        const res = await old.match(new URL(m.file, SCOPE).href);
        if (res) await models.put(new URL(`${m.file}?v=${m.rev}`, SCOPE).href, res);
      }
    }
    const keep = new Set([SHELL_CACHE, MODEL_CACHE, ORT_CACHE, SHARE_CACHE]);
    for (const key of await caches.keys()) {
      if (key.startsWith('ps-') && !keep.has(key)) await caches.delete(key);
    }
    // Drop models that were replaced by a newer revision.
    for (const req of await models.keys()) if (!MODEL_URLS.has(req.url)) await models.delete(req);
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);
  if (url.origin !== SCOPE.origin || !url.pathname.startsWith(SCOPE.pathname)) return;
  const rel = url.pathname.slice(SCOPE.pathname.length);

  if (req.method === 'POST' && rel === 'share-target') {
    event.respondWith(receiveShare(req));
    return;
  }
  if (req.method !== 'GET') return;

  if (rel.startsWith('ort/')) event.respondWith(isolate(ortFile(rel.slice(4), event)));
  else if (rel.startsWith('models/') && rel.endsWith('.onnx')) event.respondWith(isolate(cacheFirst(MODEL_CACHE, req, event)));
  else if (req.mode === 'navigate') event.respondWith(isolate(appPage(req)));
  else event.respondWith(isolate(cacheFirst(SHELL_CACHE, req, event, true)));
});

// Downloads stream straight through to the app while a copy is saved, so the app sees progress
// from the first byte (a slow connection must never look like a stalled one).
async function cacheFirst(name, req, event, ignoreSearch = false) {
  const cache = await caches.open(name);
  const hit = await cache.match(req, { ignoreSearch });
  if (hit) return hit;
  const res = await fetch(req);
  if (res.ok && res.type === 'basic' && rightSize(req, res)) event.waitUntil(cache.put(req, res.clone()).catch(() => {}));
  return res;
}

/** False for a model whose download size shows it isn't the file the app expects (never cached). */
function rightSize(req, res) {
  const expected = MODEL_BYTES.get(new URL(req.url).pathname);
  const len = +res.headers.get('content-length');
  return !expected || !len || !!res.headers.get('content-encoding') || len === expected;
}

async function appPage(req) {
  const cache = await caches.open(SHELL_CACHE);
  const hit = (await cache.match(new URL('index.html', SCOPE).href)) || (await cache.match(SCOPE.href));
  if (hit) return hit;
  return fetch(req);
}

const ORT_TYPES = { js: 'text/javascript', mjs: 'text/javascript', wasm: 'application/wasm', map: 'application/json' };

/**
 * The response if it's plausibly the runtime file asked for, else null: not an error or HTML page,
 * and an engine (.wasm) file must start with the WebAssembly signature. Still streams.
 */
async function genuine(res, name) {
  if (!res || !res.ok || /text\/html/i.test(res.headers.get('content-type') || '')) return null;
  if (!name.endsWith('.wasm') || !res.body) return res;
  const reader = res.body.getReader();
  const head = [];
  let n = 0;
  while (n < 4) {
    const { done, value } = await reader.read();
    if (done) break;
    head.push(value);
    n += value.length;
  }
  const first = new Uint8Array(n);
  let o = 0;
  for (const c of head) { first.set(c, o); o += c.length; }
  if (n < 4 || first[0] !== 0x00 || first[1] !== 0x61 || first[2] !== 0x73 || first[3] !== 0x6d) {
    reader.cancel().catch(() => {});
    return null;
  }
  const body = new ReadableStream({
    start(c) { c.enqueue(first); },
    async pull(c) { const { done, value } = await reader.read(); if (done) c.close(); else c.enqueue(value); },
    cancel(reason) { return reader.cancel(reason); },
  });
  return new Response(body, { status: res.status, statusText: res.statusText, headers: res.headers });
}

// ONNX Runtime files: cache -> the copy published next to the app -> CDN. Streamed like models.
async function ortFile(name, event) {
  if (!/^[\w.-]+$/.test(name)) return new Response('Not found', { status: 404 });
  const cache = await caches.open(ORT_CACHE);
  const key = new URL(`ort/${name}`, SCOPE).href;
  const hit = await cache.match(key);
  if (hit) return hit;

  let res = null;
  let type = 'basic';
  try { res = await genuine(await fetch(key), name); } catch (_) { /* not hosted locally */ }
  if (!res) {
    const cdn = await fetch(ORT_CDN + name, { mode: 'cors', credentials: 'omit' });
    type = cdn.type;
    res = await genuine(cdn, name);
    if (!res) return cdn.ok ? new Response('Damaged runtime file', { status: 502 }) : cdn;
  }
  const ext = name.split('.').pop();
  const headers = { 'Content-Type': ORT_TYPES[ext] || res.headers.get('content-type') || 'application/octet-stream' };
  // Only same-origin responses show whether the body was compressed in transit (then the length differs).
  const len = res.headers.get('content-length');
  if (len && type === 'basic' && !res.headers.get('content-encoding')) headers['Content-Length'] = len;
  const out = new Response(res.body, { headers });
  event.waitUntil(cache.put(key, out.clone()).catch(() => {}));
  return out;
}

// Cross-origin isolation lets the AI runtime use SharedArrayBuffer (multi-threaded inference).
async function isolate(pending) {
  const res = await pending;
  if (!res || res.status === 0 || res.type === 'opaque' || res.type === 'opaqueredirect') return res;
  const headers = new Headers(res.headers);
  headers.set('Cross-Origin-Opener-Policy', 'same-origin');
  headers.set('Cross-Origin-Embedder-Policy', 'require-corp');
  headers.set('Cross-Origin-Resource-Policy', 'same-origin');
  const noBody = [101, 204, 205, 304].includes(res.status);
  return new Response(noBody ? null : res.body, { status: res.status, statusText: res.statusText, headers });
}

// Android "Share → PlateSight": stash the file, then open the app, which picks it up.
async function receiveShare(req) {
  try {
    const form = await req.formData();
    const files = form.getAll('media').filter((f) => f && typeof f === 'object');
    const cache = await caches.open(SHARE_CACHE);
    for (const k of await cache.keys()) await cache.delete(k);
    if (files[0]) {
      await cache.put(new URL('__shared/0', SCOPE).href, new Response(files[0], {
        headers: {
          'Content-Type': files[0].type || 'application/octet-stream',
          'X-File-Name': encodeURIComponent(files[0].name || 'shared'),
        },
      }));
    }
  } catch (_) { /* fall through to the app */ }
  return Response.redirect(new URL('./?shared=1', SCOPE).href, 303);
}
