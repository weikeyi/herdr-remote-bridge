#!/usr/bin/env bash
set -euo pipefail
umask 077
here="$(cd "$(dirname "$0")" && pwd)"
native="$(cd "$here/../.." && pwd)"
: "${RELAY_SOURCE:?Point at the audited relay source checkout}"
: "${JAVA_HOME:?JDK17 is required}"
: "${ANDROID_JAR:?Point at installed SDK android.jar for compile-only platform types}"
work="$(mktemp -d)"
server_pid=''
cleanup(){ if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi; rm -rf "$work"; }
trap cleanup EXIT
mkdir -p "$work/internal/"{transport,protocol,gateway,gatewaywire,framing} "$work/classes" "$work/jars"
# Production Go crypto, gateway and chunking are copied unchanged, not reimplemented.
cp "$RELAY_SOURCE/internal/transport/e2ee.go" "$work/internal/transport/"
for package in gateway gatewaywire framing; do
  for file in "$RELAY_SOURCE/internal/$package/"*.go; do
    [[ "$file" == *_test.go ]] && continue
    cp "$file" "$work/internal/$package/"
    cmp "$file" "$work/internal/$package/$(basename "$file")"
  done
done
cmp "$RELAY_SOURCE/internal/transport/e2ee.go" "$work/internal/transport/e2ee.go"
sha256sum "$work/internal/transport/e2ee.go"
cp "$here/"{gateway,synthetic}.go "$work/internal/transport/"
cat > "$work/internal/transport/frameconn.go" <<'GO'
package transport
import "context"
type CloseStatus int
type FrameConn interface{
ReadFrame(context.Context)([]byte,error)
WriteFrame(context.Context,[]byte)error
Close(CloseStatus,string)
CloseNow()
Codec()FrameCodec
TransportName()string
}
GO
grep -Eq 'EncryptedWebSocketSubprotocol[[:space:]]*=[[:space:]]*"herdr-e2ee-v2"' "$RELAY_SOURCE/internal/protocol/protocol.go"
cp "$RELAY_SOURCE/internal/protocol/protocol.go" "$work/internal/protocol/protocol.go"
cmp "$RELAY_SOURCE/internal/protocol/protocol.go" "$work/internal/protocol/protocol.go"
printf 'module github.com/0cv/herdr-mobile-relay\n\ngo 1.27.3\n\nrequire github.com/coder/websocket v1.8.15\n' > "$work/go.mod"
grep '^github.com/coder/websocket v1.8.15' "$RELAY_SOURCE/go.sum" > "$work/go.sum"
cat > "$work/main.go" <<'GO'
package main
import("fmt";"os";"github.com/0cv/herdr-mobile-relay/internal/transport")
func main(){if err:=transport.RunWSS();err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}}
GO
(cd "$work" && go build -mod=readonly -buildvcs=false -o server .)
# JVM variant of the SAME pinned OkHttp version, for a host-side test only.
# No dependency or trust override is added to the production Android app.
paths=(
  com/google/zxing/core/3.5.4/core-3.5.4.jar
  com/squareup/okhttp3/okhttp-jvm/5.5.1/okhttp-jvm-5.5.1.jar
  com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1.jar
  org/jetbrains/kotlin/kotlin-stdlib/2.1.21/kotlin-stdlib-2.1.21.jar
  org/jetbrains/annotations/13.0/annotations-13.0.jar
  org/json/json/20240303/json-20240303.jar
)
for path in "${paths[@]}"; do
  name="${path##*/}"
  if [[ -n "${WSS_JARS_DIR:-}" ]]; then cp "$WSS_JARS_DIR/$name" "$work/jars/$name"
  else curl --fail --location --retry 2 --proto '=https' "https://repo.maven.apache.org/maven2/$path" --output "$work/jars/$name"; fi
done
(cd "$work/jars" && sha256sum --check --strict "$here/jars.sha256")
classpath="$(find "$work/jars" -name '*.jar' -printf '%p:' )$ANDROID_JAR"
mapfile -t sources < <(find "$native/src/main/java" "$native/../android-client/app/src/main/java" -name '*.java')
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$classpath" -d "$work/classes" "${sources[@]}" "$here/NativeWssIntegration.java"
HARNESS_DIR="$work" "$work/server" > "$work/config.json" 2> "$work/server.log" &
server_pid=$!
for ((i=0;i<100;i++)); do
  [[ -s "$work/config.json" ]] && break
  if ! kill -0 "$server_pid" 2>/dev/null; then cat "$work/server.log"; exit 1; fi
  sleep 0.1
done
test -s "$work/config.json"
"$JAVA_HOME/bin/keytool" -importcert -noprompt -alias synthetic-native-harness -file "$work/test-ca.pem" \
  -keystore "$work/truststore.p12" -storetype PKCS12 -storepass test-only-password > "$work/keytool.log" 2>&1
# Process-only trust store: no system, Gradle, production app, or user cert changes.
set +e
timeout 100s "$JAVA_HOME/bin/java" -Djava.net.preferIPv4Stack=true -Djavax.net.ssl.trustStore="$work/truststore.p12" \
 -Djavax.net.ssl.trustStorePassword=test-only-password -Djavax.net.ssl.trustStoreType=PKCS12 \
 -cp "$work/classes:$classpath" dev.herdrbridge.nativeclient.NativeWssIntegration "$work/config.json"
status=$?
set -e
cat "$work/server.log"
exit "$status"
