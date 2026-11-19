# Native E2EE protocol core

Platform Java cryptography, no WebView, JavaScript runtime or third-party crypto.
Production source is compatible with the Android API 28+ Java APIs used by the
native client. JDK 17 is the host-side test toolchain; Android provider/runtime
acceptance remains separate.

## Integration boundary

Call `E2ee.start(kind, id, version, secretBase64url, locale)` for **each** connection.
Serialize its immutable `hello` fields into the protocol v2 client-hello object.
Pass a strictly parsed server-hello into `acceptServerHello`. The returned session
is a **candidate**, not an enrollment result. Encrypt client finish first; decrypt
and validate server finish, its credential fields, role and locale before exposing
application messages or persisting credentials. The enclosing state machine must
close candidates on timeout, disconnect, invalid finish or cancellation.

The session uses gateway binary frames, not the direct WebSocket JSON codec.
Outer gateway multiplexing and chunk framing are separate. Finish consumes sequence
zero; regular application messages start at one. The session is synchronized and
fails closed on invalid framing, sequence, authentication or UTF-8. Reconnect
requires fresh keys/nonces and a new session. It never resets counters in place.

A local 4 MiB plaintext bound limits allocations; it is not the upstream maximum.
Secrets are not logged. Owned raw secret arrays are cleared on close where
possible; Java providers/VM can retain internal copies, so this is not a promise
of physical memory erasure. The class intentionally exposes no test-key injection
outside its package. No network connection, credential persistence or UI is
implemented by this class.

## Verification

```sh
python3 fixtures/extract.py --check
JAVA_HOME=/path/to/jdk17 bash test.sh
```

`fixtures/upstream-e2ee-v2.json` is byte-for-byte copied from the audited Bridge
source `contracts/fixtures/e2ee/v2.json` (v0.25.1 protocol baseline).
SHA-256: `d089162c020446fa89f783a60fd64ca432ace4ca23e8aaa70e61611690a8df74`.
The fixture's misleadingly named `relay_key` is literal ASCII secret bytes,
not base64. Its keys/nonces are public synthetic test values, never usable
production credentials. The s2c sequence-zero example is a data frame, **not**
evidence of enrollment completion.

The Java test verifies HMAC proofs, transcript, HKDF, encrypted finish and data
frames against this fixture; the original Go `TestE2EEVersionTwoVector` was also
run successfully against the unchanged implementation. Fixture properties are a
mechanical JSON projection, not independently invented expected outputs.
Negative tests cover corrupted tags/headers, replay, sequence gaps and exhaustion,
invalid P-256 points, malformed base64url and invalid UTF-8. Full fresh-device
enrollment and end-to-end native terminal acceptance must be tested separately.

## Randomized Java ↔ original Go integration

With the audited source checkout and Go 1.27.3 available:

```sh
RELAY_SOURCE=/path/to/relay JAVA_HOME=/path/to/jdk17 bash integration/test.sh
```

The harness copies `internal/transport/e2ee.go` byte-for-byte into a temporary
isolated module, checks it with `cmp`, prints its SHA256 and invokes the actual
Go handshake/seal/open functions. Only `FrameConn`, the auth resolver and app
responses are synthetic. Frames move over process pipes; no network endpoints or
user credentials are involved. Both peers generate fresh random nonces/ephemeral
P-256 keys. Synthetic invitation/credential secrets are generated per run.

The trace verifies invitation authentication, **real encrypted client/server
finish**, Reader identity, credential issuance, encrypted agents/read_pane
exchange with Unicode terminal text, disconnect, and credential authentication on
a fresh session. Both finish sequences restart at zero and application frames
start at one. Reconnect does not issue a replacement credential.

This is crypto interoperability evidence, not an Android device run, WSS/chunk
integration, Core terminal integration or durable vault-save test. The test uses
a deliberately narrow parser for controlled synthetic JSON; production must use
the strict application state machine/parser. The synthetic resolver does not
claim to implement the actual server invitation retry/revocation persistence.
