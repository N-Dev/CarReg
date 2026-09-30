# RoadSight for Android

PlateSight (the number-plate reader) and TrafficSight (the traffic counter) as one native Android app.
Everything runs on the phone with ONNX Runtime for Android (no browser in between), and nothing is
uploaded.

**Install on a phone:** open <https://github.com/N-Dev/CarReg/releases/latest/download/RoadSight.apk> on
the phone, allow your browser to install unknown apps when Android asks (once), and tap Install. New
versions install over the old one and keep history and settings. Android 8 or later, 64-bit (arm64).

| Tab | What it does |
|---|---|
| **Plates** | Live plate reading: every frame goes through the plate finder and reader, plates are tracked and voted on, and confirmed once the reads agree. Torch, zoom, pause. The photo button (or *Share → RoadSight* from the gallery) reads a photo with the accurate models, three crops per plate and a deep scan. |
| **Traffic** | The traffic counter: set up two lines on the road, then Start counting. Counts cars, vans & trucks, buses, motorbikes, bicycles, people, dogs and horses by direction, with speeds. The screen dims while counting; the tabs are hidden so it can't be stopped by accident. |
| **History** | Plates (search, details, CSV) and traffic counting sessions (charts, tables, CSV and the one-page PDF report for the council). |
| **Settings** | Plate and traffic options, the AI engine (CPU, XNNPACK or NNAPI, threads) and a **speed test** that tries every setup on this phone, checks the answers are still right, and uses the fastest for each model. |

The first version doesn't have PlateSight's video mode or developer tools; the web apps still do.

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
  counted at 40 and 60 km/h. The report is checked line for line against the web app's.
- `app/src/androidTest`: on an emulator in CI: the models through the app's own engine (and which
  accelerators work), every tab, a photo shared to the app, and a counting session from a synthetic road
  through to its results screen and PDF report. Screenshots are saved.

CI builds the app, runs both, and publishes the arm64 APK as a GitHub release. The build log, test
results, emulator screenshots and the test's PDF report (as a picture) of the last run are on the
[`android-ci`](https://github.com/N-Dev/CarReg/tree/android-ci) branch.

### Signing key

Android only installs an update over an app signed with the same key, so every build is signed with
`keystore/roadsight.jks` (password `roadsight-sideload`). It is in the repository so CI can use it, which
means anyone could sign an app that installs over RoadSight on a phone that has it. For a private key:

1. Make a new keystore (`keytool -genkeypair -keystore roadsight.jks -alias roadsight -keyalg RSA -validity 10000`).
2. Add it to the repository's Actions secrets as base64 (`base64 -w0 roadsight.jks`), with its password.
3. Have the workflow decode it into `android/keystore/roadsight.jks` before building, and delete the committed one.

Phones with the old key's app installed then need to uninstall it once (history is lost) before installing the new one.
