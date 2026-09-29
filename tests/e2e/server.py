"""Static server for the app + bridge endpoints that run ONNX models with Python onnxruntime."""
import hashlib
import json
import os
import struct
import sys
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import numpy as np
import onnxruntime as ort

APP = sys.argv[1]
ORT_DIR = os.environ.get("ORT_DIR")  # optional: serve real ONNX Runtime Web files at /ort/ (like the published site)
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
SESSIONS = {}
LOG = {"loads": [], "runs": {}, "env": []}
LOCK = threading.Lock()
DT = {"float32": np.float32, "uint8": np.uint8, "int32": np.int32}
NP2 = {np.dtype("float32"): "float32", np.dtype("uint8"): "uint8", np.dtype("int32"): "int32", np.dtype("int64"): "int32"}


class H(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, ".js": "text/javascript", ".mjs": "text/javascript",
                      ".webmanifest": "application/manifest+json", ".onnx": "application/octet-stream",
                      ".wasm": "application/wasm", ".json": "application/json"}

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=APP, **kw)

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/octet-stream"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        p = urlparse(self.path).path
        if ORT_DIR and p.startswith("/ort/"):
            f = os.path.join(ORT_DIR, os.path.basename(p))
            if not os.path.isfile(f):
                return self._send(404, b"not found", "text/html")
            ctype = {"js": "application/javascript", "mjs": "application/javascript", "wasm": "application/wasm"}.get(f.rsplit(".", 1)[-1], "application/octet-stream")
            return self._send(200, open(f, "rb").read(), ctype)
        if p == "/__log":
            return self._send(200, json.dumps(LOG).encode(), "application/json")
        return super().do_GET()

    def do_POST(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length", 0))
        data = self.rfile.read(n)
        try:
            if u.path == "/__ort/load":
                sid = hashlib.sha1(data).hexdigest()[:12]
                with LOCK:
                    if sid not in SESSIONS:
                        SESSIONS[sid] = ort.InferenceSession(data, providers=["CPUExecutionProvider"])
                    LOG["loads"].append({"sid": sid, "bytes": len(data)})
                    LOG["env"].append(json.loads(self.headers.get("X-Ort-Env") or "{}"))
                s = SESSIONS[sid]
                out = {"sid": sid, "inputNames": [i.name for i in s.get_inputs()], "outputNames": [o.name for o in s.get_outputs()]}
                return self._send(200, json.dumps(out).encode(), "application/json")
            if u.path == "/__ort/run":
                sid = parse_qs(u.query)["sid"][0]
                s = SESSIONS[sid]
                hl = struct.unpack("<I", data[:4])[0]
                header = json.loads(data[4:4 + hl])
                o = 4 + hl
                feeds = {}
                for f in header["feeds"]:
                    arr = np.frombuffer(data[o:o + f["byteLength"]], dtype=DT[f["type"]]).reshape(f["dims"])
                    o += f["byteLength"]
                    feeds[f["name"]] = arr
                t0 = time.time()
                names = [x.name for x in s.get_outputs()]
                outs = s.run(names, feeds)
                with LOCK:
                    r = LOG["runs"].setdefault(sid, {"n": 0, "ms": 0.0, "batch": 0})
                    r["n"] += 1
                    r["ms"] += (time.time() - t0) * 1000
                    r["batch"] = max(r["batch"], int(list(feeds.values())[0].shape[0]))
                meta = []
                bufs = []
                for name, arr in zip(names, outs):
                    arr = np.ascontiguousarray(arr.astype(np.float32) if arr.dtype != np.uint8 else arr)
                    b = arr.tobytes()
                    meta.append({"name": name, "type": NP2[arr.dtype], "dims": list(arr.shape), "byteLength": len(b)})
                    bufs.append(b)
                hb = json.dumps({"outputs": meta}).encode()
                return self._send(200, struct.pack("<I", len(hb)) + hb + b"".join(bufs))
        except Exception as e:  # surface errors to the page
            return self._send(500, repr(e).encode(), "text/plain")
        return self._send(404, b"not found", "text/plain")


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    print(f"serving {APP} on {PORT}", flush=True)
    srv.serve_forever()
