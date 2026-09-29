"""End-to-end tests of PlateSight in Chromium emulating a Pixel 7, with a fake camera.

Needs: pip install playwright onnxruntime numpy pillow; ffmpeg.
By default the AI runtime is a stand-in that runs the real models with Python onnxruntime (mock-ort.js).
REAL_ORT=/path/to/onnxruntime-web/dist runs the real WebAssembly runtime, served at /ort/ like the
published site (this is what the publish workflow does). Exits non-zero if any check fails.

Sections:
  main      first launch, live scan, photo, video, history, settings, share target, offline
  cars      moving the camera from one car to the next (no mixed-up plates or photos)
  debug     developer easter egg, live debug overlay, read inspector, field test, debug panel,
            benchmark, diagnostics speed summary, accuracy stats, training-set export, quality
            override, history privacy
  slow      slow first launch (no false "hang", no safe mode) and an update arriving mid-download
  upgrade   a phone upgrading from 1.0: downloaded models carry over, "What's new" once
  datasaver Data Saver: Auto never downloads the sharper models, but uses them once on the phone
  counter   TrafficSight (count/): counts, directions and speeds on a synthetic road, results, CSV,
            council report, offline, and living alongside PlateSight
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
TRAFFIC = os.path.join(WORK, "traffic.y4m")
if not os.path.exists(TRAFFIC):
    # TrafficSight's road, 960 x 540, looping every 11 s: car A drives left to right in the near lane at
    # 160 px/s, then car B right to left in the far lane at 240 px/s. With lines at 35% and 65% of the
    # width (288 px) set 20 m apart, that's 40 km/h and 60 km/h.
    ffmpeg("-i", f"{T}/car_ie.jpg", "-vf", "crop=1220:690:140:125,scale=300:-2", f"{WORK}/car_a.png")
    ffmpeg("-i", f"{T}/two_cars.jpg", "-vf", "crop=1190:690:1690:118,scale=220:-2", f"{WORK}/car_b.png")
    ffmpeg("-f", "lavfi", "-i", "color=c=#9aa7b4:s=960x540:r=15:d=11", "-f", "lavfi", "-i", "color=c=#3b3e44:s=960x220",
           "-f", "lavfi", "-i", "color=c=#c9c4b8:s=960x70", "-loop", "1", "-i", f"{WORK}/car_a.png", "-loop", "1", "-i", f"{WORK}/car_b.png",
           "-filter_complex", "[0:v][1:v]overlay=0:250[r];[r][2:v]overlay=0:470[p];[p]drawbox=x=0:y=357:w=960:h=5:color=white@0.8:t=fill[l];"
           "[l][4:v]overlay=x='960-240*(t-5.2)':y=350-h:shortest=1[b];[b][3:v]overlay=x='-300+160*t':y=462-h:shortest=1,format=yuv420p",
           "-t", "11", "-r", "15", TRAFFIC)

PORT = 8765
BASE = f"http://127.0.0.1:{PORT}/"
MOCK = open(f"{H}/mock-ort.js").read()
REAL_ORT = os.environ.get("REAL_ORT")
SECTIONS = [s for s in os.environ.get("SECTIONS", "main,cars,debug,slow,upgrade,datasaver,counter,fallback,gpu").split(",") if s]
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
    # For "Copy diagnostics". Granting for this origin replaces its permissions, so camera is listed again.
    ctx.grant_permissions(["camera", "clipboard-read", "clipboard-write"], origin=BASE.rstrip("/"))
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

    # ---------------------------------------------------------------- copy diagnostics: speed per quality tier, benchmark
    page.click("#debugTabs [data-tab=overview]")
    page.wait_for_selector("#dbgSpeed .dtable td", timeout=20000)
    page.click("[data-dact=copy]")
    page.wait_for_function("() => /copied|copy/i.test(document.querySelector('#toast').textContent)", timeout=10000)
    toast = page.inner_text("#toast")
    try:
        text = page.evaluate("() => navigator.clipboard.readText()")
        d = json.loads(text[text.index("{"):text.index("\n\nLog (last")])
    except Exception as e:
        text, d = repr(e), {}
    sp = d.get("speed") or {}
    tiers = [r for setup in (sp.get("setups") or {}).values() for r in setup.values()]
    bench = ((d.get("benchmarks") or {}).get("models") or {}).get("results") or []
    check("diagnostics: speed per quality tier and the benchmark are copied",
          "copied" in toast and sp.get("frames", 0) > 3 and sp.get("timeline") and any("active" in r or "idle" in r for r in tiers)
          and any(r.get("finder") for r in tiers) and any(b.get("key") == "det384" for b in bench),
          f"{toast!r} speed={json.dumps(sp)[:300]} bench={json.dumps(bench)[:120]} {text[:80]!r}")
    page.locator("#dbgSpeed").screenshot(path=f"{SHOTS}/25b_debug_speed.png")

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
    # The service worker finishes saving the downloads in the background; on a fast machine the AI can
    # be ready a moment before that, so wait for both models to be saved.
    for _ in range(100):
        if page.evaluate("async () => (await (await caches.open('ps-models')).keys()).length") >= 2:
            break
        page.wait_for_timeout(100)
    # Rewind this install to the 1.0 layout: models cached under their plain URLs in 'ps-models-v1',
    # no release recorded, and the old service worker gone.
    copied = page.evaluate("""async () => {
        const now = await caches.open('ps-models');
        const old = await caches.open('ps-models-v1');
        const reqs = await now.keys();
        for (const req of reqs) {
            const u = new URL(req.url); u.search = '';
            await old.put(u.href, await now.match(req));
        }
        await caches.delete('ps-models');
        localStorage.removeItem('ps_release');
        localStorage.setItem('ps_boot', 'ok');
        for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
        return reqs.length;
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
          f"copied={copied} downloads={downloads} caches={caches}")
    check("upgrading from 1.0: what's new is shown", news)
    browser.close()


# ==================================================================== Data Saver
SAVE_DATA = "try { Object.defineProperty(NetworkInformation.prototype, 'saveData', { get: () => true, configurable: true }); } catch (e) {}"
# Every frame counts as quick, so Auto wants a sharper tier as soon as it's allowed to step up. Headless
# Chromium reports critical CPU pressure, which holds the quality, so the phone is made to look cool too.
ROOMY = "() => { const s = window.__plateSight.scan; s.adaptive.budget = 1e6; s.simPressure = 'nominal'; }"
TIER = "() => ({ tier: window.__plateSight.scan.adaptive.tier, det: window.__plateSight.scan.last && window.__plateSight.scan.last.det, loaded: [...window.__plateSight.engine.ready] })"


def section_datasaver(p, dev):
    """Data Saver: Auto never downloads the sharper models itself, but uses them once they're on the phone."""
    browser = launch(p)
    ctx, page = new_page(browser, dev)
    ctx.add_init_script(SAVE_DATA)
    downloads = []
    ctx.route("**/*.onnx*", lambda route: (downloads.append(route.request.url.rsplit("/", 1)[-1]), route.continue_()))
    page.goto(BASE)
    page.wait_for_function(READY, timeout=120000)
    page.wait_for_function("() => window.__plateSight.scan.running", timeout=30000)
    page.evaluate(ROOMY)
    page.wait_for_timeout(9000)
    r = page.evaluate(TIER)
    msgs = page.evaluate("() => window.__plateSight.logEntries().map(e => e.msg)")
    sharper = [u for u in downloads if "detector-640" in u or "ocr-accurate" in u]
    check("Data Saver: Auto doesn't download the sharper models", page.evaluate("() => navigator.connection.saveData") and r["tier"] == "fast"
          and not sharper and any("Data Saver is on" in m for m in msgs), f"{json.dumps(r)} downloads={downloads}")
    # The sharper models reach the phone another way (Photo mode, Download all models); then the app is reopened.
    page.evaluate("() => Promise.all(['det640', 'ocrAcc'].map(k => window.__plateSight.engine.load(k)))")
    for _ in range(100):
        if page.evaluate("async () => (await (await caches.open('ps-models')).keys()).length") >= 4:
            break
        page.wait_for_timeout(100)
    downloads.clear()
    page.reload()
    page.wait_for_function(READY, timeout=120000)
    page.wait_for_function("() => window.__plateSight.scan.running", timeout=30000)
    page.evaluate(ROOMY)
    try:
        page.wait_for_function("() => { const s = window.__plateSight.scan; return s.adaptive.tier === 'sharp' && s.last && s.last.det === 'det640'; }", timeout=120000)
        ok = True
    except Exception:
        ok = False
    r = page.evaluate(TIER)
    msgs = page.evaluate("() => window.__plateSight.logEntries().map(e => e.msg)")
    check("Data Saver: Auto steps up to models already on the phone, without downloading", ok and not downloads and any("already on the phone" in m for m in msgs),
          f"{json.dumps(r)} downloads={downloads}")
    browser.close()


# ==================================================================== TrafficSight (count/)
TC_READY = "() => window.__trafficSight && window.__trafficSight.state.ready && window.__trafficSight.state.camera && window.__trafficSight.state.frames > 2"


def section_counter(p, dev):
    """TrafficSight on a synthetic road: opened on a phone that already has PlateSight, counts both cars
    with their directions and speeds, then results, CSV, the council report and offline use."""
    browser = launch(p, TRAFFIC)
    land = dict(dev, viewport={"width": 915, "height": 412}, screen={"width": 915, "height": 412})
    # A phone without PlateSight: TrafficSight's own service worker must serve the AI runtime (and the
    # worker threads it starts) with the isolation headers, or the AI never starts.
    ctx0, page0 = new_page(browser, land)
    page0.goto(BASE + "count/")
    try:
        page0.wait_for_function(TC_READY, timeout=120000)
        alone = page0.evaluate("() => ({ threads: __trafficSight.engine.info.threads, fps: __trafficSight.state.fpsT.length })")
    except Exception:
        alone = None
    check("TrafficSight starts on a phone without PlateSight, multi-core", alone and alone["threads"] > 1, json.dumps(alone))
    ctx0.close()
    ctx, page = new_page(browser, land)
    # PlateSight first: its service worker must leave count/ to TrafficSight's own.
    page.goto(BASE)
    page.wait_for_function(READY, timeout=180000)
    page.goto(BASE + "count/")
    page.wait_for_function(TC_READY, timeout=240000)
    info = page.evaluate("""() => ({ title: document.title, model: __trafficSight.state.model, bench: __trafficSight.state.benchMs,
        threads: __trafficSight.engine.info && __trafficSight.engine.info.threads, coi: self.crossOriginIsolated,
        sw: navigator.serviceWorker.controller && navigator.serviceWorker.controller.scriptURL })""")
    check("TrafficSight opens from its own address on a phone with PlateSight, multi-core, with its own service worker",
          info["title"] == "TrafficSight" and info["coi"] and info["threads"] > 1 and info["sw"].endswith("/count/sw.js"), json.dumps(info))

    page.click("#btnSetup")
    page.wait_for_selector("#setup:not([hidden])", timeout=5000)
    page.wait_for_timeout(400)
    page.screenshot(path=f"{SHOTS}/39_counter_setup.png")
    page.evaluate("() => __trafficSight.closeEditor(false)")
    page.evaluate("""() => __trafficSight.applySetup({ lines: { a: [0.35, 0.35, 0.35, 0.95], b: [0.65, 0.35, 0.65, 0.95] }, distanceM: 20,
        limit: 50, dirNames: ['Eastbound', 'Westbound'], site: 'Test Road', setupDone: true })""")
    page.evaluate("() => __trafficSight.startCounting()")
    # The screen dims while counting and wakes on a tap.
    page.evaluate("() => { __trafficSight.settings.dimAfter = 1; }")
    try:
        page.wait_for_selector("#dim:not([hidden])", timeout=8000)
        dimmed = True
    except Exception:
        dimmed = False
    page.screenshot(path=f"{SHOTS}/40b_counter_dim.png")
    page.click("#dim")
    page.evaluate("() => { __trafficSight.settings.dimAfter = 0; }")
    page.wait_for_timeout(300)
    check("counting: the screen dims to save battery and wakes on a tap", dimmed and page.evaluate("() => document.querySelector('#dim').hidden"))
    try:
        page.wait_for_function("""() => { const c = __trafficSight.state.counted;
            return c.some(r => r.dir === 1 && r.speed) && c.some(r => r.dir === 2 && r.speed); }""", timeout=150000)
    except Exception:
        pass
    page.screenshot(path=f"{SHOTS}/40_counter_live.png")
    # The camera stops and starts again (the app went to the background): speeds must still be right.
    page.evaluate("() => { __trafficSight.stopCamera(); __trafficSight.state.counted.length = 0; }")
    page.wait_for_timeout(800)
    page.evaluate("() => __trafficSight.startCamera()")
    try:
        page.wait_for_function("""() => { const c = __trafficSight.state.counted;
            return c.some(r => r.dir === 1 && r.speed) && c.some(r => r.dir === 2 && r.speed); }""", timeout=150000)
    except Exception:
        pass
    recs = page.evaluate("() => __trafficSight.state.counted")
    first = {d: next((r for r in recs if r["dir"] == d and r["speed"]), None) for d in (1, 2)}
    east, west = first[1], first[2]
    vehicles = all(r["kind"] in ("car", "truck", "bus") for r in recs)
    per_dir = [sum(1 for r in recs if r["dir"] == d) for d in (1, 2)]
    check("counter: each car counted once, in its direction, as a vehicle", east and west and vehicles and max(per_dir) <= 2, f"{per_dir} {json.dumps(recs)[:500]}")
    check("counter: speeds between the lines (40 and 60 km/h, within 15%)",
          east and west and abs(east["speed"] - 40) <= 6 and abs(west["speed"] - 60) <= 9,
          f"east={east and east['speed']} west={west and west['speed']} fps={page.evaluate('() => __trafficSight.state.fpsT.length / 5')}")

    page.evaluate("() => __trafficSight.stopCounting()")
    page.wait_for_timeout(500)
    page.evaluate("() => __trafficSight.showResults(__trafficSight.state.sessionId)")
    page.wait_for_selector("#results .tiles .tile", timeout=15000)
    page.wait_for_timeout(500)
    res = page.evaluate("""() => {
        const ink = (id) => { const c = document.getElementById(id); const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
            let n = 0; for (let i = 3; i < d.length; i += 4) if (d[i]) n++; return n; };
        return { tiles: document.querySelector('#results .tiles').innerText, hours: ink('chHours'), speeds: ink('chSpeeds'),
                 rows: document.querySelectorAll('#results .numbers tbody tr').length }; }""")
    check("results: figures, hourly chart and speed chart", "Motor vehicles" in res["tiles"] and res["hours"] > 500 and res["speeds"] > 500 and res["rows"] > 3,
          json.dumps(res)[:300])
    page.set_viewport_size({"width": 915, "height": 2400})  # the whole results page in one picture
    page.wait_for_timeout(600)
    page.screenshot(path=f"{SHOTS}/41_counter_results.png")
    page.set_viewport_size({"width": 915, "height": 412})
    with page.expect_download(timeout=20000) as dl:
        page.click("#btnCSV")
    csv = open(dl.value.path(), encoding="utf-8").read()
    with page.expect_download(timeout=20000) as dl2:
        page.click("#btnReport")
    pdf = open(dl2.value.path(), "rb").read()
    check("results: CSV export and the council report (PDF)",
          csv.startswith("time,type,direction,speed_kmh,length_m,site") and "Eastbound" in csv and "Westbound" in csv and "Test Road" in csv
          and time.strftime("%Y-%m-%d") in csv and "1970-" not in csv
          and pdf.startswith(b"%PDF-1.4") and b"Test Road" in pdf and b"Eastbound" in pdf and pdf.rstrip().endswith(b"%%EOF"),
          f"{csv[:160]!r} pdf={len(pdf)} bytes")

    page.click("#btnSettings")
    page.wait_for_selector("#sheetSettings.open", timeout=5000)
    page.wait_for_timeout(400)
    page.screenshot(path=f"{SHOTS}/42_counter_settings.png")
    page.click("#backdrop", position={"x": 20, "y": 20})
    # Held upright: the picture on top, the counts underneath.
    page.click("#btnResults")
    page.set_viewport_size({"width": 412, "height": 915})
    page.wait_for_timeout(1500)
    page.screenshot(path=f"{SHOTS}/43_counter_portrait.png")
    page.set_viewport_size({"width": 915, "height": 412})

    # Offline: everything it needs is on the phone.
    ctx.set_offline(True)
    page.reload()
    try:
        page.wait_for_function(TC_READY, timeout=120000)
        ok = True
    except Exception:
        ok = False
    ctx.set_offline(False)
    check("TrafficSight works offline after the first start", ok and page.evaluate("() => document.title") == "TrafficSight")
    # PlateSight is still PlateSight.
    page.goto(BASE)
    page.wait_for_function(READY, timeout=120000)
    check("PlateSight is unaffected", page.evaluate("() => document.title") == "PlateSight")
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
