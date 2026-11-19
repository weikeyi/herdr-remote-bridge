# Dependency decisions

- Platform JCA implements P-256 ECDH, HMAC-SHA256, HKDF composition and AES-GCM.
  No new third-party cryptographic primitive implementation is included.
- OkHttp is pinned to 5.5.1 for WSS rather than implementing HTTP Upgrade, frame
  handling and TLS manually. The official 2026-10-22 release includes rejection
  of compromised eMudhra intermediates, important for JVM/AOSP environments.
  This release requires compile SDK 37; the build toolchain is upgraded rather
  than disabling that requirement or downgrading away from the fix.
  Source: https://lysine.dev/okhttp/changelogs/changelog/#version-551
- Android toolchain follows the official supported AGP 9.1.1 / Gradle 9.3.1 /
  JDK 17 / Build Tools 36.0.0 combination for API 37. The application target
  remains 36 and minimum is 28; a higher compile SDK does not raise the minimum.
  Source: https://developer.android.com/build/releases/agp-9-1-0-release-notes
- org.json 20240303 is test-only, to run production JSON protocol tests on a
  host JVM. Production uses Android's platform JSON classes behind a strict
  syntax/duplicate-key/depth gate.
- JUnit 4.13.2 and AndroidX test runner 1.6.2 / ext JUnit 1.2.1 are test-only.
  GitHub Actions are pinned to exact commit hashes; the Gradle distribution and
  wrapper JAR are checksum-verified.

No analytics, ads, crash-reporting, clipboard, camera, storage or login SDK is
included. TLS uses the default verified platform path; there is no custom trust
manager or user-certificate bypass. Dependencies do not establish authority to
read or send user data outside the explicitly selected WSS endpoint.

## Isolated Android runtime lane

The first API 36 Google APIs software-emulator run did not boot and executed no
tests. The next lane uses the official AOSP `system-images;android-29;default;x86_64`
image with supported `-gpu swiftshader`, Vulkan disabled, software acceleration
only, no snapshots and a small display. This does not alter `/dev/kvm` permissions.
If it cannot boot, runtime tests remain unverified rather than being skipped.
Passing on API 29 would not prove API 28 or modern Android/physical-device behavior.
Sources: https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml
and https://developer.android.com/studio/run/emulator-acceleration .

## Offline QR dependency and packaged licenses

ZXing **core 3.5.4** is pinned for offline QR decoding of a user-selected image.
The official artifact was published on 2025-11-11, beyond the two-week waiting
period. No camera integration, MLKit/cloud decoder, login or analytics SDK is
added. Sources: https://github.com/zxing/zxing/releases/tag/zxing-3.5.4 and
https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.4/ .

Encoded input is limited to 8 MiB; PNG/JPEG/WebP source images are limited to
32 MiPixels, then sampled to at most 4 MiPixels before QR decoding. Only selected
content URIs are opened. The app does not upload the image; a system-selected
content provider may fetch the user's selected file despite the local-only
picker hint. Decoded text is still checked by the invitation origin/expiry/scope
validator; importing does not pair automatically. Multiple readable codes fail
closed. Cancellation closes owned IO; bounded library decoding is not promised
to stop instantly inside BitmapFactory or ZXing.

The APK includes `assets/third-party/THIRD_PARTY_NOTICES.txt`, the full Apache 2.0
license, and ZXing's complete upstream LICENSE including bundled notices.
The production resolved runtime contains OkHttp Android 5.5.1, Okio JVM 3.18.1,
Kotlin stdlib 2.2.21, JetBrains annotations 13.0, AndroidX annotation JVM 1.10.0,
startup runtime 1.2.0 and tracing 1.0.0, in addition to ZXing. Their official POM
license declarations were checked; all declare Apache 2.0. The isolated host WSS
harness uses Kotlin 2.1.21 and is not the APK runtime dependency resolution.
