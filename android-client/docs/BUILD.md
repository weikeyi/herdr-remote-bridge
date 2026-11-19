# Building the independent preview

This directory is a standalone Gradle project, not a change to an official Android
application. The production app uses platform Java/Android APIs only. JUnit and
AndroidX runner/ext-junit are pinned **test-only** dependencies.

## Toolchain

- Android Gradle Plugin 8.13.2 and Gradle 8.13.
- JDK 17; compile SDK 36, target SDK 36, minimum SDK 28.
- Android SDK Build Tools 35.0.0.
- JUnit 4.13.2; AndroidX test runner 1.6.2 and ext-junit 1.2.1.

The [official AGP 8.13 compatibility table](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
specifies Gradle 8.13, JDK 17 and Build Tools 35.0.0 and supports API 36.
The wrapper distribution and JAR hashes are checked against the
[official Gradle checksum reference](https://gradle.org/release-checksums/).
Actions are pinned to commit SHAs. Emulator system image/WebView revisions are
provided by Google's SDK repository, not vendored into this project; CI records
native results, not reproducible emulator image byte identity.

Install the Android SDK from its official distribution and accept its SDK licenses.
Set `JAVA_HOME` and `ANDROID_HOME` locally, then run from this directory:

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
sha256sum -c gradle/wrapper/gradle-wrapper.jar.sha256
./gradlew --no-daemon assembleDebug lintDebug testDebugUnitTest assembleDebugAndroidTest
```

On Windows use `gradlew.bat` and an equivalent SHA-256 verification command.
For an emulator or a separately authorized test device:

```sh
./gradlew --no-daemon connectedDebugAndroidTest
```

Instrumentation uses an intercepted synthetic HTTPS page, not a live Bridge
server, user account or invitation. It verifies WebCrypto and persistence within
the installed WebView as well as manifest policy. It is not evidence of real
phone-to-PC pairing. Keep the device's Android System WebView updated.

## CI outputs

The dedicated workflow runs build/lint/unit tests and emulator instrumentation.
The device job depends on the build job and installs that exact APK plus its
same-build test APK after verifying checksums and source identity. Once the
emulator boots it runs adb instrumentation directly, with strict per-test result
validation, rather than downloading Gradle dependencies again. Test input APKs
are a separate one-day artifact, not part of the user download.
APK artifacts contain the debug APK, SHA256SUMS, source/toolchain metadata and the
signing certificate fingerprint. They expire after 14 days; synthetic test reports
after one day. Downloaded debug APKs are previews, not signed production releases.
The debug variant is deliberately non-debuggable; debug signing is not permission
for adb `run-as` or WebView debugging. Instrumentation uses a same-signed test APK;
its compatibility must pass CI before being claimed.
CI debug signing keys may change between runs, so in-place updates are not promised.
No release signing key, server URL, credentials or invitation belongs in build files.

The emulator action uses hardware acceleration when already accessible and its
software fallback otherwise. CI does not change `/dev/kvm` or OS security settings.
A missing/slow emulator is a failed or blocked device check, never an implicit pass.
