# Source and upstream provenance

Herdr Remote Bridge is a modified fork of [0cv/herdr-mobile-relay](https://github.com/0cv/herdr-mobile-relay), based on v0.25.1 (commit `7fb48b27d3c4b81089bf91f7534d626faf018390`).

The source for this fork is intended to be published at [weikeyi/herdr-remote-bridge](https://github.com/weikeyi/herdr-remote-bridge). Preserve the full corresponding source when distributing modified builds or operating a modified network service, as required by the license.

The upstream copyright notices and [AGPL-3.0-or-later license](LICENSE) remain unchanged. Bundled fonts, libraries and assets retain their own notices. This fork does not represent an official upstream Windows release.

The Bridge changes add relay-only private WSS transport, native Windows Core Named Pipe support, current-user protected credentials, local device management and Windows lifecycle tooling. See [Windows setup](relay/windows/README.md), [gateway setup](relay/bridge/README.md) and [CHANGELOG](CHANGELOG.md).

Windows CI uses temporary synthetic credentials and pipes. It does not install a user service, alter a user's Core/App installation, deploy a server or publish a release. Native CI success is not a claim that a user's actual Windows/VPS/Android setup has been connected.
