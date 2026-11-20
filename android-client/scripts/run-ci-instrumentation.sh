#!/usr/bin/env bash
# The job downloads both verified APKs from this run’s build job. No Gradle/network here.
set -euo pipefail
report=app/build/reports/androidTests
mkdir -p "$report"
timeout 60s adb wait-for-device
timeout 300s adb install -r app/build/device-inputs/app-debug.apk > "$report/install-app.txt" 2>&1
timeout 300s adb install -r -t app/build/device-inputs/app-debug-androidTest.apk > "$report/install-tests.txt" 2>&1
# -r preserves per-test statuses; pipefail preserves adb/timeout failure.
timeout 300s adb shell am instrument -w -r \
  -e class dev.herdrbridge.nativeclient.AndroidCryptoTest,dev.herdrbridge.nativeclient.DeviceVaultTest,dev.herdrbridge.protocol.ChunkCodecTest,dev.herdrbridge.nativeclient.MainActivitySmokeTest,dev.herdrbridge.nativeclient.ReaderQrUiTest,dev.herdrbridge.nativeclient.QrImageDecoderTest,dev.herdrbridge.nativeclient.TerminalInputUiTest \
  dev.herdrbridge.nativeclient.internal.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$report/instrumentation.txt"
python3 scripts/check-instrumentation.py "$report/instrumentation.txt"

# Screenshot suite is separate: the original 20-test acceptance stays unchanged.
ui_report=app/build/reports/synthetic-ui
mkdir -p "$ui_report"
printf '%s\n' 'API29 emulator; real Activity with synthetic test-only repositories. No real endpoint, credentials or terminal. PNGs may be partial when a test fails; keyboard PNG exists only after observed IME visibility.' > "$ui_report/README.txt"
set +e
timeout 180s adb shell am instrument -w -r \
  -e class dev.herdrbridge.nativeclient.SyntheticUiScreenshotTest \
  dev.herdrbridge.nativeclient.internal.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$ui_report/instrumentation.txt"
ui_status=${PIPESTATUS[0]}
timeout 60s adb pull /sdcard/Android/data/dev.herdrbridge.nativeclient.internal/files/synthetic-ui/. "$ui_report/" > "$ui_report/pull.txt" 2>&1
pull_status=$?
set -e
python3 scripts/check-instrumentation.py "$ui_report/instrumentation.txt" --screenshots
[[ "$ui_status" == 0 && "$pull_status" == 0 ]]
python3 - "$ui_report" <<'PYCHECK'
import pathlib,sys
root=pathlib.Path(sys.argv[1])
for name in ('01-pairing-empty','02-session-home','03-reader-detail','04-controller-composer','05-controller-ime-visible'):
    data=(root/(name+'.png')).read_bytes()
    assert data.startswith(b'\x89PNG\r\n\x1a\n') and len(data)>1000, name
print('PASS five real synthetic-screen PNGs captured; no placeholder images')
PYCHECK
