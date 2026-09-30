# RoadSight for Android

PlateSight (the number-plate reader) and TrafficSight (the traffic counter) as one native Android app.
Everything runs on the phone with ONNX Runtime for Android (no browser in between), and nothing is
uploaded.

**Install on a phone:** open <https://github.com/N-Dev/CarReg/releases/latest/download/RoadSight.apk> on
the phone, allow your browser to install unknown apps when Android asks (once), and tap Install. New
versions install over the old one and keep history and settings. Android 8 or later, 64-bit (arm64).

| Tab | What it does |
|---|---|
| **Plates** | Live plate reading: every frame goes through the plate finder and reader, plates are tracked and voted on, and confirmed once the reads agree. Torch, pinch or tap to zoom (down to the ultra-wide lens if the phone has one), pause. The photo button (or *Share → RoadSight* from the gallery) reads a photo with the accurate models, three crops per plate and a deep scan. The ⋮ menu: keep watching with the screen off, read the plates in a video, the watchlist, and which way round the app turns. |
| **Traffic** | The traffic counter: set up two lines on the road (across the picture, or for a road running away from you), then Start counting. Counts cars, vans & trucks, buses, motorbikes, bicycles, people, dogs and horses by direction, with speeds. Counting carries on with the screen off or another app open (a notification with a Stop button shows meanwhile). The ⋮ menu also counts the traffic in a video. |
| **History** | Plates (search, details, CSV) and traffic counting sessions (charts, tables, CSV and the one-page PDF report for the council). Several sessions (days at one site, say) can go into one report: each day, an average day hour by hour, and the counts and speeds over them all. |
| **Settings** | Plate and traffic options, the **watchlist**, which way round the app is, the AI engine (CPU, XNNPACK or NNAPI, threads) and a **speed test** (offered at first launch) that tries every setup on this phone, checks the answers are still right, and uses the fastest for each model. Also **back up and restore** (one file with everything), **debug mode**, and a daily **update check**. |

### Watchlist and my cars

Add plates to watch for (from the Plates menu, Settings, or any plate's details): when one is read, live,
with the screen off, in a photo or in a video, the phone buzzes three times and shows a notification with
the plate's photo (at most once every 10 minutes for the same plate). Plates on the ignore list (your own
cars) are never saved to history or shown. Both lists stay on the phone.

### Videos

*Share → RoadSight* from the gallery, or the ⋮ menu on either camera tab. The phone's own decoder reads the
video (turning phone videos upright) and the same AI as the camera looks at it: about 15 frames a second
of video for traffic (set the lines on a frame first; the session is dated from when the video was
recorded) and 10 for plates, with the sharper plate models. Keep the screen open while it runs.

### Debug mode

Settings → Developer. The plate and traffic screens then show everything the AI found in each frame
(dashed boxes with scores and readings), each tracked plate or road user's number, where road users are
heading, and a readout: camera and analysed frame rates, the time for each step with a graph, the quality
tier and why, how each model is running, and the phone's heat, battery and memory. The **debug log** lists
what the app did (models loaded, accelerators, quality changes, plates confirmed, road users counted,
background running, errors) with **Copy** and **Share** (with the phone's details) for bug reports.

## How it's built

```
android/
  core/   plain Kotlin, no Android: plate formats, voting and tracking, adaptive quality, the traffic
          counter, statistics, the council report (PDF), and the pre- and post-processing around the
          AI models. A port of the web apps' JavaScript, unit-tested on the JVM with the real models.
  app/    the Android app: camera (CameraX), ONNX Runtime, screens (Jetpack Compose), storage (SQLite).
  ci/     scripts for the GitHub Actions workflow (.github/workflows/android.yml)
```

The models aren't copied into `android/`: the build takes them from `models/` and `count/models/` (a
Gradle task puts them in the APK's assets), so the web apps and the app always use the same files.

Build it yourself with JDK 17 and the Android SDK: `cd android && ./gradlew :core:test :app:assembleRelease`
(the APKs are in `app/build/outputs/apk/`).

### Tests

- `core/src/test`: the web apps' unit tests ported to Kotlin (formats, voting, tracking, counting,
  statistics, the PDF report), and the real models on the test photos: both plates in `two_cars.jpg`,
  a portrait frame, vehicles found by both traffic models, and a synthetic road where two cars must be
  counted at 40 and 60 km/h. The report is checked line for line against the web app's. Also video frames
  (YUV to pixels, shrinking and turning upright) and figures and reports across several days.
- `app/src/androidTest`: on an emulator in CI: the models through the app's own engine (and which
  accelerators work), every tab, zoom and landscape, a road running away, counting in the background, a
  photo shared to the app, a counting session from a synthetic road through to its results and PDF report,
  the watchlist (notification and banner) and ignore list, debug mode, backup and restore, the speed test
  offer, and three short videos (`android/ci/make-test-videos.py` makes them): frames upright, traffic
  counted with speeds, plates read, and the video screens. Screenshots are saved.

CI builds the app, runs both, and publishes the arm64 APK as a GitHub release (from `main`; `next` only
builds and tests). The build log, test results, emulator screenshots and the test's PDF report (as a
picture) of the last run are on the [`android-ci`](https://github.com/N-Dev/CarReg/tree/android-ci) branch.

### Signing key

Android only installs an update over an app signed with the same key. By default every build is signed
with `keystore/roadsight.jks` (password `roadsight-sideload`), which is in the repository so CI can use it;
that means anyone could sign an app that installs over RoadSight on a phone that has it. The workflow
switches to a private key as soon as one is in the repository's secrets:

1. Make a keystore: `keytool -genkeypair -keystore roadsight-private.jks -alias roadsight -keyalg RSA -keysize 4096 -validity 10000`.
2. In GitHub: Settings → Secrets and variables → Actions → New repository secret:
   `ROADSIGHT_KEYSTORE_BASE64` (the output of `base64 -w0 roadsight-private.jks`) and
   `ROADSIGHT_KEYSTORE_PASSWORD`; also `ROADSIGHT_KEY_ALIAS` and `ROADSIGHT_KEY_PASSWORD` if they differ
   from `roadsight` and the keystore password.
3. Keep the keystore file somewhere safe: without it, no update can ever install over that build again.

Phones with the old key's build then have to reinstall once: in the app, Settings → Backup → **Back up**,
uninstall RoadSight, install the new APK, then Settings → Backup → **Restore**.

Google's developer verification for apps installed from outside the Play Store applies in Brazil,
Indonesia, Singapore and Thailand from 30 September 2026 and on certified Android phones everywhere from
2027: apps from unregistered developers then need extra steps to install. Registering is done by the
owner in the Android Developer Console with their Google account (the free limited-distribution account
covers up to 20 phones), and registers the private key above, so set that up first.
