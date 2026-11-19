# Native milestone verification

Status: explicit Controller input revision locally verified, pending its twenty-test Android
CI run. Not a physical-device-accepted production release. The preceding native
core commit b0697bcd passed all CI gates, including eleven actual API 29 tests.

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

## QR/Reader UX revision

The native core's corrected run 44501375389 at b0697bcd completed successfully,
including all eleven Android 10 tests. The next local revision adds bounded
offline QR-image decoding, an explicit image-picker workflow and phone Reader
layout/copy/refresh improvements. Forty-two JVM tests pass, including six real
ZXing fixture tests; actual WSS regression passes. Eighteen Android cases are
authored and compiled, including generated PNG decoding, blocked-pipe
cancellation, stale/invalid picker results, current-origin validation and Reader
layout/explicit clipboard behavior. Runtime verification of those seven new
cases must not be inferred from compilation or the previous eleven-test pass.

## QR revision actual runtime result (6d70ea28)

Run 44506025231 build, 42 JVM tests, lint and real-WSS/Go parity passed.
The first instrumentation attempt timed out installing the APK after emulator
boot, executing zero tests. The bounded retry executed all eighteen tests:
seventeen passed, including QR BitmapFactory decoding/cancellation, invitation
origin checks, KeyStore and foreground recovery. The clipboard UI test failed
because getPrimaryClip returned null. The next patch investigates Android 10's
focused-window clipboard requirement without dropping the real clipboard assertion.

Android Activity/lifecycle/vault tests and host-JVM real WSS are separate verified
legs, not one integrated Activity → WSS → AndroidKeyStore acceptance trace.
Physical Android/user Windows/VPS acceptance remains pending. An isolated test
CA would require a separate acceptance-only build variant; the delivered APK
must retain system-only trust and must not include that CA or a TLS bypass.

## Native Controller input revision

The user expanded the first-version scope to explicit terminal input. Controller
requires a separately selected pairing role matching the authenticated server
finish. Old v1 stored credentials remain Reader. Both a current per-pane
manual_terminal_input capability and a matching snapshot younger than three
seconds are required. Native text is limited to 4096 strict UTF-8 bytes without
controls or multiline; Enter is a separate deliberate action. Only one operation
may be pending. No input is queued, persisted, logged or replayed on reconnect.
A non-secret uncertainty latch is persisted before dispatch; unknown delivery
requires a fresh explicit inspection before another write.

The current local suite passes 54 JVM tests and zero lint errors. Reader and
Controller real-WSS fixtures pass: Controller text and Enter reach a synthetic
counter once each, fresh reconnect does not replay, Reader performs zero writes,
and bad CA/hostname are rejected. Unchanged original Go app/credential tests
pass six top-level cases locally. Five coordinator cases require Unix sockets
that the local sandbox denies (EPERM), so those are not local passes; the full
unchanged original-enforcement script is a required CI step.

Twenty Android runtime cases are compiled, preserving the prior eighteen and
adding two Controller UI/uncertainty cases. They still require execution against
the exact final commit. c8f1f045's preceding clipboard-only fix passed build and
protocol checks but its device job timed out installing the APK, before running
tests. Installation timeout is now bounded at five minutes for slow software
emulation; no test is skipped or assertion removed.
