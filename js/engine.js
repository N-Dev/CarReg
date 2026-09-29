// Main-thread side of the AI engine: service worker setup, worker RPC, runtime and model loading.

export const CFG = self.PS_CONFIG;
export const ORT_VERSION = CFG.ort.version;
export const ORT_CDN = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/`;
const APP_BASE = new URL('../', import.meta.url).href;

/** Model table from config.js, with cache-busting revision URLs. */
export const MODELS = Object.fromEntries(Object.entries(CFG.models).map(([k, m]) => [k, { ...m, url: `${m.file}?v=${m.rev}` }]));

/** Absolute URL of a model file (as requested by the worker, so the service worker caches it under the same key). */
export const modelUrl = (key) => new URL(MODELS[key].url, APP_BASE).href;

/**
 * Registers the service worker. On the very first visit the page reloads once so it runs
 * under the worker's cross-origin isolation headers (needed for multi-threaded inference).
 */
export async function setupServiceWorker(onUpdate) {
  if (!('serviceWorker' in navigator) || !window.isSecureContext) return { sw: false };
  try {
    const hadController = !!navigator.serviceWorker.controller;
    const reg = await navigator.serviceWorker.register(new URL('sw.js', APP_BASE).href);
    if (!hadController) {
      if (sessionStorage.getItem('ps_sw_reload')) return { sw: false, reg };
      await new Promise((resolve) => {
        navigator.serviceWorker.addEventListener('controllerchange', resolve, { once: true });
        setTimeout(resolve, 8000);
      });
      if (navigator.serviceWorker.controller) {
        sessionStorage.setItem('ps_sw_reload', '1');
        location.reload();
        return { reloading: true };
      }
      return { sw: false, reg };
    }
    sessionStorage.removeItem('ps_sw_reload');
    navigator.serviceWorker.addEventListener('controllerchange', () => onUpdate && onUpdate());
    reg.update().catch(() => {});
    return { sw: true, reg };
  } catch (error) {
    return { sw: false, error };
  }
}

export class Engine extends EventTarget {
  constructor() {
    super();
    this.worker = null;
    this.seq = 0;
    this.pending = new Map();
    this.loads = new Map();
    this.ready = new Set();
    this.timings = {};
    this.info = null;
  }

  _rpc(cmd, args = {}, transfer = [], onProgress = null) {
    if (!this.worker) return Promise.reject(new Error('RESTART: engine not running'));
    return new Promise((resolve, reject) => {
      const id = ++this.seq;
      this.pending.set(id, { resolve, reject, onProgress });
      this.worker.postMessage({ id, cmd, args }, transfer);
    });
  }

  _onMessage(e) {
    const { id, ok, result, error, progress } = e.data || {};
    const p = this.pending.get(id);
    if (!p) return;
    if (progress) { if (p.onProgress) p.onProgress(progress); return; }
    this.pending.delete(id);
    if (ok) p.resolve(result);
    else p.reject(new Error(error));
  }

  _progress(detail) { this.dispatchEvent(new CustomEvent('progress', { detail })); }

  /**
   * Starts a fresh inference worker and downloads the runtime (progress key 'runtime').
   * prefer: 'site' (runtime copy published with the app) or 'cdn'; threads: 1 = single-core safe mode.
   */
  async boot({ gpu = false, threads = 1, prefer = 'site', safe = false } = {}) {
    this.terminate();
    const n = self.crossOriginIsolated === true ? Math.max(1, threads) : 1;
    const useGpu = !!gpu && 'gpu' in navigator;
    const mode = useGpu ? CFG.ort.gpu : CFG.ort.cpu;
    this.worker = new Worker(new URL('./engine-worker.js', import.meta.url));
    this.worker.onmessage = (e) => this._onMessage(e);
    this.worker.onerror = (e) => {
      const err = new Error(`CRASH: ${e.message || 'the AI engine stopped'}`);
      for (const p of this.pending.values()) p.reject(err);
      this.pending.clear();
      this.dispatchEvent(new CustomEvent('crash', { detail: err }));
    };
    this.info = await this._rpc('init', {
      siteBase: new URL('ort/', APP_BASE).href,
      cdnBase: ORT_CDN,
      ort: { script: mode.script, wasm: mode.wasm, wasmBytes: mode.wasmBytes, prefer },
      threads: n,
      gpu: useGpu,
      base: APP_BASE,
    }, [], (p) => this._progress(p));
    this.info.safe = safe;
    this.info.requestedThreads = threads;
    this.timings.runtimeMs = this.info.runtimeMs;
    return this.info;
  }

  /** Stops the worker (if any) and forgets loaded models; pending calls are rejected. */
  terminate() {
    if (this.worker) {
      this.worker.onmessage = null;
      this.worker.onerror = null;
      this.worker.terminate();
      this.worker = null;
    }
    const err = new Error('RESTART: engine restarted');
    for (const p of this.pending.values()) p.reject(err);
    this.pending.clear();
    this.loads.clear();
    this.ready.clear();
    this.info = null;
  }

  /** Loads a model once; progress events carry { key, loaded, total } or { key, stage: 'init' }. */
  load(key) {
    if (!this.loads.has(key)) {
      const m = MODELS[key];
      const job = this._rpc('load', {
        key, url: modelUrl(key), expected: m.bytes, kind: m.kind, size: m.size || 0,
      }, [], (p) => this._progress(p))
        .then((r) => {
          this.ready.add(key);
          if (r && r.ep && this.info) this.info.ep = r.ep;
          if (r && r.gpuError && this.info) this.info.gpuError = r.gpuError;
          if (r && !r.cached) this.timings[key] = { downloadMs: r.downloadMs, initMs: r.initMs };
          this.dispatchEvent(new CustomEvent('loaded', { detail: { key, ...r } }));
          return r;
        })
        .catch((err) => { this.loads.delete(key); throw err; });
      this.loads.set(key, job);
    }
    return this.loads.get(key);
  }

  has(key) { return this.ready.has(key); }

  /** Detect + read plates in an ImageBitmap (the bitmap is transferred and closed). */
  analyze(bitmap, opts) {
    return this._rpc('analyze', { bitmap, ...opts }, [bitmap]);
  }

  /** Times every loaded model. */
  bench(runs = 15) { return this._rpc('bench', { runs }); }
}
