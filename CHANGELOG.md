# Changelog

## [Unreleased]

### Added

- Independent native Android Reader development milestone, without a WebView or
  remotely loaded application. It imports scoped invitations, encrypts saved
  credentials with AndroidKeyStore, and implements read-only session selection
  and full text snapshot retrieval using the existing Bridge protocol.
- Explicit reconnect and bounded native text rendering. Full terminal emulation,
  remote input and automatic background reconnection are not included.

This milestone is not yet device-accepted. See VERIFICATION.md for the precise
boundary between implemented code and executed acceptance tests.
