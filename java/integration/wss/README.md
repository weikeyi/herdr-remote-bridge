# Actual native client over isolated WSS

This gate compiles and executes the **production NativeRelayClient Java sources**,
its invitation parser, E2EE, chunk codec and application protocol, rather than a
test-only client implementation. It connects through real loopback TLS/WebSockets
to the original Go gateway, relay-link multiplexing, framing and E2EE code.
The Go auth resolver and agent/pane service are synthetic. Credentials exist only
in an in-memory test store; AndroidKeyStore persistence has separate device tests.

## Run

From the Android client directory after the ordinary aggregate:

```sh
RELAY_SOURCE=/path/to/audited/relay \
ANDROID_JAR="$ANDROID_HOME/platforms/android-37.0/android.jar" \
bash ../java/integration/wss/test.sh
```

Requirements: JDK17 in JAVA_HOME, Go 1.27.3 on PATH, curl, Python-independent shell
tools, and the installed Android SDK JAR for compiling platform-only type
references. No emulator is needed for this host-side gate.

The checksum-pinned host-side dependency JARs are pinned in jars.sha256 and downloaded
from official Maven Central. They include the JVM variant of the same OkHttp 5.5.1
version, its exact declared runtime dependencies, the JVM org.json test implementation, and ZXing core required when
compiling the actual app QR classes. WSS_JARS_DIR optionally supplies a local cache; every JAR is still
checksum verified. This is not an Android OkHttp-provider/device acceptance test.

## Acceptance trace

1. Untrusted certificate and wrong certificate hostname must produce a native
   security ERROR before enrollment, not merely an arbitrary disconnect.
2. The actual invitation factory parses a scoped transient invitation. Gateway
   challenge/proof, original Go E2EE and both encrypted finishes complete.
3. The client emits ENROLLED only after its CredentialStore save; that invitation
   connection is closed before proceeding.
4. A fresh saved-credential connection authenticates, parses push_config/agents,
   requests the exact observed target and receives an authenticated snapshot.
5. Another fresh saved-credential reconnect repeats the list/snapshot flow.
   Go rejects reuse of either client nonce or client public key.
6. The snapshot contains 100000 Chinese characters, over 300 KB encoded, exercising
   real cross-WebSocket chunk reassembly within the native character limit.
7. Original Go protocol DecodeMap/ClassifyAction and strict synthetic request
   checks confirm the only application request is the intended read-only
   read_pane. All client workers terminate at the end.

## Isolation and limits

Only 127.0.0.1 and localhost are used. The temporary test CA is trusted **only** by
the spawned JVM through its own PKCS12 trust store. No production TrustManager,
HostnameVerifier, app trust configuration, system trust store or certificate
verification is changed. The isolated JVM prefers IPv4 so localhost reaches the
IPv4-only test listener; a connection refusal cannot count as a TLS rejection. Both negative endpoints continue using default OkHttp
TLS verification. Temporary configuration (including synthetic secrets), CA and
trust store are created under a private mktemp directory and deleted on exit.
Only redacted pass/failure trace goes to stdout/stderr; never upload the private
temporary directory. No user endpoint, token, session or computer is accessed.

This proves the actual Java network/protocol path against a synthetic service,
not a real Core terminal, VPS deployment, Android lifecycle, UI rendering or
AndroidKeyStore. Device CI and eventual real-environment acceptance remain
separate gates. The scripted reconnect explicitly drives the real client
factories; the foreground lifecycle coordinator has its own tests.
