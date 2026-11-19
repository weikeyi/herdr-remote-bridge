# Remaining native acceptance gates

Do not treat an internal APK artifact as a passed installation test. The protocol
milestone has working tests, but the following observations must be recorded
against the exact built commit before a user-facing native preview is recommended.

1. CI assembles the app and instrumentation APK, passes unit tests/lint, records
   both hashes and source commit, and verifies only INTERNET permission with
   debugging/backup/cleartext disabled.
2. An isolated emulator executes all eighteen expected crypto/chunk/vault/UI/QR tests from
   that same build. Zero tests, skips, crashes and partial logs are failures.
3. Real WSS connects to a test Bridge with a valid test TLS certificate. Exercise
   Gateway hello/connect/ready, E2EE enrollment, Reader role and successful
   credential persistence before any session content is displayed.
4. Display an authentic agents snapshot. Select a current exact target and refresh
   a full read_pane snapshot. Verify UTF-8, control-sequence stripping and bounds.
5. Disconnect/background, then return to the foreground and verify saved-credential automatic recovery.
   Confirm bounded recovery, fresh negotiation, successful credential authentication and invitation
   consumption on the test Core. No old target/content may reappear automatically.
6. Revoke the test credential server-side; reconnect must fail without falling back
   to the invitation. Corrupt or remove the vault key in the isolated test app and
   confirm failure without silently generating a replacement key over the record.
7. Change the terminal generation/session identity while a read is in flight;
   stale content must not be shown. Exercise repeated refresh/select, background,
   disconnect and cancellation, including a failed network during enrollment.

The existing user VPS, Windows Core and separate WebView preview are not test
fixtures and must not be changed automatically. No real credential or terminal
output belongs in a public test log, issue, screenshot or CI artifact.

For the QR/UX revision also verify a real user-selected local image, image-picker
cancellation, two-code rejection, changed origin while selection is pending,
background cancellation and explicit copy. A successful decode fills the invite
field only; it must never silently pair or open a decoded URL. Live camera QR
scanning, full VT behavior, write controls and modern physical-device coverage
remain outside this Reader preview.
