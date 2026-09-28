# PlateSight for Android

A number-plate reader that runs **entirely on your phone** in Chrome, installed like an app. It has:
- a live camera scanner
- photo and video modes
- a searchable history with CSV export
- "Share → PlateSight" from your Gallery

Nothing you scan is uploaded.

It knows Irish plates well: it corrects misreads using the format (e.g. `24lD12345` → `241-D-12345`) and decodes the year, half-year and county (Dublin · Baile Átha Cliath). UK and Northern Ireland plates are decoded too. Plates from about 65 other countries are read and labelled with their country.

**Open it on your phone:** <https://n-dev.github.io/CarReg/> (in Chrome → ⋮ → Install app)

## Put it on your phone (≈5 minutes, free)

The app is a folder of static files. It must be served over **https** so Chrome allows the camera and installation. GitHub Pages is the easiest permanent option:

1. Go to github.com and create a new **public** repository, e.g. `platesight`.
2. Click **Add file → Upload files**. Drag in *everything inside this folder* (the files and the `js`, `models`, `icons` folders), then **Commit**.
3. Open **Settings → Pages**. Under *Build and deployment*, choose **Deploy from a branch**, select `main` and `/ (root)`, and press **Save**.
4. After about a minute, open `https://YOUR-USERNAME.github.io/platesight/` in **Chrome on your phone**.
5. The first launch downloads the AI (about 15 MB, once). Then tap **⋮ → Install app** (or **Add to Home screen**).

From then on it opens from your home screen, full screen, and works offline.

**Quicker test:** drag this folder onto <https://app.netlify.com/drop> to get an instant https link (it's temporary unless you create a free account).

**Try it on your PC first:** run `python -m http.server 8000` in this folder, then open <http://localhost:8000> in desktop Chrome (webcam works on localhost).

## Using it

| Tab | What it does |
|---|---|
| **Scan** | Live camera. Each car is tracked and read over several frames; a plate is confirmed once the reads agree (you'll feel a buzz). Has torch and zoom (if your phone supports them). Scanning pauses when you leave the app. |
| **Photo** | Take or pick a photo. Uses the larger, more accurate models (downloaded on first use, about 13 MB). Reads each plate from 3 slightly different crops and votes. If nothing is found, it does a zoomed "deep scan". |
| **Video** | Record or pick a video. Plates are tracked through the clip and listed with the time they appear; tap **▶ at 0:12** to jump there. On slower phones playback slows down automatically so enough frames get analysed. |
| **History** | Everything confirmed, grouped by day, with search, details and CSV export (saved or shared). Stored only on this phone. |

Once installed, share a photo or video from Gallery → **PlateSight** to read it directly.

## How it works

```
camera / photo / video frame
  → YOLOv9-t plate detector (ONNX, 384 px for live, 640 px for photos)
  → crop each plate from the full-resolution frame
  → fast-plate-ocr (reads characters + predicts the country)
  → tracker + multi-frame vote → country format check (IE / UK / NI / generic)
```

- Inference runs in a Web Worker with **ONNX Runtime Web** (WebAssembly, multi-threaded), so the UI stays smooth. The optional GPU (WebGPU) setting is marked beta.
- `sw.js` caches the app, models and runtime for offline use, and adds the cross-origin-isolation headers needed for multi-threading on hosts like GitHub Pages. It also receives shared files.
- If the fast engine ever fails to start on a device, the app restarts itself in single-core **safe mode**; you can reset this in Settings.

## Files

```
index.html, app.css           UI
js/main.js                    screens and interaction
js/engine.js                  service-worker setup, worker RPC, model loading
js/engine-worker.js           ONNX Runtime + pre/post-processing
js/tracker.js                 tracking and voting
js/formats.js                 IE/UK/NI validation, decoding, flags
js/store.js                   settings and IndexedDB history
sw.js, manifest.webmanifest   offline, install, share target
models/                       detector (384/640) and OCR (fast/accurate) ONNX models
tools/vendor_ort.py           optional: copy ONNX Runtime into ./ort so no CDN is needed
.github/workflows/publish.yml on every push to main: publishes the site to gh-pages with its own copy of ONNX Runtime Web (ort/)
tests/                        unit tests (node --test tests/*.test.mjs) and a browser end-to-end test
```

**Changing the app:** edit on `main` and bump `VERSION` in `sw.js`. The GitHub Action republishes the site within a minute or two; installed phones then pick up the update and show a "Reload" prompt.

## Troubleshooting

- **Camera blocked:** in Chrome, tap the icon left of the address bar → Permissions → Camera → Allow. For the installed app: long-press the icon → App info → Permissions.
- **"Couldn't start the AI" on first launch:** the first launch needs internet. After that it works offline.
- **Slow:** keep sensitivity on Medium and get closer or use zoom. Try the GPU setting on recent phones.

## Privacy

All processing happens on the device. Number plates are personal data under GDPR, so scan where you have a good reason to and don't keep or share plates of people you don't know. Turn off *Save plates to history* in Settings if you don't need it.

## Credits

- Plate detector: [open-image-models](https://github.com/ankandrew/open-image-models) (MIT).
- OCR: [fast-plate-ocr](https://github.com/ankandrew/fast-plate-ocr) (MIT).
- Runtime: [ONNX Runtime Web](https://onnxruntime.ai) (MIT).
