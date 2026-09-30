"""Optional: download ONNX Runtime Web into ./ort so the app never needs the CDN.

The published site gets its copy from the publish workflow, and the app falls back to jsDelivr for
anything missing, so this is only for hosting PlateSight somewhere else. The version and file names
come from js/config.js. Run from the app folder:  python tools/vendor_ort.py
"""
import pathlib
import re
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = (ROOT / "js" / "config.js").read_text()
VERSION = re.search(r"version:\s*'([0-9.]+)'", CONFIG).group(1)
CDN = f"https://cdn.jsdelivr.net/npm/onnxruntime-web@{VERSION}/dist/"
# Scripts and WebAssembly engines named in js/config.js (CPU and GPU), plus the matching loaders.
NAMES = re.findall(r"(?:script|wasm):\s*'([\w.-]+)'", CONFIG)
FILES = NAMES + [n.replace(".wasm", ".mjs") for n in NAMES if n.endswith(".wasm")]

out = ROOT / "ort"
out.mkdir(exist_ok=True)
print(f"ONNX Runtime Web {VERSION}")
for name in FILES:
    try:
        data = urllib.request.urlopen(CDN + name, timeout=120).read()
        (out / name).write_bytes(data)
        print(f"ok   {name} ({len(data) / 1e6:.1f} MB)")
    except Exception as e:  # the app still falls back to the CDN for anything missing
        print(f"skip {name}: {e}")
