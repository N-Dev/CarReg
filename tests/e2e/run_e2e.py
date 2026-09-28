"""End-to-end test (needs: pip install playwright onnxruntime numpy; ffmpeg).
End-to-end test of PlateSight Mobile in Chromium emulating a Pixel 7 (fake camera, real models via mock ORT)."""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.request

os.environ["PW_EXPERIMENTAL_SERVICE_WORKER_NETWORK_EVENTS"] = "1"  # let the test route service-worker requests

from playwright.sync_api import sync_playwright

H = os.path.dirname(os.path.abspath(__file__))
APP = os.path.abspath(os.path.join(H, "..", ".."))
T = os.path.join(H, "assets")
WORK = os.path.join(H, ".work")
SHOTS = os.path.join(WORK, "shots")
os.makedirs(SHOTS, exist_ok=True)
CAM = os.path.join(WORK, "cam_ie.y4m")
VID = os.path.join(WORK, "two_cars.webm")
if not os.path.exists(CAM):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-loop", "1", "-i", f"{T}/car_ie.jpg", "-vf",
                    "crop=1280:720:'min(274,t*55)':'min(273,t*45)',format=yuv420p", "-t", "6", "-r", "15", CAM], check=True)
if not os.path.exists(VID):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-loop", "1", "-i", f"{T}/two_cars.jpg", "-vf",
                    "crop=1280:720:'min(1828,t*230)':140,format=yuv420p", "-t", "8", "-r", "25", "-c:v", "libvpx", "-b:v", "3M", "-an", VID], check=True)
PORT = 8765
BASE = f"http://127.0.0.1:{PORT}/"
os.makedirs(SHOTS, exist_ok=True)
MOCK = open(f"{H}/mock-ort.js").read()
results = {}
logs = []
cdn_hits = []


def log(*a):
    print(*a, flush=True)


def check(name, cond, detail=""):
    results[name] = bool(cond)
    log(("PASS" if cond else "FAIL"), name, detail)


srv = subprocess.Popen([sys.executable, f"{H}/server.py", APP, str(PORT)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(1.0)

try:
    with sync_playwright() as p:
        browser = p.chromium.launch(args=[
            "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream",
            f"--use-file-for-fake-video-capture={CAM}",
            "--autoplay-policy=no-user-gesture-required",
        ])
        dev = dict(p.devices["Pixel 7"])
        ctx = browser.new_context(**dev, permissions=["camera"], service_workers="allow")

        def cdn(route):
            url = route.request.url
            cdn_hits.append(url)
            name = url.rsplit("/", 1)[-1]
            if name in ("ort.min.js", "ort.webgpu.min.js"):
                route.fulfill(status=200, body=MOCK, headers={"Content-Type": "text/javascript", "Access-Control-Allow-Origin": "*"})
            else:
                route.fulfill(status=404, body="nope")

        ctx.route("https://cdn.jsdelivr.net/**", cdn)
        page = ctx.new_page()
        page.on("console", lambda m: logs.append(f"[{m.type}] {m.text}"))
        page.on("pageerror", lambda e: logs.append(f"[pageerror] {e}"))

        # ---------------------------------------------------------------- first launch
        t0 = time.time()
        page.goto(BASE)
        page.wait_for_timeout(600)
        page.screenshot(path=f"{SHOTS}/01_first_launch.png")
        page.wait_for_function("() => window.__plateSight && window.__plateSight.state.ready", timeout=120000)
        info = page.evaluate("() => ({ info: window.__plateSight.engine.info, coi: self.crossOriginIsolated, ctrl: !!navigator.serviceWorker.controller })")
        log("boot", round(time.time() - t0, 1), "s", json.dumps(info))
        check("service worker controls page", info["ctrl"])
        check("cross-origin isolated (multi-thread capable)", info["coi"])
        check("runtime loaded via SW proxy of CDN", info["info"]["source"] == "app" and any("ort.min.js" in u for u in cdn_hits), f"cdn hits: {cdn_hits}")
        check("multi-threaded config", info["info"]["threads"] > 1, f"threads={info['info']['threads']}")

        # ---------------------------------------------------------------- live scan (fake camera)
        page.wait_for_selector(".tray-chip", timeout=60000)
        page.wait_for_timeout(1200)
        page.screenshot(path=f"{SHOTS}/02_live_scan.png")
        chips = page.eval_on_selector_all(".tray-chip strong", "els => els.map(e => e.textContent)")
        check("live: Irish plate confirmed from camera", "241-D-12345" in chips, str(chips))
        live = page.evaluate("() => { const s = window.__plateSight.scan; return { fps: s.fps, tracks: s.tracker.tracks.map(t => ({ id: t.id, n: t.reads.length, text: t.result && t.result.text, region: t.result && t.result.region, conf: t.result && t.result.conf })) }; }")
        log("live", json.dumps(live))
        page.click(".tray-chip")
        page.wait_for_timeout(500)
        page.screenshot(path=f"{SHOTS}/03_live_detail.png")
        detail = page.inner_text("#detailBody")
        check("live detail decodes county/year", "Dublin" in detail and "2024" in detail, detail.replace("\n", " | ")[:200])
        page.click("#backdrop", position={"x": 200, "y": 24})
        page.wait_for_timeout(400)

        # ---------------------------------------------------------------- photo (two cars)
        page.click("#tabbar button[data-mode=photo]")
        page.wait_for_timeout(300)
        page.screenshot(path=f"{SHOTS}/04_photo_empty.png")
        page.set_input_files("#filePhoto", f"{T}/two_cars.jpg")
        page.wait_for_function("() => { const h = document.querySelector('#photoHead h3'); return h && /found|No plates/.test(h.textContent); }", timeout=120000)
        page.wait_for_timeout(700)
        page.screenshot(path=f"{SHOTS}/05_photo_result.png")
        texts = page.eval_on_selector_all("#photoResults .plate strong", "els => els.map(e => e.textContent)")
        check("photo: both plates read", "241-D-12345" in texts and "AB12 CDE" in texts, str(texts))
        tags = page.inner_text("#photoResults")
        check("photo: countries recognised", "Ireland" in tags and "United Kingdom" in tags)
        page.evaluate("() => document.querySelector('#view-photo').scrollTo(0, 99999)")
        page.wait_for_timeout(400)
        page.screenshot(path=f"{SHOTS}/06_photo_cards.png")
        page.click("#photoResults [data-act=detail]")
        page.wait_for_timeout(500)
        page.screenshot(path=f"{SHOTS}/07_photo_detail.png")
        page.go_back()
        page.wait_for_timeout(400)
        check("back button closes sheet", not page.evaluate("() => document.querySelector('#sheetDetail').classList.contains('open')"))

        # ---------------------------------------------------------------- video
        page.click("#tabbar button[data-mode=video]")
        page.wait_for_timeout(300)
        page.screenshot(path=f"{SHOTS}/08_video_empty.png")
        page.set_input_files("#fileVideo", VID)
        page.wait_for_timeout(3500)
        page.screenshot(path=f"{SHOTS}/09_video_running.png")
        page.wait_for_function("() => /^Done/.test(document.querySelector('#vidStatus').textContent)", timeout=180000)
        page.wait_for_timeout(500)
        page.screenshot(path=f"{SHOTS}/10_video_done.png")
        vt = page.eval_on_selector_all("#videoResults .plate strong", "els => els.map(e => e.textContent)")
        vstat = page.inner_text("#vidStatus")
        check("video: both plates found", "241-D-12345" in vt and "AB12 CDE" in vt, f"{vt} | {vstat}")

        # ---------------------------------------------------------------- history & settings
        page.click("#tabbar button[data-mode=history]")
        page.wait_for_selector(".hist-item", timeout=10000)
        page.wait_for_timeout(400)
        page.screenshot(path=f"{SHOTS}/11_history.png")
        hist = page.eval_on_selector_all(".hist-item .plate strong", "els => els.map(e => e.textContent)")
        counts = page.eval_on_selector_all(".hist-item .meta", "els => els.map(e => e.textContent)")
        check("history has both plates", "241-D-12345" in hist and "AB12 CDE" in hist, f"{hist} {counts}")
        page.fill("#histSearch", "ab12")
        page.wait_for_timeout(300)
        filtered = page.eval_on_selector_all(".hist-item .plate strong", "els => els.map(e => e.textContent)")
        check("history search", filtered == ["AB12 CDE"], str(filtered))
        page.fill("#histSearch", "")
        with page.expect_download(timeout=10000) as dl:
            page.click("#btnExport")
        csv = open(dl.value.path()).read()
        check("CSV export", "241-D-12345" in csv and "AB12 CDE" in csv, csv.splitlines()[0])
        page.click("#btnSettings")
        page.wait_for_timeout(500)
        page.screenshot(path=f"{SHOTS}/12_settings.png")
        page.click("#backdrop", position={"x": 200, "y": 24})
        page.wait_for_timeout(300)

        # ---------------------------------------------------------------- share target
        b64 = base64.b64encode(open(f"{T}/car_uk.jpg", "rb").read()).decode()
        status = page.evaluate("""async (b64) => {
            const bin = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
            const fd = new FormData();
            fd.append('media', new File([bin], 'car.jpg', { type: 'image/jpeg' }));
            const r = await fetch('share-target', { method: 'POST', body: fd });
            return r.url + ' ' + r.status;
        }""", b64)
        log("share POST ->", status)
        page.goto(BASE + "?shared=1")
        page.wait_for_function("() => { const h = document.querySelector('#photoHead h3'); return h && /found/.test(h.textContent); }", timeout=60000)
        st = page.eval_on_selector_all("#photoResults .plate strong", "els => els.map(e => e.textContent)")
        check("share target opens photo in app", st == ["AB12 CDE"], str(st))

        # ---------------------------------------------------------------- offline relaunch
        # Block every network request except the harness's model bridge (a real phone runs the models locally).
        blocked = []

        def offline(route):
            if "/__ort/" in route.request.url:
                route.continue_()
            else:
                blocked.append(route.request.url)
                route.abort("internetdisconnected")

        ctx.unroute("https://cdn.jsdelivr.net/**")
        ctx.route("https://cdn.jsdelivr.net/**", offline)
        ctx.route(f"http://127.0.0.1:{PORT}/**", offline)
        page.reload()
        try:
            page.wait_for_function("() => window.__plateSight && (window.__plateSight.state.ready || window.__plateSight.state.bootError)", timeout=60000)
        finally:
            log("offline boot:", page.evaluate("() => { const s = window.__plateSight && window.__plateSight.state; return s ? { ready: s.ready, err: s.bootError && s.bootError.message } : 'no app'; }"), "blocked:", blocked[:5])
        page.click("#tabbar button[data-mode=photo]")
        page.set_input_files("#filePhoto", f"{T}/car_ie.jpg")
        page.wait_for_function("() => { const h = document.querySelector('#photoHead h3'); return h && /found|No plates|Couldn/.test(h.textContent); }", timeout=60000)
        ot = page.eval_on_selector_all("#photoResults .plate strong", "els => els.map(e => e.textContent)")
        check("works fully offline after first launch", ot == ["241-D-12345"] and not blocked, f"{ot} blocked={blocked[:3]}")
        page.screenshot(path=f"{SHOTS}/13_offline_photo.png")
        browser.close()
finally:
    srv.terminate()

errors = [l for l in logs if l.startswith("[error]") or l.startswith("[pageerror]")]
log("\nconsole errors:", len(errors))
for l in errors[:20]:
    log("  ", l[:300])
log("\nSUMMARY", sum(results.values()), "/", len(results), "passed")
