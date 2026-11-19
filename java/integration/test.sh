#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${RELAY_SOURCE:?Point RELAY_SOURCE at the audited relay checkout}"
: "${JAVA_HOME:?Point JAVA_HOME at JDK 17}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/internal/transport" "$work/internal/protocol" "$work/classes"
# Execute the original crypto implementation without source edits or private API exports.
cp "$RELAY_SOURCE/internal/transport/e2ee.go" "$work/internal/transport/e2ee.go"
cmp "$RELAY_SOURCE/internal/transport/e2ee.go" "$work/internal/transport/e2ee.go"
cp "$here/pipe.go" "$work/internal/transport/pipe.go"
printf 'module github.com/0cv/herdr-mobile-relay\n\ngo 1.27.3\n' > "$work/go.mod"
grep -Eq 'EncryptedWebSocketSubprotocol[[:space:]]*=[[:space:]]*"herdr-e2ee-v2"' "$RELAY_SOURCE/internal/protocol/protocol.go"
sha256sum "$work/internal/transport/e2ee.go"
# The only imported protocol identifier in e2ee.go is this protocol constant.
printf 'package protocol\nconst EncryptedWebSocketSubprotocol = "herdr-e2ee-v2"\n' > "$work/internal/protocol/constant.go"
cat > "$work/main.go" <<'GO'
package main
import("fmt";"os";"github.com/0cv/herdr-mobile-relay/internal/transport")
func main(){if err:=transport.RunPipe();err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}}
GO
(cd "$work" && go build -buildvcs=false -o server .)
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$work/classes" "$here/../src/main/java/dev/herdrbridge/protocol/E2ee.java" "$here/PipeIntegration.java"
timeout 45s "$JAVA_HOME/bin/java" -cp "$work/classes" dev.herdrbridge.protocol.PipeIntegration "$work/server"
