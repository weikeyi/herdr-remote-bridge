# Herdr Remote Bridge: Windows test bundle

This is an independent fork CI test build, not an official upstream release,
a signed production release, or a deployment.
Read `README.zh-CN.md` for the testing sequence and `windows/README.md` for
launcher, credential, task and update instructions. Nothing installs on extraction.

Source: https://github.com/weikeyi/herdr-remote-bridge . Select the complete
commit in `build-manifest.json`, not the initialization-only `main` branch.
This is an independent AGPL-3.0-or-later fork of `0cv/herdr-mobile-relay`;
the included LICENSE and source repository preserve upstream notices.

## Contents and identity

- `herdr-mobile-relay.exe`: Windows amd64 relay built from this commit.
- `web/`: newly built matching PWA, ready for your separately authorized HTTPS host.
- `windows/`: current-user launcher scripts and a non-secret configuration example.
- `build-manifest.json`: version, full source revision and per-file SHA-256 hashes.
- `SHA256SUMS`: hashes of all payload files including the build manifest.

Verify the downloaded artifact came from the intended repository and successful
CI commit. Checksums detect changes, not publisher authenticity. Compare
`herdr-mobile-relay.exe version --json` with `build-manifest.json` and
`web/version.json`; version and revision must match. Keep this complete directory
layout, with `web` beside the executable. No Node, Bun or Go installation is needed.

Start with `herdr-mobile-relay.exe bridge self-test` in an ordinary, non-admin
PowerShell. It creates and removes temporary test secrets and pipes only. Do not
enter API keys in chat or command arguments. Follow the local masked credential
workflow in `windows/README.md` before explicitly installing a current-user task.

This bundle does not configure a VPS, domain, TLS, Gateway or phone connection.
Core/Desktop must already be available and its pipe must be verified locally.
Do not upload the executable, scripts, manifests, credentials or your configuration
to the PWA host: deploy only `web/`, following the separate hosting instructions.
The executable-only updater does not update hosted web assets. Real limited-user
installation, Core integration, phone pairing and rollback still need acceptance.
