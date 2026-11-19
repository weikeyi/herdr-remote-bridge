#!/usr/bin/env bash
set -euo pipefail
: "${BRIDGE_SOURCE:?Set absolute path to audited Bridge source checkout}"
base="$(cd -- "$(dirname -- "$0")" && pwd)"
export BRIDGE_SOURCE="$(cd -- "$BRIDGE_SOURCE" && pwd)"
export FIXTURE_DIR="$base/fixtures"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
cp "$base/go.mod" "$base/main.go" "$work/"
(cd "$work"; go mod edit "-replace=github.com/0cv/herdr-mobile-relay=$BRIDGE_SOURCE"; go run -mod=mod .)
bun "$base/export-check.ts"
