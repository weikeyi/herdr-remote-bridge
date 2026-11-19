# Preview acceptance checklist

Record the exact source commit, APK SHA-256, APK signing certificate SHA-256,
Android version and Android System WebView version for each acceptance run.
Never record invitation URLs, gateway registration tokens or device credentials.

## Automated

- Pure Java origin/invitation validation unit tests.
- Android lint and APK assembly.
- Packaged application permission inspection: INTERNET only.
- Emulator: HTTPS secure context, Web Crypto round-trips and localStorage across WebView recreation.
- Emulator: native WebView security settings, backup and cleartext manifest policy.

A successful APK build is not a successful emulator test. An emulator result is
not a production end-to-end pairing result. CI reports identify which ran.

## Device and protocol integration (not automated against a user's server)

Use a temporary Reader invitation for a non-sensitive test session and a
user-controlled server with a valid system-trusted certificate. The reference
server/Windows protocol is Bridge `4e80d943579c7213cce78dedeb87df04a428f649`.

1. Fresh install shows no preselected server or credentials.
2. Configure HTTPS hostname or IP:port; verify certificate rejection without bypass.
3. Wait for Web Crypto capability pass before pasting an invitation.
4. Pair once; confirm invitation fragment disappears and Back cannot reveal it.
5. Check test session read access; attempt a write and confirm the relay rejects Reader authority.
6. Close and reopen the app; existing device session resumes without the invitation.
7. Disconnect/reconnect network; ensure no queued writes or authorization changes occur.
8. Try expired, duplicated, wrong-origin and malformed invitations; none should enroll.
9. Clear application data; verify origin/session removed and a new invitation is required.
10. Revoke the old device from Windows; verify it cannot reconnect.
11. Check portrait/landscape, small screen, keyboard, repeated buttons, Cancel and Back.

Do not request Controller authority merely to make a failed Reader test pass.
Do not treat uninstall as server-side revocation. No release-signing or automatic
update guarantee exists for the debug preview.
