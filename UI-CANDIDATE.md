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

## 夜航

The approved direction uses a neutral dark canvas and one citrine accent.
The featured recent entry is the last terminal opened in the current Activity,
only when its full live target still appears in the session inventory. It is
not written to disk, does not survive a fresh Activity, and never duplicates
a row in the remaining list. Nothing is featured as recent on first use.

The terminal view is unboxed. Terminal text is not synthetically highlighted
or altered by this UI iteration. Input gating, exact target checks, refresh and
credential behavior remain unchanged.

### Night Voyage usability polish

Search filters visible, live session titles in the app only. The query is cleared
on leaving Home; search does not change pairing, capabilities or remote state.
The terminal has a separate wrapping heading and adapts the composer to the
keyboard and system font size. Explicit text without Enter and deliberate Enter
remain separate. Exact build visual QA exercises the standard 400dp profile and
a 360dp profile with 1.20 font scaling; the standard IME view is measured
against the prior 400dp snapshot.
