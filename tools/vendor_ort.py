"""Optional: download ONNX Runtime Web into ./ort so the app never needs the CDN.

The app works without this (it fetches the runtime from jsDelivr on first launch and caches it).
Run from the app folder:  python tools/vendor_ort.py
"""
import pathlib
import urllib.request

VERSION = "1.20.1"  # keep in sync with ORT_VERSION in sw.js and js/engine.js
CDN = f"https://cdn.jsdelivr.net/npm/onnxruntime-web@{VERSION}/dist/"
FILES = [
    "ort.min.js", "ort-wasm-simd-threaded.mjs", "ort-wasm-simd-threaded.wasm",            # CPU
    "ort.webgpu.min.js", "ort-wasm-simd-threaded.jsep.mjs", "ort-wasm-simd-threaded.jsep.wasm",  # GPU (beta setting)
]

out = pathlib.Path(__file__).resolve().parent.parent / "ort"
out.mkdir(exist_ok=True)
for name in FILES:
    try:
        data = urllib.request.urlopen(CDN + name, timeout=120).read()
        (out / name).write_bytes(data)
        print(f"ok   {name} ({len(data) / 1e6:.1f} MB)")
    except Exception as e:  # the app still falls back to the CDN for anything missing
        print(f"skip {name}: {e}")
