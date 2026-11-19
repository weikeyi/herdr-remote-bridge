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
- Final Android host-side unit suite: 36 tests, including seven foreground
  recovery/state/epoch/budget tests.
- Randomized original Go ↔ Java process-pipe trace: invitation handshake,
  encrypted client/server finish, Reader credential issuance, encrypted agents
  and read_pane content including UTF-8, then fresh credential reconnect.
  The Go cryptographic source is copied unchanged and byte-compared before use.
- CI instrumentation-result checker: 6 test groups, rejects skipped/partial
  or mismatched test results.

## Real WSS transport gate

The actual NativeRelayClient connects over loopback TLS/WebSocket to unchanged Go
Gateway, relay registration/multiplexing, chunk framing and E2EE implementations.
The final trace passes invitation import/enrollment, fresh credential confirmation,
current agent list, exact-target read_pane with a 300KB UTF-8 multi-chunk snapshot,
and another fresh credential reconnect. Untrusted CA and wrong hostname both
fail as security errors before credential persistence. Server-side test data and
credential storage are synthetic; the host test uses a process-only temporary CA
truststore, not a production TLS override. All worker connections are closed.

## What these checks do not prove

The original pipe trace does not exercise WSS. The later real-WSS gate does, but
its auth resolver, application responses and in-memory credential store remain
synthetic. It is not a real Windows Core, AndroidKeyStore or physical-device
acceptance test.
The native UI does not display synthetic fixture sessions in production.

Android instrumentation is authored for the actual provider crypto vector tests,
chunk codec and AndroidKeyStore/AtomicFile failure cases, and real native Activity lifecycle
smoke cases using invalid inputs only. A compiled test APK is
not evidence those tests ran. Record the exact CI commit, run and outcomes here
before describing them as passed.

## Aggregate build checkpoint

The SDK mismatch was fixed using supported AGP 9.1.1 / Gradle 9.3.1 / JDK 17 /
compile SDK 37 / Build Tools 36.0.0. Target remains 36 and minimum remains 28.
The complete local aggregate passed: app and instrumentation APK assembly,
36 application unit tests, and lint with zero errors. APK signature verification
passed, only INTERNET permission was declared, and the app is not debuggable.

For published milestone `d306b404`, CI build and randomized Go/Java parity
passed on run 44494949225 attempt 2. Android instrumentation never started:
the software-emulated API 36 Google APIs image timed out during boot. This is
zero executed Android tests, not an application test pass or assertion failure.
The internal APK is not being packaged as a user preview. The next commit adds foreground recovery and the passed isolated real-WSS gate.
An AOSP API 29 software lane will execute eleven required crypto/chunk/vault/UI
cases; those runtime outcomes are still pending. QR scanning and full terminal
emulation are not implemented.

No live user endpoints were contacted, no invitations issued, and no server or
Windows deployment was modified for these tests.

## First actual Android 10 run

Commit `a5232882`, run 44499480450, successfully built and passed the real-WSS
CI gate. Its AOSP API 29 emulator booted and executed all eleven tests: seven
passed, including the AndroidKeyStore case and all four actual Activity/lifecycle
cases. Four test-harness failures remained: three chunk tests used desktop-only
`HexFormat`, and the crypto fixture tried to write the test APK's cache while
executing under the target app UID. The subsequent patch uses a strict portable
hex decoder and the target app cache, enables lint inspection of test sources,
and preserves every original assertion. Local chunk tests and the full aggregate
pass; the corrected eleven-test Android run still needs verification.
