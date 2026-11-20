# Native Android phone UI candidate

This branch is a visual QA candidate based on preview `eccbb308`. It does not
replace the original preview or deploy anything. Review actual Android emulator
screenshots with synthetic session/terminal content before promoting an APK.

## Review on a phone-sized viewport

- First launch: pairing is separate from the session home. The invitation is
  entered or imported explicitly and is never persisted.
- Home: connection and permission status, readable session titles, distinct
  pane identifiers, loading/empty/disconnected states and a way to reconnect.
- Detail: select a session to open its terminal directly, using the available
  viewport. Back returns to sessions. A read-only snapshot refreshes about every
  four seconds only on the foreground detail screen. Refresh/copy controls
  remain available. Passive snapshots do not grant write freshness; polling
  pauses during inspection or uncertain delivery, and a failed passive fetch
  pauses automatic reads until a successful manual refresh.
- Controller: draft input and distinct send-text/Enter controls stay usable with
  the keyboard visible. Reader has no composer. An acknowledged write may
  refresh the same pane, but it never sends the next action automatically.
- Safety: exact target, scoped role and capability, elapsed-time freshness,
  single in-flight operation and durable unknown-delivery lock still apply.
  Backgrounding or leaving the target must not reveal stale content or resend.

The delivered `eccbb308` APK used an ephemeral debug cert. This candidate uses
`dev.herdrbridge.nativeclient.preview` (launcher label **Bridge 终端**) so it
installs alongside the old preview without touching its data. It requires a new
invitation and pairing. No stable signing key is created here, so future
in-place updates to this new package are still not guaranteed.
Software-emulator screenshots and runtime tests are not physical-device, live
Windows, or live relay acceptance.

## Dark terminal redesign

This iteration keeps the preview application ID and pair/role/input protocol
unchanged. The app has a dark native theme and vectors, distinct setup and session
hierarchy, accessible icon actions and a compact terminal composer. Emulator
screenshots and geometry assertions use only synthetic fixture data.

A different ephemeral debug signer prevents in-place upgrades even with a
larger version code. Preserve the installed build and its pairing until a new
invitation is ready and you decide to switch. A reinstall loses the phone's
local credential; the existing bridge credential is not remotely revoked by
uninstall. No signing key or real invitation is created in this candidate.
