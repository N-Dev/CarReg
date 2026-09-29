# PlateSight for Android

A number-plate reader that runs **entirely on your phone** in Chrome, installed like an app. It has:
- a live camera scanner that adapts to your phone (sharper AI when there's headroom, lighter when it's busy or warm)
- photo and video modes
- a searchable history with CSV export, which deletes old plates automatically
- "Share → PlateSight" from your Gallery
- a hidden developer mode with a live debug overlay, diagnostics, a read inspector and field-test tools

Nothing you scan is uploaded.

It knows Irish plates well: it corrects misreads using the format (e.g. `24lD12345` → `241-D-12345`) and decodes the year, half-year and county (Dublin · Baile Átha Cliath). UK and Northern Ireland plates are decoded too. Plates from about 65 other countries are read and labelled with their country.

**Open it on your phone:** <https://n-dev.github.io/CarReg/> (in Chrome → ⋮ → Install app)

## Using it

| Tab | What it does |
|---|---|
| **Scan** | Live camera. Each car is tracked and read over several frames; a plate is confirmed once the reads agree (you'll feel a buzz). Has torch and zoom (if your phone supports them). Scanning pauses when you leave the app. |
| **Photo** | Take or pick a photo. Uses the larger, more accurate models (13 MB more, downloaded the first time). Reads each plate from 3 slightly different crops and votes. If nothing is found, it does a zoomed "deep scan". |
| **Video** | Record or pick a video. Plates are tracked through the clip and listed with the time they appear; tap **▶ at 0:12** to jump there. On slower phones playback slows down automatically so enough frames get analysed. |
| **History** | Everything confirmed, grouped by day, with search, details and CSV export. Stored only on this phone and deleted after 30 days (7 days, 1 year or never in Settings). You can also choose not to keep photos. |

The first launch downloads the AI (about 22 MB, once); after that PlateSight works offline.

### Live quality (Settings → Live scanning → Quality)

| | Plate finder | Plate reader |
|---|---|---|
| **Fast** | 384 px | fast |
| **Balanced** | 384 px | accurate for new plates, fast once confirmed |
| **Sharp** | 640 px (smaller, further plates) | accurate for new plates, fast once confirmed |

**Auto** (the default) starts on Fast and steps up after a few seconds of headroom, fetching the extra models in the background (not on Data Saver). It aims for 10 analysed frames a second: it steps down within about a second if frames get slower than that (as they do when a phone heats up and throttles) or if Chrome reports critical CPU load, and waits longer each time before retrying a tier that proved too slow. While no plate is in view it analyses 4 frames a second instead of 15.

## Developer mode

Tap the **PlateSight logo seven times** (like Android's developer options). You get:

- **Live overlay:** the area the plate finder analyses, every raw detection with its score, and each tracked plate's id, number of reads and agreement.
- **Speed graph** in the corner: one bar per frame, coloured by quality tier, red when over the frame-time budget.
- **Read inspector** in every plate's details: the exact 128 × 64 image the plate reader saw, how sure it was of each character and what it considered instead, its country guess, and how the frames voted.
- **Field test:** mark any reading *Right* or *Wrong* (and type the real plate).
- **Debug panel** (bug icon, top bar):
  - *Overview:* phone, engine, live speed and storage diagnostics, with **Copy diagnostics** to send when something goes wrong.
  - *Log:* everything the app did (start-up, downloads, tier changes, confirmations, errors), filterable.
  - *Tools:* model benchmark, a CPU-thread benchmark that finds the fastest thread count, overrides (quality, threads, detection and confirm thresholds, idle mode, raw boxes), "simulate a hot phone", restart the engine, download all models.
  - *Accuracy:* your field-test results (exact and per-character accuracy, common mix-ups, recent misses), a confidence-threshold chart that suggests a threshold once you've tested 20 plates, and **Export .zip** of a training set.

### Improving the plate reader with your own plates

1. Turn on developer mode and scan normally. Open plates and mark them right or wrong. A few hundred is a sensible minimum, across day, night, rain and angles.
2. Debug panel → Accuracy → **Export .zip**. It contains `train/` and `val/` in the dataset format of [fast-plate-ocr](https://github.com/ankandrew/fast-plate-ocr) (the library behind the plate readers), `predictions.csv`, and a README with the fine-tuning and ONNX export commands.
3. Replace `models/plate-ocr-accurate.onnx` with the retrained model and update its `bytes` and `rev` in `js/config.js` (the unit tests print the right values). Push, and phones fetch the new model automatically.

## How it works

```
camera / photo / video frame
  → YOLOv9-t plate finder (ONNX, 384 or 640 px)
  → crop each plate from the full-resolution frame
  → fast-plate-ocr plate reader (reads characters + predicts the country)
  → tracker + multi-frame vote → country format check (IE / UK / NI / generic)
```

- Inference runs in a Web Worker with **ONNX Runtime Web** (WebAssembly, multi-threaded), so the UI stays smooth. The GPU setting uses WebGPU and is marked beta.
- `sw.js` caches the app, models and runtime for offline use, and adds the cross-origin-isolation headers needed for multi-threading on hosts like GitHub Pages. Downloads stream through it, so progress shows from the first byte. It also receives shared files.
- Start-up tries the fastest setup first and falls back without reloading. It only falls back to single-core **safe mode** if the engine actually hangs (a slow download never counts), and safe mode is remembered only until the next update.

## Files

```
index.html, app.css           UI
js/config.js                  versions, runtime files and model files: the single source of truth
js/main.js                    wires the screens together and starts the AI
js/ctx.js                     shared state, settings, events, debug log, helpers
js/boot.js                    AI engine start-up, progress, fallbacks and safe mode
js/scan.js                    live scanning     js/adaptive.js   quality tiers and idle mode
js/photo.js, js/video.js      photo and video modes
js/history-view.js            history, search, export, retention
js/detail.js                  plate details, read inspector, field test
js/settings-view.js           settings and "What's new"
js/debug.js                   debug panel      js/fieldtest.js  accuracy, thresholds, dataset export
js/board.js, cards.js,        confirmed plates per session, result cards,
  overlay.js                  boxes, debug layers and the speed graph
js/engine.js                  service-worker setup, worker RPC, model loading
js/engine-worker.js           ONNX Runtime + pre/post-processing
js/tracker.js                 tracking and voting
js/formats.js                 IE/UK/NI validation, decoding, flags
js/store.js                   settings, IndexedDB history and field-test samples
js/zip.js                     tiny ZIP writer for the dataset export
sw.js, manifest.webmanifest   offline, install, share target
models/                       plate finder (384/640) and plate reader (fast/accurate) ONNX models
tools/check_runtime.mjs       checks an ONNX Runtime Web copy against js/config.js
tools/vendor_ort.py           optional: copy ONNX Runtime into ./ort so no CDN is needed
tests/                        unit tests and browser end-to-end tests
.github/workflows/publish.yml tests every push; publishes only if they all pass
```

## Changing the app

Edit on `main` and push. The GitHub Action then:

1. runs the unit tests (`node --test tests/*.test.mjs`): they check that every model file matches its size and revision in `js/config.js`, that the service worker caches every app file, and the logic behind tracking, quality tiers, retention, field testing and the ZIP export;
2. runs the end-to-end tests in Chromium emulating a Pixel 7, with a fake camera and the real ONNX Runtime Web: first launch, live scan, photo, video, history, share target, offline use, moving between cars, developer mode, field testing and export, a slow first launch, an update arriving mid-download, a damaged copy of the AI engine, and GPU mode on WebGPU. If they fail, it retries once, then saves logs and screenshots to the `ci-logs` branch;
3. only if everything passes, publishes to GitHub Pages with the build id stamped into `js/config.js` and `sw.js`. Installed phones pick the update up and offer a reload (never in the middle of a download).

Run the browser tests yourself with `pip install playwright onnxruntime numpy pillow`, `python -m playwright install chromium`, then `python tests/e2e/run_e2e.py` (add `REAL_ORT=<onnxruntime-web dist folder>` for the real runtime; `SECTIONS=debug,slow` to run some).

There are no version numbers to bump by hand. To change the ONNX Runtime version, edit it in `js/config.js` with the new file sizes (`node tools/check_runtime.mjs <dist folder>` tells you if they're wrong).

## Troubleshooting

- **Camera blocked:** in Chrome, tap the icon left of the address bar → Permissions → Camera → Allow. For the installed app: long-press the icon → App info → Permissions.
- **"Couldn't start the AI" on first launch:** the first launch needs internet. After that it works offline.
- **Slow or hot:** leave Quality on Auto, or choose Fast. Keep sensitivity on Medium and get closer or use zoom.
- **Anything odd:** turn on developer mode, then Debug panel → Overview → **Copy diagnostics**, and paste it into your bug report.

## Privacy

All processing happens on the device. Number plates are personal data under GDPR, so scan where you have a good reason to and don't keep or share plates of people you don't know. History deletes itself after 30 days by default; you can shorten that, keep only the text without photos, or turn history off in Settings. Field-test samples (developer mode) stay on the phone until you export or clear them.

## Credits

- Plate finder: [open-image-models](https://github.com/ankandrew/open-image-models) (MIT).
- Plate reader: [fast-plate-ocr](https://github.com/ankandrew/fast-plate-ocr) (MIT).
- Runtime: [ONNX Runtime Web](https://onnxruntime.ai) (MIT).
