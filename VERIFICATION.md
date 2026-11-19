# Native milestone verification

Status: implementation in progress, not a device-accepted release.

## Passed in the isolated development workspace

- Original Go v2 golden-vector test.
- Original TypeScript E2EE/transport suite: 83 checks.
- Independent Java E2EE fixture and negative checks: 60.
- Java fixture extraction exactly matches the public upstream synthetic vector.
- Gateway HMAC and chunk fixtures exported from the real Go/TypeScript source.
- Java chunk codec JUnit: 5 tests.
- Native application protocol parser JUnit: 9 tests.
- Randomized original Go ↔ Java process-pipe trace: invitation handshake,
  encrypted client/server finish, Reader credential issuance, encrypted agents
  and read_pane content including UTF-8, then fresh credential reconnect.
  The Go cryptographic source is copied unchanged and byte-compared before use.
- CI instrumentation-result checker: 6 test groups, rejects skipped/partial
  or mismatched test results.

## What these checks do not prove

The pipe trace uses a synthetic auth resolver and synthetic application responses.
It is not an actual Core, Gateway WSS, AndroidKeyStore or device acceptance test.
The native UI does not display synthetic fixture sessions in production.

Android instrumentation is authored for the actual provider crypto vector tests,
chunk codec and AndroidKeyStore/AtomicFile failure cases. A compiled test APK is
not evidence those tests ran. Record the exact CI commit, run and outcomes here
before describing them as passed.

## Aggregate build checkpoint

The SDK mismatch was fixed using supported AGP 9.1.1 / Gradle 9.3.1 / JDK 17 /
compile SDK 37 / Build Tools 36.0.0. Target remains 36 and minimum remains 28.
The complete local aggregate passed: app and instrumentation APK assembly,
29 application unit tests, and lint with zero errors. APK signature verification
passed, only INTERNET permission was declared, and the app is not debuggable.

Android instrumentation has not yet run. CI/runtime outcomes must still be
recorded against the published commit; do not infer them from this build.

No live user endpoints were contacted, no invitations issued, and no server or
Windows deployment was modified for these tests.
