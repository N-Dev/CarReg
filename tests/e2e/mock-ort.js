/* Test stand-in for ONNX Runtime Web: same API surface the app uses (env, Tensor, InferenceSession),
 * but each run() is executed by the harness server with Python onnxruntime on the real models. */
(function () {
  const origin = self.location.origin;
  class Tensor {
    constructor(type, data, dims) { this.type = type; this.data = data; this.dims = dims; this.size = data.length; }
  }
  const env = { wasm: { numThreads: 0, wasmPaths: '', proxy: false, simd: true }, webgpu: {}, logLevel: 'warning', versions: { web: 'mock' } };
  const ARR = { float32: Float32Array, uint8: Uint8Array, int32: Int32Array };
  function pack(header, buffers) {
    const h = new TextEncoder().encode(JSON.stringify(header));
    const len = new Uint8Array(4);
    new DataView(len.buffer).setUint32(0, h.length, true);
    return new Blob([len, h, ...buffers]);
  }
  async function unpack(res) {
    const buf = await res.arrayBuffer();
    const hl = new DataView(buf).getUint32(0, true);
    const header = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, 4, hl)));
    let o = 4 + hl;
    const out = {};
    for (const t of header.outputs) {
      const bytes = buf.slice(o, o + t.byteLength);
      o += t.byteLength;
      out[t.name] = new Tensor(t.type, new ARR[t.type](bytes), t.dims);
    }
    return out;
  }
  class InferenceSession {
    static async create(model, opts) {
      const body = model instanceof ArrayBuffer ? model : model.buffer.slice(model.byteOffset, model.byteOffset + model.byteLength);
      const r = await fetch(`${origin}/__ort/load`, {
        method: 'POST',
        body,
        headers: { 'X-Ort-Env': JSON.stringify({ numThreads: env.wasm.numThreads, wasmPaths: env.wasm.wasmPaths, eps: opts && opts.executionProviders, coi: self.crossOriginIsolated }) },
      });
      if (!r.ok) throw new Error(`mock load failed ${r.status}`);
      const s = new InferenceSession();
      Object.assign(s, await r.json());
      return s;
    }
    async run(feeds) {
      const header = { feeds: [] };
      const bufs = [];
      for (const [name, t] of Object.entries(feeds)) {
        header.feeds.push({ name, type: t.type, dims: t.dims, byteLength: t.data.byteLength });
        bufs.push(t.data);
      }
      const r = await fetch(`${origin}/__ort/run?sid=${this.sid}`, { method: 'POST', body: pack(header, bufs) });
      if (!r.ok) throw new Error(`mock run failed ${r.status}: ${await r.text()}`);
      return unpack(r);
    }
    async release() {}
  }
  self.ort = { Tensor, InferenceSession, env };
})();
