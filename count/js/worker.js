/* TrafficSight inference worker (classic worker): runs the YOLOX detector with ONNX Runtime Web off
 * the main thread. Per frame: crop the part of the picture around the counting lines, letterbox it
 * into the model's square input, run the model, decode its boxes, and keep the road users worth
 * counting. The runtime is the same copy PlateSight publishes (../ort/), with the CDN as a fallback.
 */
'use strict';

// Deliberately not named `ort`: the runtime's script declares a global `var ort`.
let rt = null;
const sessions = new Map(); // key -> { sess, size }
let queue = Promise.resolve();
const now = () => performance.now();

class WithTransfer { constructor(value, transfer) { this.value = value; this.transfer = transfer; } }

// One inference at a time: an ONNX Runtime session must not run concurrently.
function exclusive(fn) {
  const run = queue.then(fn, fn);
  queue = run.catch(() => {});
  return run;
}

// COCO class numbers worth counting on a road, and which detections may be duplicates of each other.
const COCO = { 0: 'person', 1: 'bicycle', 2: 'car', 3: 'motorbike', 5: 'bus', 7: 'truck', 16: 'dog', 17: 'horse' };
const GROUP = { person: 'person', bicycle: 'two', motorbike: 'two', car: 'vehicle', bus: 'vehicle', truck: 'vehicle', dog: 'animal', horse: 'animal' };

let cv = null;
let ctx = null;
let input = null;
let grid = null;

/** Anchor grid for YOLOX's three output scales (strides 8, 16, 32): [x, y, stride] per row. */
function gridFor(size) {
  if (grid && grid.size === size) return grid.rows;
  const rows = [];
  for (const s of [8, 16, 32]) {
    const g = size / s;
    for (let y = 0; y < g; y++) for (let x = 0; x < g; x++) rows.push(x, y, s);
  }
  grid = { size, rows: new Float32Array(rows) };
  return grid.rows;
}

function iou(a, b) {
  const w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
  const h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
  if (w <= 0 || h <= 0) return 0;
  const i = w * h;
  return i / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i);
}

/**
 * Keeps the best of overlapping boxes within each group: the model often calls one van both a car and
 * a truck, or puts a second, smaller box inside a vehicle (a trailer, or part of a car behind it).
 */
function nms(dets, thr = 0.5) {
  dets.sort((a, b) => b.score - a.score);
  const keep = [];
  const inside = (d, k) => {
    const w = Math.min(d.box[2], k.box[2]) - Math.max(d.box[0], k.box[0]);
    const h = Math.min(d.box[3], k.box[3]) - Math.max(d.box[1], k.box[1]);
    return w > 0 && h > 0 ? (w * h) / ((d.box[2] - d.box[0]) * (d.box[3] - d.box[1])) : 0;
  };
  for (const d of dets) {
    if (!keep.some((k) => GROUP[k.cls] === GROUP[d.cls] && (iou(k.box, d.box) > thr || inside(d, k) > 0.6))) keep.push(d);
  }
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
    if (now() - last > 100) { last = now(); onProgress(Math.min(loaded, total || loaded), total); }
  }
  onProgress(loaded, loaded);
  const out = new Uint8Array(loaded);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
}

const isWasm = (b) => b.length > 8 && b[0] === 0x00 && b[1] === 0x61 && b[2] === 0x73 && b[3] === 0x6d;

const handlers = {
  async init({ siteBase, cdnBase, ort, threads }, id) {
    const why = (e) => String((e && e.message) || e).replace(/^Failed to execute 'importScripts' on 'WorkerGlobalScope': /, '').slice(0, 140);
    const fromSite = async () => { importScripts(siteBase + ort.script); return siteBase; };
    // A plain importScripts() from the CDN is blocked under cross-origin isolation; a CORS fetch isn't.
    const fromCdn = async () => {
      const res = await fetch(cdnBase + ort.script, { mode: 'cors', credentials: 'omit' });
      if (!res.ok) throw new Error(`HTTP ${res.status} from CDN`);
      const url = URL.createObjectURL(new Blob([await res.text()], { type: 'text/javascript' }));
      try { importScripts(url); } finally { URL.revokeObjectURL(url); }
      return cdnBase;
    };
    const tried = [];
    let source = null;
    let wasmPaths = null;
    for (const [name, load] of [['site', fromSite], ['cdn', fromCdn]]) {
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
    const t0 = now();
    let wasm = null;
    for (const src of source === 'site' ? [siteBase, cdnBase] : [cdnBase, siteBase]) {
      try {
        const init = src === cdnBase ? { mode: 'cors', credentials: 'omit' } : undefined;
        const bytes = await fetchBytes(src + ort.wasm, ort.wasmBytes, (loaded, total) => self.postMessage({ id, progress: { key: 'runtime', loaded, total } }), init);
        if (!isWasm(bytes)) throw new Error(`damaged engine file (${bytes.length} bytes)`);
        wasm = bytes;
        break;
      } catch (e) {
        tried.push(`wasm ${src === siteBase ? 'site' : 'cdn'}: ${why(e)}`);
      }
    }
    if (!wasm) throw new Error(`RUNTIME: couldn't download the engine (${tried.join(' | ')})`);
    rt.env.wasm.wasmPaths = wasmPaths;
    rt.env.wasm.wasmBinary = wasm;
    rt.env.wasm.numThreads = threads;
    rt.env.wasm.proxy = false;
    rt.env.logLevel = 'error';
    return { threads, source, tried, runtimeMs: Math.round(now() - t0), coi: self.crossOriginIsolated === true, version: (rt.env.versions && rt.env.versions.web) || '' };
  },

  async load({ key, url, expected, size }, id) {
    if (sessions.has(key)) return { key, cached: true };
    const t0 = now();
    let bytes;
    try {
      bytes = await fetchBytes(url, expected, (loaded, total) => self.postMessage({ id, progress: { key, loaded, total } }));
    } catch (e) {
      throw new Error(`DOWNLOAD: ${(e && e.message) || e}`);
    }
    if (expected && bytes.length !== expected) throw new Error(`DOWNLOAD: the model arrived damaged (${bytes.length} of ${expected} bytes)`);
    const downloadMs = Math.round(now() - t0);
    return exclusive(async () => {
      self.postMessage({ id, progress: { key, stage: 'init' } });
      const t1 = now();
      const sess = await rt.InferenceSession.create(bytes, { executionProviders: ['wasm'], graphOptimizationLevel: 'all', logSeverityLevel: 3 });
      sessions.set(key, { sess, size });
      const zeros = new rt.Tensor('float32', new Float32Array(3 * size * size), [1, 3, size, size]);
      await sess.run({ [sess.inputNames[0]]: zeros }); // the first run allocates buffers: do it now
      return { key, downloadMs, initMs: Math.round(now() - t1) };
    });
  },

  /** Median time of the detector on a blank frame, in ms. */
  bench({ key, runs = 8 }) {
    return exclusive(async () => {
      const { sess, size } = sessions.get(key);
      const feed = { [sess.inputNames[0]]: new rt.Tensor('float32', new Float32Array(3 * size * size), [1, 3, size, size]) };
      const times = [];
      for (let i = 0; i < runs; i++) { const t = now(); await sess.run(feed); times.push(now() - t); }
      times.sort((a, b) => a - b);
      return { key, p50: Math.round(times[Math.floor(times.length / 2)] * 10) / 10, threads: rt.env.wasm.numThreads };
    });
  },

  /**
   * Road users in a frame. roi: the part of the frame to analyse, as fractions [x, y, w, h].
   * Returns boxes as fractions of the whole frame.
   */
  detect({ bitmap, key, roi = [0, 0, 1, 1], conf = 0.3 }) {
    return exclusive(async () => {
      const t0 = now();
      try {
        const s = sessions.get(key);
        if (!s) throw new Error(`Model ${key} is not loaded`);
        const S = s.size;
        const W = bitmap.width;
        const H = bitmap.height;
        const rx = Math.round(roi[0] * W);
        const ry = Math.round(roi[1] * H);
        const rw = Math.max(1, Math.round(roi[2] * W));
        const rh = Math.max(1, Math.round(roi[3] * H));
        const r = Math.min(S / rw, S / rh);
        const nw = Math.round(rw * r);
        const nh = Math.round(rh * r);
        if (!cv) { cv = new OffscreenCanvas(S, S); ctx = cv.getContext('2d', { willReadFrequently: true }); }
        if (cv.width !== S) { cv.width = S; cv.height = S; }
        // As YOLOX was trained: the picture in the top-left corner, grey (114) padding, BGR, 0-255.
        ctx.fillStyle = 'rgb(114,114,114)';
        ctx.fillRect(0, 0, S, S);
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = 'medium';
        ctx.drawImage(bitmap, rx, ry, rw, rh, 0, 0, nw, nh);
        const px = ctx.getImageData(0, 0, S, S).data;
        const n = S * S;
        if (!input || input.length !== 3 * n) input = new Float32Array(3 * n);
        for (let i = 0, p = 0; i < n; i++, p += 4) {
          input[i] = px[p + 2];
          input[i + n] = px[p + 1];
          input[i + 2 * n] = px[p];
        }
        const t1 = now();
        const out = await s.sess.run({ [s.sess.inputNames[0]]: new rt.Tensor('float32', input, [1, 3, S, S]) });
        const o = out[s.sess.outputNames[0]];
        const t2 = now();
        const d = o.data;
        const cols = o.dims[2];
        const rows = o.dims[1];
        const g = gridFor(S);
        const found = [];
        for (let i = 0; i < rows; i++) {
          const b = i * cols;
          const obj = d[b + 4];
          if (obj < conf) continue;
          let best = 0;
          let bi = -1;
          for (let c = 0; c < 80; c++) { const v = d[b + 5 + c]; if (v > best) { best = v; bi = c; } }
          const cls = COCO[bi];
          const score = obj * best;
          if (!cls || score < conf) continue;
          const stride = g[i * 3 + 2];
          const cx = (d[b] + g[i * 3]) * stride;
          const cy = (d[b + 1] + g[i * 3 + 1]) * stride;
          const bw = Math.exp(d[b + 2]) * stride;
          const bh = Math.exp(d[b + 3]) * stride;
          // Model input -> frame pixels -> fractions of the frame.
          const x1 = Math.max(0, ((cx - bw / 2) / r + rx) / W);
          const y1 = Math.max(0, ((cy - bh / 2) / r + ry) / H);
          const x2 = Math.min(1, ((cx + bw / 2) / r + rx) / W);
          const y2 = Math.min(1, ((cy + bh / 2) / r + ry) / H);
          if (x2 - x1 < 0.004 || y2 - y1 < 0.004) continue;
          found.push({ cls, score: Math.round(score * 1000) / 1000, box: [x1, y1, x2, y2] });
        }
        const dets = nms(found);
        const t3 = now();
        return { dets, w: W, h: H, ms: { prep: Math.round(t1 - t0), infer: Math.round(t2 - t1), post: Math.round(t3 - t2), total: Math.round(t3 - t0) } };
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
