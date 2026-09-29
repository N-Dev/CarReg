// Checks a copy of ONNX Runtime Web against js/config.js: every file the app loads exists and has
// the size the first-launch progress bar expects. Usage: node tools/check_runtime.mjs <dist dir>
import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dir = process.argv[2];
if (!dir) { console.error('usage: node tools/check_runtime.mjs <onnxruntime-web dist dir>'); process.exit(2); }
const box = { self: {} };
vm.runInNewContext(readFileSync(path.join(root, 'js/config.js'), 'utf8'), box);
const ort = box.self.PS_CONFIG.ort;
let bad = 0;
for (const [mode, m] of Object.entries({ cpu: ort.cpu, gpu: ort.gpu })) {
  for (const [file, bytes] of [[m.script, m.scriptBytes], [m.wasm, m.wasmBytes], [m.wasm.replace(/\.wasm$/, '.mjs'), null]]) {
    let size = -1;
    try { size = statSync(path.join(dir, file)).size; } catch (_) { /* missing */ }
    if (size < 0) { console.log(`::error::${mode}: ${file} is missing from ${dir}`); bad++; continue; }
    if (bytes != null && size !== bytes) { console.log(`::error::${mode}: ${file} is ${size} bytes but js/config.js says ${bytes}`); bad++; continue; }
    console.log(`ok  ${mode}  ${file}  ${(size / 1e6).toFixed(2)} MB`);
  }
}
console.log(bad ? `${bad} problem(s) with ONNX Runtime Web ${ort.version}` : `ONNX Runtime Web ${ort.version} matches js/config.js`);
process.exit(bad ? 1 : 0);
