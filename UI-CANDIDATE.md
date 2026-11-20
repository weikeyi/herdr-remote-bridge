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
  viewport. Back returns to sessions. Refresh/copy are explicit controls.
- Controller: draft input and distinct send-text/Enter controls stay usable with
  the keyboard visible. Reader has no composer. An acknowledged write may
  refresh the same pane, but it never sends the next action automatically.
- Safety: exact target, scoped role and capability, elapsed-time freshness,
  single in-flight operation and durable unknown-delivery lock still apply.
  Backgrounding or leaving the target must not reveal stale content or resend.

The delivered `eccbb308` APK used an ephemeral debug cert. A differently signed
candidate cannot overwrite it in place; no stable signing key is created here.
Software-emulator screenshots and runtime tests are not physical-device, live
Windows, or live relay acceptance.
