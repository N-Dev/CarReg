/* TrafficSight service worker
 * - Caches the app, its models and the AI runtime so counting works offline after the first start.
 * - Adds cross-origin isolation headers so the AI runtime can use several CPU cores.
 * - Serves ONNX Runtime Web from the copy PlateSight publishes (../ort/), falling back to the CDN,
 *   and shares PlateSight's runtime cache so a phone with both apps keeps one copy.
 * Versions come from PlateSight's ../js/config.js (runtime, build id) and js/config.js (models); the
 * browser re-checks both on every update check, so each publish reaches installed phones.
 */
importScripts('../js/config.js', 'js/config.js');
const PS = self.PS_CONFIG;
const TC = self.TC_CONFIG;
const VERSION = PS.app;
const ORT_VERSION = PS.ort.version;
const ORT_CDN = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/`;

const SHELL_CACHE = `tc-shell-${VERSION}`;
const MODEL_CACHE = 'tc-models';
const ORT_CACHE = `ps-ort-${ORT_VERSION}`; // the same cache PlateSight uses

// Every file the app needs offline (the unit tests check nothing is missing).
const SHELL = [
  './', 'index.html', 'app.css', 'manifest.webmanifest', '../js/config.js',
  'js/config.js', 'js/main.js', 'js/engine.js', 'js/worker.js', 'js/counter.js', 'js/stats.js', 'js/store.js',
  'js/charts.js', 'js/report.js', 'js/pdf.js',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-512.png', 'icons/favicon-32.png', 'icons/apple-touch-icon.png',
];

const SCOPE = new URL(self.registration.scope);
const SITE = new URL('../', SCOPE);
const MODEL_URLS = new Set(Object.values(TC.models).map((m) => new URL(`${m.file}?v=${m.rev}`, SCOPE).href));
const MODEL_BYTES = new Map(Object.values(TC.models).map((m) => [new URL(m.file, SCOPE).pathname, m.bytes]));
const SHARED_CONFIG = new URL('js/config.js', SITE).pathname;

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL_CACHE);
    await cache.addAll(SHELL.map((u) => new Request(new URL(u, SCOPE), { cache: 'reload' })));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keep = new Set([SHELL_CACHE, MODEL_CACHE]);
    for (const key of await caches.keys()) if (key.startsWith('tc-') && !keep.has(key)) await caches.delete(key);
    const models = await caches.open(MODEL_CACHE);
    for (const req of await models.keys()) if (!MODEL_URLS.has(req.url)) await models.delete(req);
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== SCOPE.origin) return;
  if (url.pathname.startsWith(`${SITE.pathname}ort/`)) {
    event.respondWith(isolate(ortFile(url.pathname.slice(SITE.pathname.length + 4), event)));
  } else if (url.pathname === SHARED_CONFIG) {
    event.respondWith(isolate(cacheFirst(SHELL_CACHE, req, event, true)));
  } else if (url.pathname.startsWith(SCOPE.pathname)) {
    const rel = url.pathname.slice(SCOPE.pathname.length);
    if (rel.startsWith('models/') && rel.endsWith('.onnx')) event.respondWith(isolate(cacheFirst(MODEL_CACHE, req, event)));
    else if (req.mode === 'navigate') event.respondWith(isolate(appPage(req)));
    else event.respondWith(isolate(cacheFirst(SHELL_CACHE, req, event, true)));
  }
});

// Downloads stream straight through to the app while a copy is saved.
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
  return hit || fetch(req);
}

const ORT_TYPES = { js: 'text/javascript', mjs: 'text/javascript', wasm: 'application/wasm', map: 'application/json' };

/** The response if it's plausibly the runtime file asked for; a .wasm must start with the WebAssembly signature. */
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

// ONNX Runtime files: the shared cache -> the copy published with the site -> the CDN.
async function ortFile(name, event) {
  if (!/^[\w.-]+$/.test(name)) return new Response('Not found', { status: 404 });
  const cache = await caches.open(ORT_CACHE);
  const key = new URL(`ort/${name}`, SITE).href;
  const hit = await cache.match(key);
  if (hit) return hit;
  let res = null;
  let type = 'basic';
  try { res = await genuine(await fetch(key, { cache: 'no-cache' }), name); } catch (_) { /* not hosted with the site */ }
  if (!res) {
    const cdn = await fetch(ORT_CDN + name, { mode: 'cors', credentials: 'omit' });
    type = cdn.type;
    res = await genuine(cdn, name);
    if (!res) return cdn.ok ? new Response('Damaged runtime file', { status: 502 }) : cdn;
  }
  const ext = name.split('.').pop();
  const headers = { 'Content-Type': ORT_TYPES[ext] || res.headers.get('content-type') || 'application/octet-stream' };
  const len = res.headers.get('content-length');
  if (len && type === 'basic' && !res.headers.get('content-encoding')) headers['Content-Length'] = len;
  const out = new Response(res.body, { headers });
  event.waitUntil(cache.put(key, out.clone()).catch(() => {}));
  return out;
}

// Cross-origin isolation lets the AI runtime use SharedArrayBuffer (several cores).
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
