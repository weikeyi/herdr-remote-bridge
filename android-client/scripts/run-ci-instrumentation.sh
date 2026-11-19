#!/usr/bin/env bash
# The job downloads both verified APKs from this run’s build job. No Gradle/network here.
set -euo pipefail
report=app/build/reports/androidTests
mkdir -p "$report"
timeout 60s adb wait-for-device
timeout 120s adb install -r app/build/device-inputs/app-debug.apk > "$report/install-app.txt" 2>&1
timeout 120s adb install -r -t app/build/device-inputs/app-debug-androidTest.apk > "$report/install-tests.txt" 2>&1
# -r preserves per-test statuses; pipefail preserves adb/timeout failure.
timeout 300s adb shell am instrument -w -r \
  -e class dev.herdrbridge.nativeclient.AndroidCryptoTest,dev.herdrbridge.nativeclient.DeviceVaultTest,dev.herdrbridge.protocol.ChunkCodecTest,dev.herdrbridge.nativeclient.MainActivitySmokeTest,dev.herdrbridge.nativeclient.ReaderQrUiTest,dev.herdrbridge.nativeclient.QrImageDecoderTest \
  dev.herdrbridge.nativeclient.internal.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$report/instrumentation.txt"
python3 scripts/check-instrumentation.py "$report/instrumentation.txt"
