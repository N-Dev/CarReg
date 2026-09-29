// Main-thread side of the AI: service worker set-up, the inference worker, and model loading.
export const PS = self.PS_CONFIG; // shared with PlateSight: runtime version and files, build id
export const TC = self.TC_CONFIG;
const BASE = new URL('../', import.meta.url).href; // the TrafficSight folder
export const SITE = new URL('../', BASE).href; // the whole site (where PlateSight publishes the runtime)
// (Guarded: an old PlateSight service worker can load this module into a page without the shared config.)
export const ORT_CDN = PS ? `https://cdn.jsdelivr.net/npm/onnxruntime-web@${PS.ort.version}/dist/` : '';
export const modelUrl = (key) => new URL(`${TC.models[key].file}?v=${TC.models[key].rev}`, BASE).href;

/**
 * Registers TrafficSight's service worker. The first time, the page reloads once so it runs under the
 * worker's cross-origin isolation headers (needed for multi-core AI). Returns { reloading } then.
 * onUpdate runs when a newer version has taken over.
 */
export async function setupServiceWorker(onUpdate) {
  if (!('serviceWorker' in navigator) || !window.isSecureContext) return { sw: false };
  const mine = () => {
    const c = navigator.serviceWorker.controller;
    return !!c && new URL(c.scriptURL).href === new URL('sw.js', BASE).href;
  };
  try {
    const had = mine();
    const reg = await navigator.serviceWorker.register(new URL('sw.js', BASE).href, { scope: BASE, updateViaCache: 'none' });
    if (!had) {
      if (sessionStorage.getItem('tc_sw_reload')) return { sw: false, reg };
      await new Promise((resolve) => {
        navigator.serviceWorker.addEventListener('controllerchange', resolve, { once: true });
        setTimeout(resolve, 8000);
      });
      if (mine()) {
        sessionStorage.setItem('tc_sw_reload', '1');
        location.reload();
        return { reloading: true };
      }
      return { sw: false, reg };
    }
    sessionStorage.removeItem('tc_sw_reload');
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
    this.loaded = new Set();
    this.info = null;
  }

  _rpc(cmd, args = {}, transfer = []) {
    if (!this.worker) return Promise.reject(new Error('RESTART: engine not running'));
    return new Promise((resolve, reject) => {
      const id = ++this.seq;
      this.pending.set(id, { resolve, reject });
      this.worker.postMessage({ id, cmd, args }, transfer);
    });
  }

  _onMessage(e) {
    const { id, ok, result, error, progress } = e.data || {};
    if (progress) { this.dispatchEvent(new CustomEvent('progress', { detail: progress })); return; }
    const p = this.pending.get(id);
    if (!p) return;
    this.pending.delete(id);
    if (ok) p.resolve(result);
    else p.reject(new Error(error));
  }

  async boot({ threads = 1 } = {}) {
    this.terminate();
    const n = self.crossOriginIsolated === true ? Math.max(1, threads) : 1;
    this.worker = new Worker(new URL('./worker.js', import.meta.url));
    this.worker.onmessage = (e) => this._onMessage(e);
    this.worker.onerror = (e) => {
      const err = new Error(`CRASH: ${e.message || 'the AI engine stopped'}`);
      for (const p of this.pending.values()) p.reject(err);
      this.pending.clear();
      this.dispatchEvent(new CustomEvent('crash', { detail: err }));
    };
    const cpu = PS.ort.cpu;
    // count/ort/ is PlateSight's published runtime (../ort/), served through TrafficSight's service worker.
    this.info = await this._rpc('init', {
      siteBase: new URL('ort/', BASE).href, cdnBase: ORT_CDN, ort: { script: cpu.script, wasm: cpu.wasm, wasmBytes: cpu.wasmBytes }, threads: n,
    });
    return this.info;
  }

  terminate() {
    if (this.worker) { this.worker.onmessage = null; this.worker.onerror = null; this.worker.terminate(); this.worker = null; }
    const err = new Error('RESTART: engine restarted');
    for (const p of this.pending.values()) p.reject(err);
    this.pending.clear();
    this.loaded.clear();
    this.info = null;
  }

  async load(key) {
    const m = TC.models[key];
    const r = await this._rpc('load', { key, url: modelUrl(key), expected: m.bytes, size: m.size });
    this.loaded.add(key);
    return r;
  }

  bench(key, runs = 8) { return this._rpc('bench', { key, runs }); }

  /** Road users in an ImageBitmap (transferred and closed). */
  detect(bitmap, opts) { return this._rpc('detect', { bitmap, ...opts }, [bitmap]); }
}
