# Bridge Native Reader

Independent native Android Reader client for the audited Bridge protocol. This
project does not embed WebView or retrieve a remote application. It does not
change the Windows Core, Gateway deployment or the separate WebView preview.

## Scope

The initial vertical slice imports an origin-scoped invitation by paste or an explicitly selected QR image,
performs the existing Gateway and end-to-end cryptographic handshakes, saves a
Reader credential with AndroidKeyStore-backed encryption, and displays native
session selection plus manually refreshed text snapshots. It intentionally has
no terminal input, resize, upload or other mutation controls. Text snapshots are
not a full VT terminal emulator.

This working tree is still an internal implementation milestone. Do not install
or distribute a build as accepted until the final verification report identifies
the exact commit and distinguishes host tests, Android runtime tests and actual
Windows/VPS/mobile acceptance.

## Layout

- `android-client/`: native Android UI, secure storage and transport.
- `java/`: independent Java E2EE and Gateway chunk codec, synthetic vectors.
- `gateway/`: source-linked wire audit and fixture exporters.
- `PROTOCOL-AUDIT.md`: handshake, enrollment, persistence and permission gates.

Protocol baseline: `weikeyi/herdr-remote-bridge` Bridge commit
`4e80d943579c7213cce78dedeb87df04a428f649`. The standalone Android branch is
based on `dev`; it does not depend on merging the separate WebView PR.

## Security boundaries

Only an explicitly configured HTTPS origin is accepted, including valid IP TLS
certificates and explicit ports. WSS stays at the same authority. System TLS
verification remains enabled. No certificate bypass, cleartext, remote HTML,
external invitation intents, clipboard scraping or secret logging is permitted.
Only Reader enrollment is accepted. Reconnect always negotiates fresh ephemeral
keys. Invitations are transient and are not stored. App backgrounding stops the
connection. Previously authorized, identity-bound saved credentials can recover
automatically in the foreground with bounded backoff; Disconnect disables it.

Synthetic fixture keys are public test data, not production secrets. Never put
real invitations, VPS addresses, terminal content or credentials in tests, issues,
CI variables or logs.

See `android-client/LICENSE` and `android-client/NOTICE` for licensing and
attribution of protocol-derived code.

## Phone Reader experience

The connection form collapses after authentication. Selecting a uniquely labeled
pane opens a larger scrollable native text view; refresh and copy are explicit
actions. Copy places the displayed sanitized text on the system clipboard and
marks it sensitive on supported Android versions. No automatic clipboard reads
or command-entry controls exist.

QR import decodes one user-selected image on the device without uploading it.
It is **not a live camera scanner**. The system image provider may retrieve a file
the user selects; the application has no camera, gallery-wide storage or cloud
recognition permission/SDK. Paste remains available. Identified text must pass
the same origin/expiry/scope checks, and the user still presses Pair.
