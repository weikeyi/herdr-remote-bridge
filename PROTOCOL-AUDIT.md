# Native Android protocol audit

Read-only audit of recovered/rc1/source, 2026-11-19. No live service, secrets, server changes or PR2 edits. Paths below are relative to that source root. This describes existing protocol, not a completed native APK acceptance.

## 1. Transport and authentication layers

- Gateway WSS is a distinct outer protocol, not direct relay WS. `frontend/src/lib/transports/gateway.ts:104–201`: connect to `wss://<configured-origin>/connect`; JSON `gateway_hello` with `proto:1, nonce` precedes client JSON `{type:"connect",proto:1,relay_id,proof}` and server `{type:"ready"}`. Only then start E2EE. Do not send E2EE directly as the initial gateway message.
- `frontend/src/lib/gateway-credentials.ts:18–97`: invitation carries relay_id (16 raw bytes, 22 base64url chars) and rendezvous key (32 bytes). Gateway proof = HMAC-SHA256(rendezvousKey, UTF8("herdr-gw-connect\0") || UTF8(encodedRelayId) || rawNonce32). This authenticates routing, not enrollment or role.
- Legacy relay-key derivation (not required for invitation-only native client): HKDF-SHA256 IKM=UTF8(token), salt=UTF8("herdr-gateway-v1"), info="herdr-gw-id" length16 or "herdr-gw-auth" length32.
- After ready, gateway binary WS messages use chunk framing, INCLUDING plaintext JSON E2EE hello. `frontend/src/lib/transports/chunking.ts`: [u8 version=1][u8 flags START=1 END=2][u32be total logical length only on START][payload]. Max wire chunk 262144 bytes; max logical 21 MiB; stall timeout30s; no interleaving. Reassemble first, distinguish sealed binary header from UTF8 JSON. This envelope is not the 10-byte sealed frame.
- Direct relay subprotocol is `herdr-e2ee-v2`, JSON sealed codec; gateway channel does not request that WS subprotocol, and uses binary sealed codec. Native scope must remain relay-only; ignore advertised STUN, no WebRTC/direct fallback.

## 2. Exact E2EE transcript and derivation

Authorities: `frontend/src/lib/e2ee.ts:1–22,183–298,299–399`; `internal/transport/e2ee.go:24–206,208–353`.
All labels UTF8/ASCII; `\0` denotes one byte 00. Base64url is URL-safe, unpadded. Numeric auth version encoded as ordinary positive decimal, not JSON bytes. Locale is sent but is NOT part of authBinding/proof transcript.

```
B = UTF8("herdr-e2ee-v2 auth\0" + kind + "\0" + id + "\0" + decimal(version) + "\0")
Nc,Ns = independent random 32-byte nonces
Pc,Ps = 65-byte raw SEC1 uncompressed P-256 points (04 || X32 || Y32)
T = B || Nc || Pc || Ns || Ps
clientProof = HMAC-SHA256(authSecret32, UTF8("herdr-e2ee-v2 client\0") || B || Nc || Pc)
serverProof = HMAC-SHA256(authSecret32, UTF8("herdr-e2ee-v2 server\0") || T)
Z = P-256 ECDH shared secret, 32 bytes (not DER/X509 serialization)
salt = HMAC-SHA256(authSecret32, UTF8("herdr-e2ee-v2 key\0") || T)
c2sKey = HKDF-SHA256(Z, salt, UTF8("herdr-e2ee-v2 c2s"), 32)
s2cKey = HKDF-SHA256(Z, salt, UTF8("herdr-e2ee-v2 s2c"), 32)
```

Client hello JSON: type=e2ee_client_hello, version=2, auth_kind=invitation|credential, auth_id, auth_version, locale, nonce, public_key, proof. Server hello: type=e2ee_server_hello, version=2, nonce, public_key, proof. Verify server proof constant-time before accepting/deriving session; reject invalid/off-curve points and wrong lengths. Fresh ephemeral key and nonce per connection. Native must never ship deterministic fixture scalars/nonces as runtime defaults.

## 3. Sealed frames, finish gate, ordering

- AES-256-GCM, 128-bit tag appended to ciphertext (JCA doFinal layout compatible).
- IV = zero4 || uint64be(sequence); AAD = UTF8("herdr-e2ee-v2 " + direction) || zero1 || uint64be(sequence).
- Separate c2s/s2c keys and counters, initial sequence0 each; safe range0..2^53-1. Strict next expected sequence, no gap/reorder/replay acceptance. Serialize concurrent sends and receives. Increment only after crypto success; close on invalid authenticated content rather than continuing a damaged stream.
- Binary sealed frame = [02][00][uint64be sequence][ciphertext || tag]. JSON alternative = {type:"e2ee",version:2,sequence,ciphertext:base64url}.
- First c2s plaintext EXACT semantic object `{type:"e2ee_client_finish",version:2}` at sequence0. Server verifies this before enrollment commit.
- First s2c plaintext must be `{type:"e2ee_server_finish",version:2,device_id,credential_id,role,locale,credential_version,credential_secret?}` at sequence0. Invitation requires 32-byte credential_secret; credential authentication MUST NOT replace secret. role only reader/controller. Validate identity/version/locale and bind existing credential identity before accepting.
- Only expose READY after server finish semantic validation AND durable credential save. Actual application traffic then starts at sequence1 in BOTH directions. Codec success alone is insufficient.
- `frontend/src/lib/transports/encrypted.ts:120–135` awaits onAuthenticated storage before assigning session/markReady; `device-auth.ts:118–155,240–262` atomically replaces invitation with credential and requires deviceId/credentialId consistency on reconnect.

## 4. Enrollment recovery, revocation and reconnect

IMPORTANT: the invitation is not irreversibly burned immediately at first successful finish. `internal/deviceauth/resolver.go:131–226` creates+persists a credential with PendingCredentialID before sending server_finish. A repeated valid invitation while unexpired returns the SAME pending credential. First successful authentication with that new credential consumes the pending invitation; disk failures roll back state. This recovers lost finish responses/client storage failures without enrolling duplicate devices. Tests: `internal/deviceauth/store_test.go:92` TestInvitationRedemptionRetriesUntilCredentialAuthentication; :147 TestRevokingPendingCredentialConsumesInvitation.

Native must durably atomically swap invitation material for credential before any app operation; preserve invitation on failed save, retain a safe retry/re-pair UX, never log either. Never assume receiving server hello means enrollment succeeded. Reconnect with stored credential, fresh ECDH/nonces/counters, no reuse of old ciphertext/counters or automatic replay of mutating commands. Revoked/version-mismatched credentials must stop pairing retries, not fall back to token/bootstrap. `internal/transport/e2ee.go:37–41` ErrDeviceAuthRejected covers spent/revoked/unknown material. Gateway unknown_relay/quota_exceeded are fatal in current browser transport; temporary network failures require bounded backoff and fresh protocol state.

## 5. Reader authorization

`internal/app/server.go:337–389` is authoritative: server reauthorizes credential and gates action metadata; read-only allowed, mutations generally controller-only with `reader_denied`. Exceptions include authenticated device-bound push operations and self-revocation; special pi capability/integration mutations require Controller. Native initial milestone should expose a minimal Reader allowlist, not infer permissions from action name. Invitation cannot self-select role; server_finish role is authoritative. Resize remains disabled, no write queue replay. Review `internal/app/server_test.go`, `panesize_containment_test.go:316`, `internal/protocol` action metadata before adding UI actions.

## 6. Independent fixtures and acceptance requirements

Primary existing deterministic fixture: `contracts/fixtures/e2ee/v2.json`. `internal/transport/e2ee_test.go:92` TestE2EEVersionTwoVector recomputes it with Go crypto, checks both public keys, transcript/proofs/sharedSecret/HKDF keys, JSON+binary frames. `frontend/tests/unit/transports.test.ts:445` independently consumes its binary record. `frontend/tests/unit/e2ee.test.ts` adds handshake/enrollment, tamper/replay/order cases; Go e2ee_fuzz_test.go parser fuzzing is reusable in spirit.

Fixture traps:
1. Its `relay_key` field is historical naming: Go uses **UTF8 literal 32-byte string** as auth secret, not base64url decode of that string (`e2ee_test.go:127`). Native Auth can base64url-encode those raw bytes at its API boundary.
2. Fixed private scalars1 and2/nonces are public synthetic test data only.
3. Fixture s2c record sequence0 is pane_content, NOT an actual server_finish. It validates crypto, not complete live handshake acceptance. Do not weaken native finish gating to accept this record as enrollment.
4. client hello fixture omits locale; production includes locale. Locale is not transcript-bound.
5. Fixture is present in audited source and exercised by Go/TS; this audit did not independently redownload upstream to assert byte-for-byte provenance.

Gateway cross-language vector: `frontend/tests/unit/transports.test.ts:69,179`, corresponding `internal/gatewaywire` tests. Chunk fixture at transports.test.ts:466 corresponds to Go internal/framing; include it before claiming WSS interoperability.

Native milestone gate: independently reproduce all v2 intermediate bytes and record bytes with JCA, reject wrong proof/key/point/version/sequence/tag/direction/truncation, enforce finish-before-ready, test pending invitation persistence failure/retry, and verify outer gateway chunking. JVM pass is NOT Android-provider/device proof. Follow with Android instrumentation and local Go fixture end-to-end; no live user secrets needed. Still not an installable fully working native client until native screens/storage/lifecycle/reconnect/device testing complete.
