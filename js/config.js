/* PlateSight configuration: the single source of truth for versions and model files.
 * A plain script (not a module) so the page, the service worker (importScripts), the publish
 * workflow and the tests can all read it. The publish workflow stamps the commit id into `app`,
 * which is what makes installed phones pick up each new version.
 * Model `rev` values are the first 12 hex digits of each file's SHA-256 (checked by the unit tests),
 * so replacing a model file automatically refreshes the copy cached on phones.
 */
self.PS_CONFIG = {
  app: '281ee0d',          // build id, stamped by the publish workflow
  release: '2.0',      // shown in Settings; "What's new" appears once per release
  ort: {
    version: '1.20.1',
    // Script + WebAssembly engine per mode, with sizes for the first-launch progress bar (checked by the publish workflow).
    cpu: { script: 'ort.min.js', scriptBytes: 446284, wasm: 'ort-wasm-simd-threaded.wasm', wasmBytes: 11246032 },
    gpu: { script: 'ort.webgpu.min.js', scriptBytes: 333594, wasm: 'ort-wasm-simd-threaded.jsep.wasm', wasmBytes: 21663894 },
  },
  models: {
    det384: { file: 'models/plate-detector-384.onnx', kind: 'det', size: 384, bytes: 7771218, rev: '888397b96d76' },
    det640: { file: 'models/plate-detector-640.onnx', kind: 'det', size: 640, bytes: 7835770, rev: 'c3c1026ca7d0' },
    ocrFast: { file: 'models/plate-ocr-fast.onnx', kind: 'ocr', bytes: 3344276, rev: '18054a6ca401' },
    ocrAcc: { file: 'models/plate-ocr-accurate.onnx', kind: 'ocr', bytes: 5262214, rev: '8341fdfac841' },
  },
};
