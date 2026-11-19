#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${JAVA_HOME:?JAVA_HOME must point to a JDK 17}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 17 -Xlint:all -Werror -d "$out" src/main/java/dev/herdrbridge/protocol/E2ee.java src/test/java/dev/herdrbridge/protocol/E2eeTest.java
"$JAVA_HOME/bin/java" -cp "$out" dev.herdrbridge.protocol.E2eeTest fixtures/vector.properties
