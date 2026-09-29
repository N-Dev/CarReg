/* PlateSight inference worker (classic worker).
 * Runs ONNX Runtime Web off the main thread so the camera UI stays smooth.
 * Pipeline per frame: letterbox -> YOLOv9 plate detector -> crop -> plate OCR (+ country head).
 */
'use strict';

// ONNX Runtime namespace. Deliberately not named `ort`: the runtime's script declares a global
// `var ort`, and a top-level `let ort` here would make that script fail to run.
let rt = null;
let OCR = null;             // OCR config: alphabet, slots, regions
let EPS = ['wasm'];
let gpuError = null;
const sessions = new Map(); // key -> { sess, meta }
const canvases = new Map();
let queue = Promise.resolve();

class WithTransfer { constructor(value, transfer) { this.value = value; this.transfer = transfer; } }

// Serialise inference: an ORT session must not run concurrently.
function exclusive(fn) {
  const run = queue.then(fn, fn);
  queue = run.catch(() => {});
  return run;
}

function canvas(name, w, h) {
  let c = canvases.get(name);
  if (!c) {
    const cv = new OffscreenCanvas(w, h);
    c = { cv, ctx: cv.getContext('2d', { willReadFrequently: true }) };
    canvases.set(name, c);
  }
  if (c.cv.width !== w || c.cv.height !== h) { c.cv.width = w; c.cv.height = h; }
  return c;
}

// Reused input buffers: avoids allocating megabytes per frame (GC pauses cause jank on phones).
// Safe because runs are serialised and ORT copies inputs during run().
const buffers = new Map();
function buffer(Type, n) {
  const key = `${Type.name}:${n}`;
  let b = buffers.get(key);
  if (!b) { b = new Type(n); buffers.set(key, b); }
  return b;
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const area = (b) => Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
function iou(a, b) {
  const w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
  const h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
  if (w <= 0 || h <= 0) return 0;
  const i = w * h;
  return i / (area(a) + area(b) - i);
}
function nms(dets, thr) {
  const sorted = dets.slice().sort((a, b) => b.score - a.score);
  const keep = [];
  for (const d of sorted) if (keep.every((k) => iou(k.box, d.box) < thr)) keep.push(d);
  return keep;
}

async function fetchBytes(url, expected, onProgress) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Download failed (HTTP ${res.status}) for ${url.split('/').pop()}`);
  const total = expected || +res.headers.get('content-length') || 0;
  if (!res.body || !res.body.getReader) {
    const b = new Uint8Array(await res.arrayBuffer());
    onProgress(b.length, b.length);
    return b;
  }
  const reader = res.body.getReader();
  const chunks = [];
  let loaded = 0;
  let last = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.length;
    const t = performance.now();
    if (t - last > 80) { last = t; onProgress(Math.min(loaded, total || loaded), total); }
  }
  onProgress(loaded, loaded);
  const out = new Uint8Array(loaded);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
}

// ---------------------------------------------------------------- detection
async function detect(bitmap, key, conf, rect) {
  const { sess, meta } = sessions.get(key);
  const S = meta.size;
  const W = bitmap.width;
  const H = bitmap.height;
  const r = Math.min(S / rect.w, S / rect.h);
  const nw = Math.round(rect.w * r);
  const nh = Math.round(rect.h * r);
  const left = Math.round((S - nw) / 2 - 0.1);
  const top = Math.round((S - nh) / 2 - 0.1);

  const { ctx } = canvas(`det${S}`, S, S);
  ctx.fillStyle = 'rgb(114,114,114)';
  ctx.fillRect(0, 0, S, S);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'medium';
  ctx.drawImage(bitmap, rect.x, rect.y, rect.w, rect.h, left, top, nw, nh);
  const px = ctx.getImageData(0, 0, S, S).data;
  const n = S * S;
  const f = buffer(Float32Array, 3 * n);
  for (let i = 0, p = 0; i < n; i++, p += 4) {
    f[i] = px[p] / 255;
    f[i + n] = px[p + 1] / 255;
    f[i + 2 * n] = px[p + 2] / 255;
  }
  const out = await sess.run({ [sess.inputNames[0]]: new rt.Tensor('float32', f, [1, 3, S, S]) });
  const o = out[sess.outputNames[0]];
  const d = o.data;
  const rows = o.dims[0] || 0;
  const cols = o.dims[1] || 7;
  const boxes = [];
  for (let i = 0; i < rows; i++) {
    const b = i * cols;
    const score = d[b + 6];
    if (!(score >= conf)) continue;
    const x1 = clamp((d[b + 1] - left) / r + rect.x, 0, W);
    const y1 = clamp((d[b + 2] - top) / r + rect.y, 0, H);
    const x2 = clamp((d[b + 3] - left) / r + rect.x, 0, W);
    const y2 = clamp((d[b + 4] - top) / r + rect.y, 0, H);
    if (x2 - x1 < 4 || y2 - y1 < 3) continue;
    boxes.push({ box: [x1, y1, x2, y2], score });
  }
  return boxes;
}

// Tiled pass for large photos where plates are tiny.
async function deepDetect(bitmap, key, conf) {
  const W = bitmap.width;
  const H = bitmap.height;
  const tw = Math.round(W * 0.6);
  const th = Math.round(H * 0.6);
  const found = [];
  for (const x of [0, W - tw]) {
    for (const y of [0, H - th]) found.push(...(await detect(bitmap, key, conf, { x, y, w: tw, h: th })));
  }
  return nms(found, 0.45);
}

// ---------------------------------------------------------------- OCR
const V0 = { ex: 0, ey: 0 };
const TTA = [V0, { ex: 0.06, ey: 0.12 }, { ex: -0.03, ey: 0 }];

function variantRect(b, v, W, H) {
  const w = b[2] - b[0];
  const h = b[3] - b[1];
  const x1 = clamp(b[0] - w * v.ex, 0, W - 1);
  const y1 = clamp(b[1] - h * v.ey, 0, H - 1);
  const x2 = clamp(b[2] + w * v.ex, x1 + 1, W);
  const y2 = clamp(b[3] + h * v.ey, y1 + 1, H);
  return [x1, y1, x2 - x1, y2 - y1];
}

async function ocr(bitmap, key, boxes, variants) {
  const { sess } = sessions.get(key);
  const H = OCR.img_height;
  const W = OCR.img_width;
  const SL = OCR.max_plate_slots;
  const A = OCR.alphabet;
  const PAD = OCR.pad_char;
  const rects = [];
  for (const b of boxes) for (const v of variants) rects.push(variantRect(b.box, v, bitmap.width, bitmap.height));
  const N = rects.length;
  const arr = buffer(Uint8Array, N * H * W * 3);
  const { ctx } = canvas('ocr', W, H);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'medium';
  rects.forEach((q, k) => {
    ctx.drawImage(bitmap, q[0], q[1], q[2], q[3], 0, 0, W, H);
    const px = ctx.getImageData(0, 0, W, H).data;
    let o = k * H * W * 3;
    for (let p = 0; p < px.length; p += 4) { arr[o++] = px[p]; arr[o++] = px[p + 1]; arr[o++] = px[p + 2]; }
  });
  const out = await sess.run({ [sess.inputNames[0]]: new rt.Tensor('uint8', arr, [N, H, W, 3]) });
  const plate = out.plate || out[sess.outputNames[0]];
  const region = out.region || null;
  const V = A.length;
  const pd = plate.data;
  const rd = region ? region.data : null;
  const R = region ? region.dims[region.dims.length - 1] : 0;

  const reads = [];
  for (let k = 0; k < N; k++) {
    let raw = '';
    const probs = [];
    for (let s = 0; s < SL; s++) {
      const base = (k * SL + s) * V;
      let bi = 0;
      let bp = -1;
      for (let c = 0; c < V; c++) { const p = pd[base + c]; if (p > bp) { bp = p; bi = c; } }
      raw += A[bi];
      probs.push(bp);
    }
    let end = raw.length;
    while (end > 0 && raw[end - 1] === PAD) end--;
    let text = '';
    const kept = [];
    for (let i = 0; i < end; i++) if (raw[i] !== PAD) { text += raw[i]; kept.push(probs[i]); }
    const conf = kept.length ? kept.reduce((a, b) => a + b, 0) / kept.length : 0;
    let reg = null;
    let regP = 0;
    if (rd) {
      let bi = 0;
      let bp = -1;
      for (let c = 0; c < R; c++) { const p = rd[k * R + c]; if (p > bp) { bp = p; bi = c; } }
      reg = OCR.plate_regions[bi] || null;
      regP = bp;
    }
    reads.push({ text, conf, minP: kept.length ? Math.min(...kept) : 0, region: reg, regionProb: regP });
  }
  const groups = [];
  for (let b = 0; b < boxes.length; b++) groups.push(reads.slice(b * variants.length, (b + 1) * variants.length));
  return groups;
}

async function thumbOf(bitmap, box) {
  const w = box[2] - box[0];
  const h = box[3] - box[1];
  const sx = Math.max(0, Math.floor(box[0] - w * 0.12));
  const sy = Math.max(0, Math.floor(box[1] - h * 0.35));
  const sw = Math.max(1, Math.min(bitmap.width - sx, Math.ceil(w * 1.24)));
  const sh = Math.max(1, Math.min(bitmap.height - sy, Math.ceil(h * 1.7)));
  const tw = 240;
  const th = clamp(Math.round((tw * sh) / sw), 24, 240);
  return createImageBitmap(bitmap, sx, sy, sw, sh, { resizeWidth: tw, resizeHeight: th, resizeQuality: 'high' });
}

// ---------------------------------------------------------------- commands
const handlers = {
  async init({ siteBase, cdnBase, file, prefer = 'site', threads, gpu, base }) {
    const why = (e) => String((e && e.message) || e)
      .replace(/^Failed to execute 'importScripts' on 'WorkerGlobalScope': /, '')
      .slice(0, 140);
    // 1) The copy served by this site (published next to the app, or proxied by the service worker).
    const fromSite = async () => { importScripts(siteBase + file); return siteBase; };
    // 2) The CDN. A plain importScripts() of a CDN script is blocked when the page is cross-origin
    //    isolated (the CDN sends no CORP header), but a CORS fetch is allowed: run it from a blob URL.
    const fromCdn = async () => {
      const res = await fetch(cdnBase + file, { mode: 'cors', credentials: 'omit' });
      if (!res.ok) throw new Error(`HTTP ${res.status} from CDN`);
      const url = URL.createObjectURL(new Blob([await res.text()], { type: 'text/javascript' }));
      try { importScripts(url); } finally { URL.revokeObjectURL(url); }
      return cdnBase;
    };
    const order = prefer === 'cdn' ? [['cdn', fromCdn], ['site', fromSite]] : [['site', fromSite], ['cdn', fromCdn]];
    const tried = [];
    let source = null;
    let wasmPaths = null;
    for (const [name, load] of order) {
      try {
        wasmPaths = await load();
        if (!self.ort || !self.ort.InferenceSession) throw new Error('script ran but ONNX Runtime is missing');
        source = name;
        break;
      } catch (e) {
        tried.push(`${name}: ${why(e)}`);
      }
    }
    if (!source) throw new Error(`RUNTIME: ${tried.join(' | ')}`);
    rt = self.ort;
    rt.env.wasm.wasmPaths = wasmPaths;
    rt.env.wasm.numThreads = threads;
    rt.env.wasm.proxy = false;
    rt.env.logLevel = 'error';
    EPS = gpu && self.navigator.gpu ? ['webgpu', 'wasm'] : ['wasm'];
    const res = await fetch(new URL('models/ocr.json', base).href);
    if (!res.ok) throw new Error('Could not load models/ocr.json');
    OCR = await res.json();
    return {
      threads,
      eps: EPS.slice(),
      source,
      tried,
      coi: self.crossOriginIsolated === true,
      version: (rt.env.versions && rt.env.versions.web) || '',
    };
  },

  async load({ key, url, expected, kind, size }, id) {
    if (sessions.has(key)) return { key, cached: true };
    let bytes;
    try {
      bytes = await fetchBytes(url, expected, (loaded, total) => self.postMessage({ id, progress: { key, loaded, total } }));
    } catch (e) {
      throw new Error(`DOWNLOAD: ${(e && e.message) || e}`);
    }
    return exclusive(async () => {
      self.postMessage({ id, progress: { key, stage: 'init' } });
      const t0 = performance.now();
      const opts = { executionProviders: EPS, graphOptimizationLevel: 'all' };
      let sess;
      try {
        sess = await rt.InferenceSession.create(bytes, opts);
      } catch (e) {
        if (EPS[0] === 'wasm') throw e;
        gpuError = String((e && e.message) || e);
        EPS = ['wasm'];
        sess = await rt.InferenceSession.create(bytes, { ...opts, executionProviders: EPS });
      }
      sessions.set(key, { sess, meta: { kind, size } });
      // Warm-up run: the first inference allocates buffers and is much slower.
      if (kind === 'det') {
        await sess.run({ [sess.inputNames[0]]: new rt.Tensor('float32', new Float32Array(3 * size * size), [1, 3, size, size]) });
      } else {
        await sess.run({ [sess.inputNames[0]]: new rt.Tensor('uint8', new Uint8Array(OCR.img_height * OCR.img_width * 3), [1, OCR.img_height, OCR.img_width, 3]) });
      }
      return { key, ms: Math.round(performance.now() - t0), ep: EPS[0], gpuError };
    });
  },

  analyze(args) {
    return exclusive(async () => {
      const { bitmap, det, ocr: ocrKey, conf = 0.35, maxPlates = 8, tta = false, deep = false, thumbs = true, minW = 36, roi = null } = args;
      const t0 = performance.now();
      const W = bitmap.width;
      const H = bitmap.height;
      try {
        if (!sessions.has(det)) throw new Error(`Model ${det} is not loaded`);
        // Portrait camera frames: detect in the centre square (the unobstructed part of the viewfinder),
        // which gives the detector ~1.8x more pixels per plate than letterboxing the whole tall frame.
        const region = roi === 'portrait' && H > W * 1.15
          ? { x: 0, y: Math.round((H - W) / 2), w: W, h: W }
          : { x: 0, y: 0, w: W, h: H };
        let boxes = await detect(bitmap, det, conf, region);
        let deepUsed = false;
        if (deep && boxes.length === 0) {
          deepUsed = true;
          boxes = await deepDetect(bitmap, det, conf);
        }
        boxes.sort((a, b) => area(b.box) - area(a.box));
        boxes = boxes.slice(0, maxPlates);
        const t1 = performance.now();
        const readable = boxes.filter((b) => b.box[2] - b.box[0] >= minW);
        if (ocrKey && readable.length && sessions.has(ocrKey)) {
          const groups = await ocr(bitmap, ocrKey, readable, tta ? TTA : [V0]);
          readable.forEach((b, i) => { b.reads = groups[i]; });
        }
        const t2 = performance.now();
        const transfer = [];
        if (thumbs) {
          for (const b of boxes) {
            try { b.thumb = await thumbOf(bitmap, b.box); transfer.push(b.thumb); } catch (_) { /* skip */ }
          }
        }
        const ms = { det: Math.round(t1 - t0), ocr: Math.round(t2 - t1), total: Math.round(performance.now() - t0) };
        return new WithTransfer({ w: W, h: H, dets: boxes, deep: deepUsed, ms }, transfer);
      } finally {
        bitmap.close();
      }
    });
  },
};

self.onmessage = async (e) => {
  const { id, cmd, args } = e.data || {};
  try {
    if (!handlers[cmd]) throw new Error(`Unknown command ${cmd}`);
    const r = await handlers[cmd](args || {}, id);
    if (r instanceof WithTransfer) self.postMessage({ id, ok: true, result: r.value }, r.transfer);
    else self.postMessage({ id, ok: true, result: r });
  } catch (err) {
    self.postMessage({ id, ok: false, error: (err && (err.message || String(err))) || 'Unknown error' });
  }
};
