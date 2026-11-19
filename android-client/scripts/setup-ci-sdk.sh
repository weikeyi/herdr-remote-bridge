#!/usr/bin/env bash
# Bootstrap a job-local SDK from Google's checksum-pinned CLI distribution.
set -euo pipefail
: "${RUNNER_TEMP:?RUNNER_TEMP must name a writable temporary directory}"
: "${GITHUB_ENV:?GITHUB_ENV must name the Actions environment file}"
: "${GITHUB_PATH:?GITHUB_PATH must name the Actions PATH file}"
: "${JAVA_HOME:?Set up JDK 17 before bootstrapping the SDK}"

sdk="$RUNNER_TEMP/bridge-android-sdk"
work="$(mktemp -d "$RUNNER_TEMP/bridge-sdk-download.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$sdk/cmdline-tools" "$RUNNER_TEMP/bridge-android-user"
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
  https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip \
  --output "$work/cli.zip"
printf '%s  %s\n' 04453066b540409d975c676d781da1477479dde3761310f1a7eb92a1dfb15af7 "$work/cli.zip" | sha256sum --check --strict
unzip -q "$work/cli.zip" -d "$work/extracted"
# A fresh job-local SDK avoids mixing CLI versions with runner preinstalls.
if [[ -e "$sdk/cmdline-tools/20.0" ]]; then
  echo 'Refusing to replace an existing job-local command-line tools directory' >&2
  exit 1
fi
mv "$work/extracted/cmdline-tools" "$sdk/cmdline-tools/20.0"
# emulator-runner prepends cmdline-tools/latest/bin; keep it on the pinned CLI.
ln -s 20.0 "$sdk/cmdline-tools/latest"
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
export ANDROID_USER_HOME="$RUNNER_TEMP/bridge-android-user"
unset ANDROID_PREFS_ROOT
manager="$sdk/cmdline-tools/20.0/bin/sdkmanager"
# Finite input avoids a yes/SIGPIPE failure hiding sdkmanager's real exit status.
printf 'y\n%.0s' {1..100} > "$work/license-answers"
"$manager" --sdk_root="$sdk" --licenses < "$work/license-answers"
"$manager" --sdk_root="$sdk" 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
test -f "$sdk/platforms/android-37.0/android.jar"
test -x "$sdk/build-tools/36.0.0/aapt"
{
  printf 'ANDROID_HOME=%s\n' "$sdk"
  printf 'ANDROID_SDK_ROOT=%s\n' "$sdk"
  printf 'ANDROID_USER_HOME=%s\n' "$ANDROID_USER_HOME"
} >> "$GITHUB_ENV"
{
  printf '%s\n' "$sdk/cmdline-tools/20.0/bin"
  printf '%s\n' "$sdk/platform-tools"
  printf '%s\n' "$sdk/emulator"
} >> "$GITHUB_PATH"
