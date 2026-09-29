"""End-to-end tests of PlateSight in Chromium emulating a Pixel 7, with a fake camera.

Needs: pip install playwright onnxruntime numpy pillow; ffmpeg.
By default the AI runtime is a stand-in that runs the real models with Python onnxruntime (mock-ort.js).
REAL_ORT=/path/to/onnxruntime-web/dist runs the real WebAssembly runtime, served at /ort/ like the
published site (this is what the publish workflow does). Exits non-zero if any check fails.

Sections:
  main      first launch, live scan, photo, video, history, settings, share target, offline
  cars      moving the camera from one car to the next (no mixed-up plates or photos)
  debug     developer easter egg, live debug overlay, read inspector, field test, debug panel,
            benchmark, accuracy stats, training-set export, quality override, history privacy
  slow      slow first launch (no false "hang", no safe mode) and an update arriving mid-download
  upgrade   a phone upgrading from 1.0: downloaded models carry over, "What's new" once
  fallback  a damaged copy of the AI runtime on the site (real runtime only)
  gpu       GPU mode on WebGPU (real runtime only; Chromium's software WebGPU adapter)
Run a subset with SECTIONS=debug,slow.
"""
import base64
import io
import json
import os
import re
import subprocess
import sys
import time
import zipfile

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
PAIR = os.path.join(WORK, "cam_pair.y4m")


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args], check=True)


if not os.path.exists(CAM):
    ffmpeg("-loop", "1", "-i", f"{T}/car_ie.jpg", "-vf", "crop=1280:720:'min(274,t*55)':'min(273,t*45)',format=yuv420p", "-t", "6", "-r", "15", CAM)
if not os.path.exists(PAIR):  # car A, then a cut to car B with its plate at the same spot on screen
    ffmpeg("-loop", "1", "-t", "3", "-i", f"{T}/car_a_12D15405.jpg", "-loop", "1", "-t", "3", "-i", f"{T}/car_b_201D8573.jpg", "-filter_complex",
           "[0:v]crop=560:993:'640+t*12':0,scale=720:1276,pad=720:1280:0:2,fps=15,format=yuv420p[a];"
           "[1:v]crop=560:993:'640+t*12':0,scale=720:1276,pad=720:1280:0:2,fps=15,format=yuv420p[b];[a][b]concat=n=2:v=1:a=0[v]",
           "-map", "[v]", PAIR)
if not os.path.exists(VID):
    ffmpeg("-loop", "1", "-i", f"{T}/two_cars.jpg", "-vf", "crop=1280:720:'min(1828,t*230)':140,format=yuv420p", "-t", "8", "-r", "25",
           "-c:v", "libvpx", "-b:v", "3M", "-an", VID)

PORT = 8765
BASE = f"http://127.0.0.1:{PORT}/"
MOCK = open(f"{H}/mock-ort.js").read()
REAL_ORT = os.environ.get("REAL_ORT")
SECTIONS = [s for s in os.environ.get("SECTIONS", "main,cars,debug,slow,upgrade,fallback,gpu").split(",") if s]
results = {}
logs = []
cdn_hits = []

READY = "() => window.__plateSight && window.__plateSight.state.ready"
PLATES = "els => els.map(e => e.textContent)"


def log(*a):
    print(*a, flush=True)


def check(name, cond, detail=""):
    results[name] = bool(cond)
    log(("PASS" if cond else "FAIL"), name, detail)


def cdn(route):
    """Stands in for the jsDelivr CDN: the mock runtime (unless testing the real one), nothing else."""
    url = route.request.url
    cdn_hits.append(url)
    name = url.rsplit("/", 1)[-1]
    cors = {"Access-Control-Allow-Origin": "*"}
    if REAL_ORT and os.path.isfile(os.path.join(REAL_ORT, name)):  # the real runtime, like jsDelivr serves it
        ctype = "application/wasm" if name.endswith(".wasm") else "text/javascript"
        route.fulfill(status=200, body=open(os.path.join(REAL_ORT, name), "rb").read(), headers={"Content-Type": ctype, **cors})
    elif name in ("ort.min.js", "ort.webgpu.min.js") and not REAL_ORT:
        route.fulfill(status=200, body=MOCK, headers={"Content-Type": "text/javascript", **cors})
    elif name.endswith(".wasm") and not REAL_ORT:  # the mock runtime ignores the engine binary
        route.fulfill(status=200, body=b"\0asm" + bytes(4096), headers={"Content-Type": "application/wasm", **cors})
    else:
        route.fulfill(status=404, body="nope")


def launch(p, cam=CAM, extra=()):
    return p.chromium.launch(args=["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
                                   f"--use-file-for-fake-video-capture={cam}", "--autoplay-policy=no-user-gesture-required", *extra])


def new_page(browser, dev, **kw):
    ctx = browser.new_context(**dev, permissions=["camera"], service_workers="allow", accept_downloads=True, **kw)
    ctx.route("https://cdn.jsdelivr.net/**", cdn)
    page = ctx.new_page()
    page.on("console", lambda m: logs.append(f"[{m.type}] {m.text}"))
    page.on("pageerror", lambda e: logs.append(f"[pageerror] {e}"))
    return ctx, page


def close_sheet(page):
    page.click("#backdrop", position={"x": 200, "y": 24})
    page.wait_for_timeout(400)


def photo(page, path, pattern="found|No plates"):
    page.click("#tabbar button[data-mode=photo]")
    page.set_input_files("#filePhoto", path)
    page.wait_for_function(f"() => {{ const h = document.querySelector('#photoHead h3'); return h && /{pattern}/.test(h.textContent); }}", timeout=180000)
    page.wait_for_timeout(500)
    return page.eval_on_selector_all("#photoResults .plate strong", PLATES)


def hist_keys(page):
    page.click("#tabbar button[data-mode=history]")
    page.wait_for_timeout(600)
    return page.eval_on_selector_all(".hist-item", "els => els.map(e => e.dataset.key)")


# ==================================================================== main
def section_main(p, dev):
    browser = launch(p)
    ctx, page = new_page(browser, dev)

    # ---------------------------------------------------------------- first launch
    t0 = time.time()
    hits0 = len(cdn_hits)
    page.goto(BASE)
    page.wait_for_timeout(600)
    page.screenshot(path=f"{SHOTS}/01_first_launch.png")
    page.wait_for_function(READY, timeout=120000)
    info = page.evaluate("() => ({ info: window.__plateSight.engine.info, coi: self.crossOriginIsolated, ctrl: !!navigator.serviceWorker.controller })")
    log("boot", round(time.time() - t0, 1), "s", json.dumps(info))
    check("service worker controls page", info["ctrl"])
    check("cross-origin isolated (multi-thread capable)", info["coi"])
    if REAL_ORT:
        check("real runtime loaded from the site copy", info["info"]["source"] == "site" and len(cdn_hits) == hits0 and info["info"]["version"] != "mock",
              f"version={info['info']['version']} cdn hits: {cdn_hits[hits0:]}")
    else:
        check("runtime loaded via SW proxy of CDN", info["info"]["source"] == "site" and any("ort.min.js" in u for u in cdn_hits[hits0:]), f"cdn hits: {cdn_hits[hits0:]}")
    check("multi-threaded config", info["info"]["threads"] > 1, f"threads={info['info']['threads']}")

    # ---------------------------------------------------------------- live scan (fake camera)
    page.wait_for_selector(".tray-chip", timeout=60000)
    page.wait_for_timeout(1200)
    page.screenshot(path=f"{SHOTS}/02_live_scan.png")
    chips = page.eval_on_selector_all(".tray-chip strong", PLATES)
    check("live: Irish plate confirmed from camera", "241-D-12345" in chips, str(chips))
    live = page.evaluate("() => { const s = window.__plateSight.scan; return { fps: s.fps, tier: s.adaptive.tier, tracks: s.tracker.tracks.map(t => ({ id: t.id, n: t.reads.length, text: t.result && t.result.text, conf: t.result && t.result.conf })) }; }")
    log("live", json.dumps(live))
    page.click(".tray-chip")
    page.wait_for_timeout(500)
    page.screenshot(path=f"{SHOTS}/03_live_detail.png")
    detail = page.inner_text("#detailBody")
    check("live detail decodes county/year", "Dublin" in detail and "2024" in detail, detail.replace("\n", " | ")[:200])
    close_sheet(page)

    # ---------------------------------------------------------------- photo (two cars)
    page.click("#tabbar button[data-mode=photo]")
    page.wait_for_timeout(300)
    page.screenshot(path=f"{SHOTS}/04_photo_empty.png")
    texts = photo(page, f"{T}/two_cars.jpg")
    page.screenshot(path=f"{SHOTS}/05_photo_result.png")
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
    vt = page.eval_on_selector_all("#videoResults .plate strong", PLATES)
    check("video: both plates found", "241-D-12345" in vt and "AB12 CDE" in vt, f"{vt} | {page.inner_text('#vidStatus')}")

    # ---------------------------------------------------------------- history & settings
    page.click("#tabbar button[data-mode=history]")
    page.wait_for_selector(".hist-item", timeout=10000)
    page.wait_for_timeout(400)
    page.screenshot(path=f"{SHOTS}/11_history.png")
    hist = page.eval_on_selector_all(".hist-item .plate strong", PLATES)
    counts = page.eval_on_selector_all(".hist-item .meta", PLATES)
    check("history has both plates", "241-D-12345" in hist and "AB12 CDE" in hist, f"{hist} {counts}")
    page.fill("#histSearch", "ab12")
    page.wait_for_timeout(400)
    filtered = page.eval_on_selector_all(".hist-item .plate strong", PLATES)
    check("history search", filtered == ["AB12 CDE"], str(filtered))
    page.fill("#histSearch", "")
    page.wait_for_timeout(300)
    with page.expect_download(timeout=10000) as dl:
        page.click("#btnExport")
    csv = open(dl.value.path()).read()
    check("CSV export", "241-D-12345" in csv and "AB12 CDE" in csv, csv.splitlines()[0])
    page.click("#btnSettings")
    page.wait_for_timeout(500)
    page.screenshot(path=f"{SHOTS}/12_settings.png")
    close_sheet(page)

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
    st = page.eval_on_selector_all("#photoResults .plate strong", PLATES)
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
    ot = photo(page, f"{T}/car_ie.jpg", "found|No plates|Couldn")
    check("works fully offline after first launch", ot == ["241-D-12345"] and not blocked, f"{ot} blocked={blocked[:3]}")
    page.screenshot(path=f"{SHOTS}/13_offline_photo.png")
    browser.close()


# ==================================================================== moving between cars
def tint(url):
    from PIL import Image
    im = Image.open(io.BytesIO(base64.b64decode(url.split(",", 1)[1]))).convert("RGB").resize((32, 16))
    px = list(im.getdata())
    r = sum(q[0] for q in px) / len(px)
    g = sum(q[1] for q in px) / len(px)
    return "red" if r > g * 1.4 else "silver"


def section_cars(p, dev):
    browser = launch(p, PAIR)
    ctx, page = new_page(browser, dev)
    page.goto(BASE)
    page.wait_for_function(READY, timeout=120000)
    page.wait_for_function("() => window.__plateSight.scan.running", timeout=30000)
    page.wait_for_timeout(13000)
    page.click("#btnScan")
    page.wait_for_timeout(1000)
    entries = page.evaluate("() => window.__plateSight.scan.board.entries().map(e => ({ text: e.r.text, conf: e.r.conf, thumb: e.thumbURL }))")
    got = {e["text"]: (round(e["conf"], 2), tint(e["thumb"]) if e["thumb"] else None) for e in entries}
    check("switching cars: two separate plates, each with its own photo",
          got.get("12-D-15405", (0, None))[1] == "silver" and got.get("201-D-8573", (0, None))[1] == "red"
          and len(got) == 2 and all(c >= 0.9 for c, _ in got.values()), str(got))
    browser.close()


# ==================================================================== debug mode, field test, privacy
def section_debug(p, dev):
    browser = launch(p)
    ctx, page = new_page(browser, dev)
    page.goto(BASE)
    page.wait_for_function(READY, timeout=120000)
    page.wait_for_function("() => window.__plateSight.scan.running", timeout=30000)

    # ---------------------------------------------------------------- the easter egg
    for _ in range(7):
        page.click("#brand")
        page.wait_for_timeout(120)
    page.wait_for_timeout(300)
    toast = page.inner_text("#toast")
    dbg = page.evaluate("() => ({ on: window.__plateSight.settings.debug, btn: !document.querySelector('#btnDebug').hidden, cls: document.body.classList.contains('is-debug') })")
    check("debug: 7 taps on the logo unlock debug mode", dbg["on"] and dbg["btn"] and dbg["cls"] and "developer" in toast, f"{dbg} {toast!r}")

    # ---------------------------------------------------------------- live overlay and speed graph
    page.wait_for_selector(".tray-chip", timeout=60000)
    page.wait_for_timeout(2500)
    live = page.evaluate("""() => { const s = window.__plateSight.scan; return { raw: s.last && Array.isArray(s.last.raw) ? s.last.raw.length : -1,
        region: !!(s.last && s.last.region), hud: !document.querySelector('#hud').hidden, perf: s.perf.length, text: document.querySelector('#hudText').textContent }; }""")
    check("debug: live overlay data and speed graph", live["raw"] >= 1 and live["region"] and live["hud"] and live["perf"] > 3, json.dumps(live))
    page.screenshot(path=f"{SHOTS}/20_debug_live.png")

    # ---------------------------------------------------------------- read inspector + field test (right)
    page.click(".tray-chip")
    page.wait_for_selector("#sheetDetail.open .insp__chars .ch", timeout=15000)
    page.wait_for_timeout(500)
    insp = page.evaluate("""() => ({ chars: [...document.querySelectorAll('.insp__chars .ch b')].map(b => b.textContent).join(''),
        img: !!document.querySelector('.insp__input img'), votes: document.querySelectorAll('.insp__votes .vote').length })""")
    check("debug: read inspector shows each character's confidence", insp["chars"] == "241D12345" and insp["img"] and insp["votes"] >= 1, json.dumps(insp))
    page.evaluate("() => document.querySelector('#sheetDetail').scrollTo(0, 99999)")
    page.wait_for_timeout(300)
    page.screenshot(path=f"{SHOTS}/21_inspector.png")
    page.click("[data-act=ft-right]")
    page.wait_for_selector(".ft__done:not([hidden])", timeout=5000)
    ft = page.inner_text(".ft__done")
    check("field test: mark a reading right", "sample #1" in ft, ft)
    close_sheet(page)

    # ---------------------------------------------------------------- field test (wrong, corrected)
    photo(page, f"{T}/two_cars.jpg")
    cards = page.locator("#photoResults .card")
    idx = next(i for i in range(cards.count()) if "AB12" in cards.nth(i).inner_text())
    cards.nth(idx).locator("[data-act=detail]").click()
    page.wait_for_selector("#sheetDetail.open [data-act=ft-wrong]", timeout=5000)
    page.click("[data-act=ft-wrong]")
    page.fill("#ftTruth", "AB12 CDF")
    page.click("[data-act=ft-save]")
    page.wait_for_selector(".ft__done:not([hidden]) .diff", timeout=5000)
    diff = page.inner_html(".ft__done .diff")
    check("field test: correct a wrong reading", "d-sub" in diff and "sample #2" in page.inner_text(".ft__done"), diff[:120])
    page.evaluate("() => document.querySelector('#sheetDetail').scrollTo(0, 99999)")
    page.wait_for_timeout(300)
    page.screenshot(path=f"{SHOTS}/22_field_test.png")
    close_sheet(page)

    # ---------------------------------------------------------------- debug panel
    page.click("#btnDebug")
    page.wait_for_selector("#sheetDebug.open .dov .kv", timeout=20000)
    page.wait_for_timeout(1200)
    ov = page.inner_text("#debugBody")
    check("debug panel: overview diagnostics", "ONNX Runtime" in ov and "Plate finder 384" in ov and "CPU cores" in ov, ov[:160].replace("\n", " | "))
    page.screenshot(path=f"{SHOTS}/23_debug_overview.png")
    page.click("#debugTabs [data-tab=log]")
    page.wait_for_timeout(400)
    lg = page.inner_text("#debugBody")
    check("debug panel: event log", "Confirmed 241-D-12345" in lg and "Ready in" in lg and "Field test #2" in lg, lg[:160].replace("\n", " | "))
    page.screenshot(path=f"{SHOTS}/24_debug_log.png")
    page.click("#debugTabs [data-tab=tools]")
    page.wait_for_timeout(300)
    page.click("[data-dact=bench]")
    page.wait_for_selector("#debugBody .dtable td", timeout=90000)
    tools = page.inner_text("#debugBody")
    check("debug tools: model benchmark", "Plate finder 384" in tools and "Reader (fast)" in tools and "Median" in tools, tools[:160].replace("\n", " | "))
    page.screenshot(path=f"{SHOTS}/25_debug_tools.png")
    page.click("#debugTabs [data-tab=accuracy]")
    page.wait_for_selector(".dstats", timeout=10000)
    acc = page.inner_text("#debugBody")
    check("accuracy: 2 plates tested, 1 right", re.search(r"2\s*plates tested", acc) and re.search(r"50%\s*read exactly right", acc) and "AB12CDF" in acc, acc[:200].replace("\n", " | "))
    page.screenshot(path=f"{SHOTS}/26_accuracy.png")
    with page.expect_download(timeout=20000) as dl:
        page.click("[data-dact=export]")
    z = zipfile.ZipFile(dl.value.path())
    names = z.namelist()
    ann = z.read(next(n for n in names if n.endswith("train/annotations.csv"))).decode()
    imgs = [n for n in names if n.endswith(".jpg")]
    jpeg = all(z.read(n)[:2] == b"\xff\xd8" for n in imgs)
    check("training set: valid zip in the plate reader's training format", z.testzip() is None and "241D12345" in ann and "AB12CDF" in ann and len(imgs) == 2 and jpeg,
          f"{names} | {ann!r}")
    close_sheet(page)

    # ---------------------------------------------------------------- quality override
    page.click("#tabbar button[data-mode=scan]")
    page.evaluate("() => window.__plateSight.setSetting('quality', 'sharp')")
    try:
        page.wait_for_function("() => { const s = window.__plateSight.scan; return s.running && s.last && s.last.det === 'det640' && s.adaptive.tier === 'sharp'; }", timeout=90000)
        ok = True
    except Exception:
        ok = False
    q = page.evaluate("() => { const s = window.__plateSight.scan; return { tier: s.adaptive.tier, det: s.last && s.last.det, ocr: s.last && s.last.ocr, loaded: [...window.__plateSight.engine.ready] }; }")
    check("quality 'Sharp': the 640 plate finder is fetched and used", ok, json.dumps(q))
    page.evaluate("() => window.__plateSight.setSetting('quality', 'auto')")

    # ---------------------------------------------------------------- delete from history -> gone from tray
    keys = hist_keys(page)
    page.click(".hist-item[data-key='241D12345']")
    page.wait_for_selector("#sheetDetail.open [data-act=delete]", timeout=5000)
    page.click("#sheetDetail [data-act=delete]")
    page.wait_for_timeout(700)
    gone = page.evaluate("""() => ({ board: !!window.__plateSight.scan.board.get('241D12345'), chip: !!document.querySelector('.tray-chip[data-key="241D12345"]'),
        hist: [...document.querySelectorAll('.hist-item')].map(e => e.dataset.key) })""")
    check("deleting a plate from history also removes it from the scan tray", "241D12345" in keys and not gone["board"] and not gone["chip"] and "241D12345" not in gone["hist"],
          json.dumps(gone))

    # ---------------------------------------------------------------- keep photos off
    page.evaluate("() => window.__plateSight.setSetting('keepPhotos', false)")
    page.wait_for_selector("#toast.show button", timeout=5000)
    page.click("#toast button")
    page.wait_for_timeout(800)
    stripped = page.evaluate("() => document.querySelectorAll('.hist-item img').length")
    photo(page, f"{T}/car_ie.jpg")
    hist_keys(page)
    after = page.evaluate("() => ({ imgs: document.querySelectorAll('.hist-item img').length, keys: [...document.querySelectorAll('.hist-item')].map(e => e.dataset.key) })")
    check("keep photos off: existing photos removed, new plates saved without one", stripped == 0 and after["imgs"] == 0 and "241D12345" in after["keys"], f"{stripped} {after}")

    # ---------------------------------------------------------------- retention + what's new
    page.evaluate("""() => new Promise((res, rej) => {
        const r = indexedDB.open('platesight', 2);
        r.onsuccess = () => {
          const tx = r.result.transaction('plates', 'readwrite');
          const st = tx.objectStore('plates');
          const day = 86400000, now = Date.now();
          for (const [key, age] of [['OLD40', 40], ['OLD10', 10]]) {
            st.put({ key, text: key, region: null, profile: 'ANY', valid: false, conf: 0.9, count: 1, first: now - age * day, last: now - age * day, source: 'live', thumb: null });
          }
          tx.oncomplete = () => res(true);
          tx.onerror = () => rej(tx.error);
        };
        r.onerror = () => rej(r.error);
    })""")
    page.evaluate("() => localStorage.setItem('ps_release', '1.0')")
    page.reload()
    page.wait_for_function(READY, timeout=60000)
    try:
        page.wait_for_selector("#sheetNew.open", timeout=8000)
        new_ok = "What’s new" in page.inner_text("#sheetNew")
    except Exception:
        new_ok = False
    page.screenshot(path=f"{SHOTS}/27_whats_new.png")
    check("what's new appears once after an update", new_ok)
    close_sheet(page)
    keys = hist_keys(page)
    page.evaluate("() => window.__plateSight.setSetting('retention', '7')")
    page.wait_for_timeout(1000)
    keys7 = page.eval_on_selector_all(".hist-item", "els => els.map(e => e.dataset.key)")
    check("history: plates past the retention period are deleted automatically",
          "OLD40" not in keys and "OLD10" in keys and "OLD10" not in keys7 and "241D12345" in keys7, f"30 days: {keys} · 7 days: {keys7}")
    page.screenshot(path=f"{SHOTS}/28_history_retention.png")
    page.click("#btnSettings")
    page.wait_for_timeout(500)
    page.evaluate("() => document.querySelector('#sheetSettings').scrollTo(0, 99999)")
    page.wait_for_timeout(300)
    page.screenshot(path=f"{SHOTS}/29_settings_privacy.png")
    close_sheet(page)
    browser.close()


# ==================================================================== slow first launch
def section_slow(p, dev):
    browser = launch(p)
    ctx, page = new_page(browser, dev)
    ctx.add_cookies([{"name": "ps_slow", "value": "1", "url": BASE}])
    # A short hang guard and stall timeout: the old start-up logic fails this download speed.
    ctx.add_init_script("localStorage.setItem('ps_hang_ms', '4000'); localStorage.setItem('ps_stall_ms', '6000');")
    t0 = time.time()
    page.goto(BASE)
    page.wait_for_function("() => window.__plateSight && window.__plateSight.state.starting && !!navigator.serviceWorker.controller", timeout=60000)
    page.wait_for_timeout(2500)
    page.screenshot(path=f"{SHOTS}/30_slow_download.png")
    # A new version of the app arrives mid-download: it must not reload the page.
    page.evaluate("() => { window.__sameLoad = 1; navigator.serviceWorker.dispatchEvent(new Event('controllerchange')); }")
    page.wait_for_function("() => window.__plateSight.state.ready || window.__plateSight.state.bootError", timeout=240000)
    took = time.time() - t0
    r = page.evaluate("""() => ({ ready: window.__plateSight.state.ready, err: window.__plateSight.state.bootError && window.__plateSight.state.bootError.message,
        threads: window.__plateSight.engine.info && window.__plateSight.engine.info.threads, safe: localStorage.getItem('ps_force_safe'),
        errors: window.__plateSight.state.engineErrors, same: window.__sameLoad === 1 })""")
    check("slow first launch: no false 'hang', no safe mode", r["ready"] and r["threads"] > 1 and not r["safe"] and not r["errors"] and took > 6,
          f"{json.dumps(r)} took {took:.1f}s")
    check("an update during the first download doesn't interrupt it", r["same"])
    try:
        page.wait_for_function("() => /updated/.test(document.querySelector('#toast').textContent)", timeout=8000)
        offered = True
    except Exception:
        offered = False
    check("...and the update is offered once the AI is ready", offered, page.inner_text("#toast"))
    browser.close()


# ==================================================================== upgrading an existing install
def section_upgrade(p, dev):
    """A phone that had PlateSight 1.0 installed: its downloaded models must carry over (no 11 MB
    re-download) and "What's new" must appear once."""
    browser = launch(p)
    ctx, page = new_page(browser, dev)
    page.goto(BASE)
    page.wait_for_function(READY, timeout=120000)
    # Rewind this install to the 1.0 layout: models cached under their plain URLs in 'ps-models-v1',
    # no release recorded, and the old service worker gone.
    page.evaluate("""async () => {
        const now = await caches.open('ps-models');
        const old = await caches.open('ps-models-v1');
        for (const req of await now.keys()) {
            const u = new URL(req.url); u.search = '';
            await old.put(u.href, await now.match(req));
        }
        await caches.delete('ps-models');
        localStorage.removeItem('ps_release');
        localStorage.setItem('ps_boot', 'ok');
        for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
    }""")
    downloads = []
    ctx.route("**/*.onnx*", lambda route: (downloads.append(route.request.url), route.continue_()))
    page.reload()
    page.wait_for_function(READY, timeout=120000)
    try:
        page.wait_for_selector("#sheetNew.open", timeout=8000)
        news = True
    except Exception:
        news = False
    caches = page.evaluate("() => caches.keys()")
    boot_shown = page.evaluate("() => !document.querySelector('#boot').hidden")
    check("upgrading from 1.0: downloaded models carry over, no re-download", not downloads and "ps-models-v1" not in caches and "ps-models" in caches and not boot_shown,
          f"downloads={downloads} caches={caches}")
    check("upgrading from 1.0: what's new is shown", news)
    browser.close()


# ==================================================================== damaged runtime copy
def section_fallback(p, dev):
    if not REAL_ORT:
        log("skip fallback (needs REAL_ORT)")
        return
    browser = launch(p)
    ctx, page = new_page(browser, dev)
    ctx.add_cookies([{"name": "ps_badwasm", "value": "1", "url": BASE}])  # the site serves a damaged engine file
    hits = len(cdn_hits)
    page.goto(BASE)
    state = """() => ({ ready: window.__plateSight.state.ready, err: window.__plateSight.state.bootError && window.__plateSight.state.bootError.message,
        info: window.__plateSight.engine.info, errors: window.__plateSight.state.engineErrors, safe: localStorage.getItem('ps_force_safe') })"""
    page.wait_for_function("() => window.__plateSight && (window.__plateSight.state.ready || window.__plateSight.state.bootError)", timeout=180000)
    r = page.evaluate(state)
    i = r["info"] or {}
    used_cdn = [u.rsplit("/", 1)[-1] for u in cdn_hits[hits:]]
    check("damaged engine file on the site: a good copy is swapped in, every core kept",
          r["ready"] and i.get("threads", 0) > 1 and not r["errors"] and not r["safe"] and "ort-wasm-simd-threaded.wasm" in used_cdn,
          f"{json.dumps(r)[:600]} cdn: {used_cdn}")
    ctx.clear_cookies()
    hits = len(cdn_hits)
    page.reload()
    page.wait_for_function(READY, timeout=120000)
    r = page.evaluate(state)
    i = r["info"] or {}
    check("...and the good copy is kept for next time (no download)", i.get("threads", 0) > 1 and not r["errors"] and len(cdn_hits) == hits,
          f"{json.dumps(r)[:300]} cdn: {cdn_hits[hits:]}")
    browser.close()


# ==================================================================== GPU mode
def section_gpu(p, dev):
    if not REAL_ORT:
        log("skip gpu (needs REAL_ORT)")
        return
    browser = launch(p, CAM, ["--enable-unsafe-webgpu"])
    ctx, page = new_page(browser, dev)
    ctx.add_init_script("if (!localStorage.getItem('ps_settings')) localStorage.setItem('ps_settings', JSON.stringify({ gpu: true }));")
    page.goto(BASE)
    page.wait_for_function("() => window.__plateSight && (window.__plateSight.state.ready || window.__plateSight.state.bootError)", timeout=240000)
    info = page.evaluate("() => ({ info: window.__plateSight.engine.info, err: window.__plateSight.state.bootError && window.__plateSight.state.bootError.message, gpu: 'gpu' in navigator })")
    log("gpu boot", json.dumps(info))
    try:
        page.wait_for_selector(".tray-chip", timeout=180000)
        chips = page.eval_on_selector_all(".tray-chip strong", PLATES)
    except Exception:
        chips = []
    texts = photo(page, f"{T}/car_uk.jpg")
    i = info["info"] or {}
    check("GPU mode: runs on WebGPU and reads plates live and in photos", i.get("ep") == "webgpu" and "241-D-12345" in chips and texts == ["AB12 CDE"],
          f"ep={i.get('ep')} gpuError={i.get('gpuError')} live={chips} photo={texts}")
    page.screenshot(path=f"{SHOTS}/31_gpu_photo.png")
    browser.close()


srv_env = dict(os.environ, **({"ORT_DIR": REAL_ORT} if REAL_ORT else {}))
srv = subprocess.Popen([sys.executable, f"{H}/server.py", APP, str(PORT)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=srv_env)
time.sleep(1.0)
try:
    with sync_playwright() as p:
        dev = dict(p.devices["Pixel 7"])
        for name in SECTIONS:
            log(f"\n=== {name}")
            t = time.time()
            try:
                globals()[f"section_{name}"](p, dev)
            except Exception as e:  # a crash in one section shouldn't hide the others
                check(f"section {name} ran to the end", False, repr(e)[:400])
            log(f"=== {name}: {time.time() - t:.0f} s")
finally:
    srv.terminate()

errors = [line for line in logs if line.startswith("[error]") or line.startswith("[pageerror]")]
log("\nconsole errors:", len(errors))
for line in errors[:20]:
    log("  ", line[:300])
log("\nSUMMARY", sum(results.values()), "/", len(results), "passed")
failed = [k for k, v in results.items() if not v]
if failed:
    log("FAILED:", "; ".join(failed))
sys.exit(0 if results and not failed else 1)
