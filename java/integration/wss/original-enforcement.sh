#!/usr/bin/env bash
set -euo pipefail
: "${RELAY_SOURCE:?Point at the audited relay source checkout}"
# Runs original server/coordinator/store tests, unchanged, separately from the
# synthetic application adapter. No running Core, terminal or credentials used.
cd "$RELAY_SOURCE"
status=0
go test -buildvcs=false -count=1 -v ./internal/app -run '^(TestAuthorizeAuthenticatedIdentity|TestValidateExactPaneTargetBindsCurrentTerminalGenerationAndAgentSession|TestManualTerminalTextUsesCentralMutationAdmission)$' || status=1
go test -buildvcs=false -count=1 -v ./internal/coordinator -run '^(TestTerminalTextPolicy|TestTerminalTextLiteralDispatchAndIdempotency|TestTerminalTextLedgerUsesKeyedNonReversibleConflictToken|TestTerminalTextUncertainDeliveryIsNotReplayed|TestTerminalTextRechecksDispatchAuthorization|TestTerminalTextRejectsControlsAndKeysBeforeDispatch|TestBackendTerminalReplacementInvalidatesQueuedTarget)$' || status=1
go test -buildvcs=false -count=1 -v ./internal/deviceauth -run '^TestAuthorizeCredentialRejectsRevokedAndReplacedVersions$' || status=1
exit "$status"
