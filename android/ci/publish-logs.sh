#!/bin/bash
# Publishes this run's build log, test results and emulator screenshots to the android-ci branch,
# so they can be read without signing in to GitHub.
set -u
OUT=ci-out
mkdir -p "$OUT"
CI="$OUT/device/files/ci"
# The council report the on-device test made, as a picture.
if [ -f "$CI/report.pdf" ]; then
  command -v pdftoppm > /dev/null || sudo apt-get install -y -qq poppler-utils > /dev/null 2>&1 || true
  pdftoppm -r 70 -png "$CI/report.pdf" "$CI/report" 2>/dev/null || true
fi
cp -r android/core/build/reports/tests "$OUT/core-tests" 2>/dev/null || true
cp -r android/core/build/test-results "$OUT/core-test-results" 2>/dev/null || true
ls -la android/app/build/outputs/apk/*/ > "$OUT/apks.txt" 2>/dev/null || true
{
  echo "# Android CI run ${GITHUB_RUN_NUMBER:-?}"
  echo
  echo "Commit ${GITHUB_SHA:-?}, $(date -u), job status: ${JOB_STATUS:-?}"
} > "$OUT/README.md"
cd "$OUT" || exit 0
git init -q -b android-ci
git add -A
git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit -q -m "Android CI run ${GITHUB_RUN_NUMBER:-?} (${GITHUB_SHA::7})"
git push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" HEAD:android-ci
