#!/bin/bash
# Runs the app's on-device tests on the CI emulator: installs the debug app and its test APK, runs
# them, and copies off the screenshots and results they save (see app/src/androidTest).
set -u
OUT=ci-out
PKG=io.github.ndev.roadsight
mkdir -p "$OUT/device"
APK=android/app/build/outputs/apk/debug/app-x86_64-debug.apk
TEST_APK=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb wait-for-device
adb shell getprop ro.build.fingerprint > "$OUT/device/emulator.txt"
adb shell 'cat /proc/cpuinfo | grep "model name" | head -1; nproc' >> "$OUT/device/emulator.txt" 2>&1
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
adb install -r -g "$TEST_APK" >> "$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 1; }
adb shell pm grant "$PKG" android.permission.CAMERA || true

adb logcat -c || true
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/instrument.txt" 2>&1
kill "$LOGCAT" 2>/dev/null || true

# Screenshots, results.txt, report.pdf: saved by the tests in the app's files (the debug app can be read with run-as).
adb exec-out run-as "$PKG" tar cf - files/ci 2>/dev/null | tar xf - -C "$OUT/device" 2>/dev/null || true
ls -la "$OUT/device" "$OUT/device/files/ci" 2>/dev/null || true
grep -E "RoadSight|AndroidRuntime|FATAL" "$OUT/logcat.txt" > "$OUT/logcat-app.txt" || true

cat "$OUT/instrument.txt" | grep -vE "^INSTRUMENTATION_STATUS: (class|current|id|numtests|stream)=|^INSTRUMENTATION_STATUS_CODE: 1" | tail -80
cat "$OUT/device/files/ci/results.txt" 2>/dev/null || true
# am instrument exits 0 even when tests fail, so look at what it printed.
if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_RESULT: shortMsg" "$OUT/instrument.txt"; then exit 1; fi
grep -qE "^OK \([0-9]+ tests?\)" "$OUT/instrument.txt" || exit 1
