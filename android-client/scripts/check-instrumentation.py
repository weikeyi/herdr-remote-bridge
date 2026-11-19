#!/usr/bin/env python3
"""Fail closed unless all eleven expected native tests completed successfully."""
import pathlib
import re
import sys

EXPECTED = {('dev.herdrbridge.protocol.ChunkCodecTest', 'timeoutAndClockFailureAreFatal'), ('dev.herdrbridge.nativeclient.DeviceVaultTest', 'roundTripWrongAuthorityCorruptionAndLostKeyFailClosed'), ('dev.herdrbridge.protocol.ChunkCodecTest', 'closeAndOversizeCarrierFail'), ('dev.herdrbridge.nativeclient.MainActivitySmokeTest', 'backgroundAndRecreationClearTransientInvitationWithoutAutoConnect'), ('dev.herdrbridge.nativeclient.MainActivitySmokeTest', 'restoredAuthorizationRequiresExactOriginAndValidSavedVault'), ('dev.herdrbridge.protocol.ChunkCodecTest', 'zeroAndMaximumMessagesAndNewAssembly'), ('dev.herdrbridge.protocol.ChunkCodecTest', 'rejectsSequenceFlagsLengthAndVersion'), ('dev.herdrbridge.nativeclient.AndroidCryptoTest', 'originalGoldenAndNegativeChecksOnAndroid'), ('dev.herdrbridge.protocol.ChunkCodecTest', 'matchesActualTypescriptExporterVector'), ('dev.herdrbridge.nativeclient.MainActivitySmokeTest', 'nativeInitialUiAndInvalidActionsNeverCreateConnection'), ('dev.herdrbridge.nativeclient.MainActivitySmokeTest', 'foregroundRecoveryClosesOldClientAndDisconnectStopsRecovery')}


def validate(text):
    if re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|shortMsg=|FAILURES!!!|Process crashed", text):
        raise ValueError("Instrumentation failed or crashed")
    if re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.M) != ["-1"]:
        raise ValueError("Missing or unexpected final instrumentation result")
    if len(re.findall(r"^OK \(11 tests\)\s*$", text, re.M)) != 1:
        raise ValueError("Expected exactly eleven successful tests")
    fields = {}
    started, passed = set(), set()
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep:
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.split(": ", 1)[1].strip()
            test = (fields.get("class"), fields.get("test"))
            if test not in EXPECTED or fields.get("numtests") != "11":
                raise ValueError("Unexpected test identity or test count")
            if code == "1" and test not in started:
                started.add(test)
            elif code == "0" and test in started and test not in passed:
                passed.add(test)
            else:
                raise ValueError("Failure, skip, duplicate or invalid test status")
            fields = {}
    if started != EXPECTED or passed != EXPECTED:
        raise ValueError("Not all expected tests started and passed")


if __name__ == "__main__":
    try:
        validate(pathlib.Path(sys.argv[1]).read_text())
    except (ValueError, OSError, IndexError) as exc:
        sys.exit(f"Instrumentation acceptance failed: {exc}")
    print("PASS: all expected Android crypto/chunk/vault tests completed")
