# Android preview security boundary

This is an independent client shell, not a modification of the official Herdr Android application. It does not implement SSH or ProxyJump. It loads an existing Bridge PWA from an HTTPS origin explicitly entered by its owner. No server, Windows component, credential, or invitation is bundled.

## Trust and transport

The configured server and the JavaScript it serves are trusted. Android verifies TLS normally, including IP address certificates when an IP origin is used. Certificate errors are not bypassable in the app. The configuration accepts an origin, not a path, and the app rejects invitations for another origin. The invitation must select the same WSS authority and relay-only transport.

Native navigation filters are not a firewall: Android WebView does not expose every WebSocket request through its normal resource interception callbacks. A compromised trusted server could serve malicious JavaScript. Do not describe this wrapper as independent enforcement against that server. Maintain the server, its certificates and its PWA, and only pair devices to servers you control.

The PWA continues to own encrypted protocol operations and authorization. The shell is not a new authorization authority: Reader restrictions must be enforced by the Windows relay. This preview does not promote Reader invitations to Controller.

## Stored information

The configured origin and PWA session persist in the application sandbox. PWA device credentials use the existing browser storage format, not Android Keystore hardware-backed keys. No invitation is deliberately persisted by the native app or included in logs, intents, analytics or backup. Pairing URLs still exist transiently in WebView and JavaScript memory while being redeemed. Clearing app data removes local access; revoke the device from Windows to revoke its server-side authority.

The Android clipboard and keyboard are outside this app's exclusive control. Use a trusted keyboard and clear the clipboard after pairing. Do not paste a production invitation into issue reports, chat messages or CI.

## Limitations to verify on a real device

A recent Android System WebView is required. Android API level alone is not a guarantee of Web Crypto support. Validate P-256 ECDH, HKDF, HMAC-SHA256, AES-GCM, secure context and persistent storage in the actual WebView. Validate pairing, removal of invitation fragments, relaunch, Reader authorization, expiry, revocation, disconnect/reconnect and no replay of writes.

A hosted PWA can update independently of the APK. The protocol integration reference is Bridge commit `4e80d943579c7213cce78dedeb87df04a428f649`; this reference is not proof of an end-to-end test of any live deployment.

## Deliberately absent

No native JavaScript bridge, certificate override, SSH client, VPN, port scan, credential importer, external invitation intent receiver, Play publishing, microphone/camera/location capability or file picker is needed for the first Reader preview. Background connectivity and notifications are not promised. Android may suspend or destroy WebView while the app is backgrounded.
