#!/bin/bash
# Runs the app's on-device tests on the CI emulator: installs the debug app and its test APK, runs
# them, and copies off the screenshots and results they save (see app/src/androidTest).
set -u
OUT=ci-out
PKG=io.github.ndev.roadsight
mkdir -p "$OUT/device"
APK=android/app/build/outputs/apk/debug/app-x86_64-debug.apk
TEST_APK=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Every step has a time limit: a stuck emulator (it happens now and then on CI runners) fails the run
# quickly, with its logs, instead of sitting until the job's limit.
timeout 300 adb wait-for-device || { echo "The emulator never came up"; exit 1; }
adb shell getprop ro.build.fingerprint > "$OUT/device/emulator.txt"
adb shell 'cat /proc/cpuinfo | grep "model name" | head -1; nproc' >> "$OUT/device/emulator.txt" 2>&1
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
adb install -r -g "$TEST_APK" >> "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
adb shell pm grant "$PKG" android.permission.CAMERA || true

adb logcat -c || true
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

# The tests take about 6 minutes; after 15 something is stuck.
timeout 900 adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/instrument.txt" 2>&1
STATUS=$?
if [ "$STATUS" = 124 ]; then
  echo "The tests didn't finish in 15 minutes. The last test started:"
  grep "INSTRUMENTATION_STATUS: test=" "$OUT/instrument.txt" | tail -1
  # Where every thread of the app is stuck (app-threads.txt on the android-ci branch).
  timeout 60 adb root > /dev/null 2>&1
  timeout 60 adb wait-for-device
  APP_PID=$(timeout 30 adb shell pidof "$PKG" | tr -d '\r')
  if [ -n "$APP_PID" ]; then
    timeout 120 adb shell debuggerd -j "$APP_PID" > "$OUT/app-threads.txt" 2>&1
    grep -E '^"|^  at |^  - (waiting|locked)' "$OUT/app-threads.txt" | head -200
  fi
fi
kill "$LOGCAT" 2>/dev/null || true

# Screenshots, results.txt, report.pdf: saved by the tests in the app's files (the debug app can be read with run-as).
timeout 120 adb exec-out run-as "$PKG" tar cf - files/ci 2>/dev/null | tar xf - -C "$OUT/device" 2>/dev/null || true
ls -la "$OUT/device" "$OUT/device/files/ci" 2>/dev/null || true
grep -E "RoadSight|AndroidRuntime|FATAL" "$OUT/logcat.txt" > "$OUT/logcat-app.txt" || true

cat "$OUT/instrument.txt" | grep -vE "^INSTRUMENTATION_STATUS: (class|current|id|numtests|stream)=|^INSTRUMENTATION_STATUS_CODE: 1" | tail -80
cat "$OUT/device/files/ci/results.txt" 2>/dev/null || true
# am instrument exits 0 even when tests fail, so look at what it printed.
if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_RESULT: shortMsg" "$OUT/instrument.txt"; then exit 1; fi
grep -qE "^OK \([0-9]+ tests?\)" "$OUT/instrument.txt" || exit 1
