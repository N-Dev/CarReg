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
const now = () => performance.now();
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

async function fetchBytes(url, expected, onProgress, init) {
  const res = await fetch(url, init);
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url.split('/').pop().split('?')[0]}`);
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
    const t = now();
    if (t - last > 80) { last = t; onProgress(Math.min(loaded, total || loaded), total); }
  }
  onProgress(loaded, loaded);
  const out = new Uint8Array(loaded);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
}

const isWasm = (b) => b.length > 8 && b[0] === 0x00 && b[1] === 0x61 && b[2] === 0x73 && b[3] === 0x6d;

// ---------------------------------------------------------------- detection
async function detect(bitmap, key, conf, rect, rawMin = null) {
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
  const raw = [];
  const floor = rawMin == null ? conf : Math.min(conf, rawMin);
  for (let i = 0; i < rows; i++) {
    const b = i * cols;
    const score = d[b + 6];
    if (!(score >= floor)) continue;
    const x1 = clamp((d[b + 1] - left) / r + rect.x, 0, W);
    const y1 = clamp((d[b + 2] - top) / r + rect.y, 0, H);
    const x2 = clamp((d[b + 3] - left) / r + rect.x, 0, W);
    const y2 = clamp((d[b + 4] - top) / r + rect.y, 0, H);
    if (x2 - x1 < 4 || y2 - y1 < 3) continue;
    if (rawMin != null) raw.push({ box: [x1, y1, x2, y2], score });
    if (score < conf) continue;
    // Touching the edge of the analysed area: the plate is probably cut off, so its reading is partial.
    const mx = Math.max(3, rect.w * 0.015);
    const my = Math.max(3, rect.h * 0.015);
    const edge = x1 <= rect.x + mx || y1 <= rect.y + my || x2 >= rect.x + rect.w - mx || y2 >= rect.y + rect.h - my;
    boxes.push({ box: [x1, y1, x2, y2], score, edge });
  }
  return rawMin == null ? boxes : { boxes, raw };
}

// Tiled pass for large photos where plates are tiny.
async function deepDetect(bitmap, key, conf) {
  const W = bitmap.width;
  const H = bitmap.height;
  const tw = Math.round(W * 0.6);
  const th = Math.round(H * 0.6);
  const found = [];
  const tiles = [];
  for (const x of [0, W - tw]) {
    for (const y of [0, H - th]) {
      tiles.push([x, y, x + tw, y + th]);
      found.push(...(await detect(bitmap, key, conf, { x, y, w: tw, h: th })));
    }
  }
  return { boxes: nms(found, 0.45), tiles };
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

async function ocr(bitmap, key, boxes, variants, debug) {
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
  const { cv, ctx } = canvas('ocr', W, H);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'medium';
  const inputs = [];
  for (let k = 0; k < N; k++) {
    const q = rects[k];
    ctx.drawImage(bitmap, q[0], q[1], q[2], q[3], 0, 0, W, H);
    const px = ctx.getImageData(0, 0, W, H).data;
    let o = k * H * W * 3;
    for (let p = 0; p < px.length; p += 4) { arr[o++] = px[p]; arr[o++] = px[p + 1]; arr[o++] = px[p + 2]; }
    // Debug: keep exactly what the reader saw for the first variant of each plate.
    if (debug && k % variants.length === 0) inputs.push(await createImageBitmap(cv));
  }
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
    const alts = [];
    for (let s = 0; s < SL; s++) {
      const base = (k * SL + s) * V;
      let bi = 0;
      let bp = -1;
      let si = 0;
      let sp = -1;
      for (let c = 0; c < V; c++) {
        const p = pd[base + c];
        if (p > bp) { si = bi; sp = bp; bp = p; bi = c; } else if (p > sp) { sp = p; si = c; }
      }
      raw += A[bi];
      probs.push(bp);
      alts.push([A[si], sp]);
    }
    let end = raw.length;
    while (end > 0 && raw[end - 1] === PAD) end--;
    let text = '';
    const kept = [];
    const keptAlts = [];
    for (let i = 0; i < end; i++) {
      if (raw[i] !== PAD) { text += raw[i]; kept.push(probs[i]); keptAlts.push(alts[i]); }
    }
    const conf = kept.length ? kept.reduce((a, b) => a + b, 0) / kept.length : 0;
    let reg = null;
    let regP = 0;
    let regions = null;
    if (rd) {
      let bi = 0;
      let bp = -1;
      for (let c = 0; c < R; c++) { const p = rd[k * R + c]; if (p > bp) { bp = p; bi = c; } }
      reg = OCR.plate_regions[bi] || null;
      regP = bp;
      if (debug) {
        regions = Array.from(rd.slice(k * R, (k + 1) * R), (p, i) => [OCR.plate_regions[i], p])
          .sort((a, b) => b[1] - a[1]).slice(0, 3);
      }
    }
    const read = { text, conf, minP: kept.length ? Math.min(...kept) : 0, probs: kept, region: reg, regionProb: regP };
    if (debug) { read.alts = keptAlts; read.regions = regions; }
    reads.push(read);
  }
  const groups = [];
  for (let b = 0; b < boxes.length; b++) groups.push(reads.slice(b * variants.length, (b + 1) * variants.length));
  return { groups, inputs };
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

// Tight crop of the plate at (up to) native resolution: the format plate-reader training data uses.
async function cropOf(bitmap, box) {
  const sx = Math.max(0, Math.floor(box[0]));
  const sy = Math.max(0, Math.floor(box[1]));
  const sw = Math.max(1, Math.min(bitmap.width - sx, Math.ceil(box[2] - box[0])));
  const sh = Math.max(1, Math.min(bitmap.height - sy, Math.ceil(box[3] - box[1])));
  const tw = Math.min(320, sw);
  return createImageBitmap(bitmap, sx, sy, sw, sh, { resizeWidth: tw, resizeHeight: Math.max(8, Math.round((sh * tw) / sw)), resizeQuality: 'high' });
}

function zeros(kind, size) {
  return kind === 'det'
    ? new rt.Tensor('float32', new Float32Array(3 * size * size), [1, 3, size, size])
    : new rt.Tensor('uint8', new Uint8Array(OCR.img_height * OCR.img_width * 3), [1, OCR.img_height, OCR.img_width, 3]);
}

// ---------------------------------------------------------------- commands
const handlers = {
  async init({ siteBase, cdnBase, ort, threads, gpu, base }, id) {
    const why = (e) => String((e && e.message) || e)
      .replace(/^Failed to execute 'importScripts' on 'WorkerGlobalScope': /, '')
      .slice(0, 140);
    const file = ort.script;
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
    const order = ort.prefer === 'cdn' ? [['cdn', fromCdn], ['site', fromSite]] : [['site', fromSite], ['cdn', fromCdn]];
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

    // Download the WebAssembly engine now, with progress, and hand it to ONNX Runtime directly.
    // (Left to ONNX Runtime, this ~11 MB download would happen silently inside session creation,
    // where a slow connection looks exactly like a hang.)
    const t0 = now();
    const report = (loaded, total) => self.postMessage({ id, progress: { key: 'runtime', loaded, total } });
    let wasm = null;
    const wasmSources = source === 'site' ? [siteBase, cdnBase] : [cdnBase, siteBase];
    for (const src of wasmSources) {
      try {
        const init = src === cdnBase ? { mode: 'cors', credentials: 'omit' } : undefined;
        const bytes = await fetchBytes(src + ort.wasm, ort.wasmBytes, report, init);
        // A damaged copy (not WebAssembly at all) falls through to the other source.
        if (!isWasm(bytes)) throw new Error(`damaged engine file (${bytes.length} bytes, not WebAssembly)`);
        wasm = bytes;
        break;
      } catch (e) {
        tried.push(`wasm ${src === siteBase ? 'site' : 'cdn'}: ${why(e)}`);
      }
    }
    if (!wasm) throw new Error(`RUNTIME: couldn't download the engine (${tried.join(' | ')})`);
    const runtimeMs = Math.round(now() - t0);

    rt.env.wasm.wasmPaths = wasmPaths;
    rt.env.wasm.wasmBinary = wasm;
    rt.env.wasm.numThreads = threads;
    rt.env.wasm.proxy = false;
    rt.env.logLevel = 'error';
    EPS = gpu && self.navigator.gpu ? ['webgpu', 'wasm'] : ['wasm'];
    const res = await fetch(new URL('models/ocr.json', base).href);
    if (!res.ok) throw new Error('RUNTIME: could not load models/ocr.json');
    OCR = await res.json();
    return {
      threads,
      eps: EPS.slice(),
      source,
      tried,
      runtimeMs,
      coi: self.crossOriginIsolated === true,
      version: (rt.env.versions && rt.env.versions.web) || '',
    };
  },

  async load({ key, url, expected, kind, size }, id) {
    if (sessions.has(key)) return { key, cached: true };
    const t0 = now();
    let bytes;
    try {
      bytes = await fetchBytes(url, expected, (loaded, total) => self.postMessage({ id, progress: { key, loaded, total } }));
    } catch (e) {
      throw new Error(`DOWNLOAD: ${(e && e.message) || e}`);
    }
    if (expected && bytes.length !== expected) {
      throw new Error(`DOWNLOAD: ${url.split('/').pop().split('?')[0]} arrived damaged (${bytes.length} of ${expected} bytes)`);
    }
    const downloadMs = Math.round(now() - t0);
    return exclusive(async () => {
      self.postMessage({ id, progress: { key, stage: 'init' } });
      const t1 = now();
      // logSeverityLevel 3 = errors only (GPU mode otherwise warns that shape ops run on the CPU, which is by design).
      const opts = { executionProviders: EPS, graphOptimizationLevel: 'all', logSeverityLevel: 3 };
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
      await sess.run({ [sess.inputNames[0]]: zeros(kind, size) });
      return { key, downloadMs, initMs: Math.round(now() - t1), ep: EPS[0], gpuError };
    });
  },

  /** Times each loaded model on blank input: mean / median / 95th percentile in ms. */
  bench({ runs = 15 }) {
    return exclusive(async () => {
      const results = [];
      for (const [key, { sess, meta }] of sessions) {
        const feed = { [sess.inputNames[0]]: zeros(meta.kind, meta.size) };
        await sess.run(feed);
        const times = [];
        for (let i = 0; i < runs; i++) {
          const t0 = now();
          await sess.run(feed);
          times.push(now() - t0);
        }
        times.sort((a, b) => a - b);
        const mean = times.reduce((a, b) => a + b, 0) / times.length;
        results.push({
          key,
          mean: Math.round(mean * 10) / 10,
          p50: Math.round(times[Math.floor(times.length / 2)] * 10) / 10,
          p95: Math.round(times[Math.min(times.length - 1, Math.floor(times.length * 0.95))] * 10) / 10,
          min: Math.round(times[0] * 10) / 10,
        });
      }
      const order = ['det384', 'det640', 'ocrFast', 'ocrAcc'];
      results.sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key));
      return { results, ep: EPS[0], threads: rt.env.wasm.numThreads };
    });
  },

  analyze(args) {
    return exclusive(async () => {
      const {
        bitmap, det, ocr: ocrKey, conf = 0.35, maxPlates = 8, tta = false, deep = false,
        thumbs = true, minW = 36, roi = null, debug = false,
      } = args;
      const t0 = now();
      const W = bitmap.width;
      const H = bitmap.height;
      try {
        if (!sessions.has(det)) throw new Error(`Model ${det} is not loaded`);
        // Portrait camera frames: detect in the centre square (the unobstructed part of the viewfinder),
        // which gives the detector ~1.8x more pixels per plate than letterboxing the whole tall frame.
        const region = roi === 'portrait' && H > W * 1.15
          ? { x: 0, y: Math.round((H - W) / 2), w: W, h: W }
          : { x: 0, y: 0, w: W, h: H };
        let boxes;
        let raw = null;
        if (debug) ({ boxes, raw } = await detect(bitmap, det, conf, region, 0.1));
        else boxes = await detect(bitmap, det, conf, region);
        let tiles = null;
        if (deep && boxes.length === 0) ({ boxes, tiles } = await deepDetect(bitmap, det, conf));
        boxes.sort((a, b) => area(b.box) - area(a.box));
        boxes = boxes.slice(0, maxPlates);
        const t1 = now();
        const readable = boxes.filter((b) => b.box[2] - b.box[0] >= minW);
        const transfer = [];
        if (ocrKey && readable.length && sessions.has(ocrKey)) {
          const { groups, inputs } = await ocr(bitmap, ocrKey, readable, tta ? TTA : [V0], debug);
          readable.forEach((b, i) => {
            b.reads = groups[i];
            b.model = ocrKey;
            if (inputs[i]) { b.ocrInput = inputs[i]; transfer.push(inputs[i]); }
          });
        }
        const t2 = now();
        for (const b of boxes) {
          if (thumbs) {
            try { b.thumb = await thumbOf(bitmap, b.box); transfer.push(b.thumb); } catch (_) { /* skip */ }
          }
          if (debug && b.reads) {
            try { b.crop = await cropOf(bitmap, b.box); transfer.push(b.crop); } catch (_) { /* skip */ }
          }
        }
        const ms = { det: Math.round(t1 - t0), ocr: Math.round(t2 - t1), total: Math.round(now() - t0) };
        return new WithTransfer({ w: W, h: H, dets: boxes, raw, region, tiles, ms, det, ocr: ocrKey }, transfer);
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
