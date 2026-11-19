# Changelog

## [Unreleased]

### Added

- Explicit Controller pairing and guarded single-line terminal text plus separate
  Enter actions. Reader stays read-only; stale targets and unknown delivery block
  further input without automatic retries.

- On-device QR invitation image import with strict single-code/resource limits,
  stale-result cancellation, origin revalidation and explicit pairing.
- A phone-oriented Reader layout, distinct pane labels, readable scrollable
  snapshots, refresh and explicitly requested sensitive clipboard copy.

- Independent native Android Reader development milestone, without a WebView or
  remotely loaded application. It imports scoped invitations, encrypts saved
  credentials with AndroidKeyStore, and implements read-only session selection
  and full text snapshot retrieval using the existing Bridge protocol.
- Identity-bound foreground recovery with bounded backoff and bounded native text
  rendering. Background connections and full terminal emulation are not included.

This milestone is not yet device-accepted. See VERIFICATION.md for the precise
boundary between implemented code and executed acceptance tests.
