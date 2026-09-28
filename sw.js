/* PlateSight service worker
 * - Caches the app, models and AI runtime so the app works offline after the first launch.
 * - Adds cross-origin isolation headers so the AI runtime can use several CPU cores.
 * - Serves ONNX Runtime Web from the app's own origin (downloaded once from the CDN).
 * - Receives photos/videos shared to the installed app (Web Share Target).
 * Bump VERSION whenever you change any app file, so phones pick up the update.
 */
const VERSION = '1.0.1';
const ORT_VERSION = '1.20.1';
const ORT_CDN = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/`;

const SHELL_CACHE = `ps-shell-${VERSION}`;
const MODEL_CACHE = 'ps-models-v1';
const ORT_CACHE = `ps-ort-${ORT_VERSION}`;
const SHARE_CACHE = 'ps-share';

const SHELL = [
  './', 'index.html', 'app.css', 'manifest.webmanifest',
  'js/main.js', 'js/engine.js', 'js/engine-worker.js', 'js/tracker.js', 'js/formats.js', 'js/store.js', 'js/ui.js',
  'models/ocr.json',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-512.png', 'icons/favicon-32.png', 'icons/apple-touch-icon.png',
];

const SCOPE = new URL(self.registration.scope);

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL_CACHE);
    await cache.addAll(SHELL.map((u) => new Request(new URL(u, SCOPE), { cache: 'reload' })));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keep = new Set([SHELL_CACHE, MODEL_CACHE, ORT_CACHE, SHARE_CACHE]);
    for (const key of await caches.keys()) {
      if (key.startsWith('ps-') && !keep.has(key)) await caches.delete(key);
    }
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

  if (rel.startsWith('ort/')) event.respondWith(isolate(ortFile(rel.slice(4))));
  else if (rel.startsWith('models/') && rel.endsWith('.onnx')) event.respondWith(isolate(cacheFirst(MODEL_CACHE, req)));
  else if (req.mode === 'navigate') event.respondWith(isolate(appPage(req)));
  else event.respondWith(isolate(cacheFirst(SHELL_CACHE, req, true)));
});

async function cacheFirst(name, req, ignoreSearch = false) {
  const cache = await caches.open(name);
  const hit = await cache.match(req, { ignoreSearch });
  if (hit) return hit;
  const res = await fetch(req);
  if (res.ok && res.type === 'basic') cache.put(req, res.clone()).catch(() => {});
  return res;
}

async function appPage(req) {
  const cache = await caches.open(SHELL_CACHE);
  const hit = (await cache.match(new URL('index.html', SCOPE).href)) || (await cache.match(SCOPE.href));
  if (hit) return hit;
  return fetch(req);
}

const ORT_TYPES = { js: 'text/javascript', mjs: 'text/javascript', wasm: 'application/wasm', map: 'application/json' };

// ONNX Runtime files: cache -> files hosted next to the app (optional) -> CDN.
async function ortFile(name) {
  if (!/^[\w.-]+$/.test(name)) return new Response('Not found', { status: 404 });
  const cache = await caches.open(ORT_CACHE);
  const key = new URL(`ort/${name}`, SCOPE).href;
  const hit = await cache.match(key);
  if (hit) return hit;

  let res = null;
  try {
    const local = await fetch(key);
    const type = local.headers.get('content-type') || '';
    if (local.ok && !/text\/html/i.test(type)) res = local;
  } catch (_) { /* not hosted locally */ }
  if (!res) {
    res = await fetch(ORT_CDN + name, { mode: 'cors', credentials: 'omit' });
    if (!res.ok) return res;
  }
  const body = await res.blob();
  const ext = name.split('.').pop();
  const out = new Response(body, {
    headers: {
      'Content-Type': ORT_TYPES[ext] || res.headers.get('content-type') || 'application/octet-stream',
      'Content-Length': String(body.size),
    },
  });
  await cache.put(key, out.clone());
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
