# Windows current-user launcher (development build)

These scripts are prepared, not run on your PC. They require Windows 10/11,
Windows PowerShell 5.1 and the ScheduledTasks module. Do not run as Administrator,
SYSTEM or another service account. This is an interactive current-user logon
task, not a Windows service: it does not run after sign-out. Sleep/network loss
still disconnects the relay. Existing Core and Desktop are never installed,
started, stopped, modified or replaced by these scripts.

## Prepare locally

1. First run the verified binary’s `bridge self-test` on Windows. It tests temporary
   DPAPI storage and a local Named Pipe, without Core, VPS or permanent state.
   Then keep the verified Bridge `.exe`, scripts and matching frontend `web` directory
   in a stable local folder owned by your user. Verify its SHA-256 against the
   delivered build manifest. A `version --json` check validates compatibility,
   not provenance. Do not point the launcher at an arbitrary downloaded binary.
2. Create `$env:LOCALAPPDATA\HerdrRemoteBridge`. This dedicated directory is the
   only accepted `dataDir`; installation makes its ACL current-user-only. Do not
   put unrelated files there and do not use a junction/symlink. ACL changes are
   not reverted by uninstall, so protected credentials stay private.
3. Create secrets locally using the verified binary. The relay key is generated
   inside Go; the VPS registration token is entered at a masked prompt. Neither
   is placed in a command argument, environment variable or plaintext file:

```powershell
$root = Join-Path $env:LOCALAPPDATA 'HerdrRemoteBridge'
New-Item -ItemType Directory -Path $root -Force | Out-Null
.\Protect-BridgeSecret.ps1 -RelayPath 'C:\path\herdr-mobile-relay.exe' -OutputPath "$root\relay.dpapi" -Kind RelayKey
.\Protect-BridgeSecret.ps1 -RelayPath 'C:\path\herdr-mobile-relay.exe' -OutputPath "$root\gateway.dpapi" -Kind GatewayRegistration
```

   DPAPI files belong to this Windows account. Copying them to another account
   or computer is not a credential migration. The helper refuses overwrites.
4. Copy `bridge.example.json` to `$root\bridge.json`, replacing every placeholder
   with actual local paths, the secure VPS origin, and the running Core's exact
   Named Pipe. JSON backslashes must be escaped. No token belongs in this file.
   The example pipe is only a pattern, not an autodetected endpoint. Do not run
   Core to discover it; obtain its configured endpoint from the existing app.
5. Keep PowerShell execution policy unchanged. If your organization blocks these
   unsigned scripts, obtain its review/signature rather than using policy bypass.

## Explicit lifecycle actions

From the scripts directory in a normal PowerShell window:

```powershell
$config = Join-Path $env:LOCALAPPDATA 'HerdrRemoteBridge\bridge.json'
.\Manage-Bridge.ps1 -Action Install -ConfigPath $config
.\Manage-Bridge.ps1 -Action Start -ConfigPath $config
.\Manage-Bridge.ps1 -Action Status -ConfigPath $config
.\Manage-Bridge.ps1 -Action Stop -ConfigPath $config
# Only when you want to remove the logon task:
.\Manage-Bridge.ps1 -Action Uninstall -ConfigPath $config
```

Install registers one `HerdrRemoteBridge-<your SID>` task with Limited privilege
and only your interactive logon trigger. It does not start it immediately. A
name collision fails rather than overwriting a task. Start/Stop/Uninstall verify
the exact owner, principal, launcher and config arguments before acting. A moved
script/config requires removing the old task with its original paths first.
No wildcard task deletion, process-name kill, files deletion or credential
revocation occurs. A failed registration leaves no partially registered replacement;
it may leave the dedicated data directory with its tightened ACL. Retry after
fixing the reported problem. Uninstall preserves all data and credentials.

`Status` reports Task Scheduler state only, not a verified connection to Core or
the VPS. Check the separate local Bridge management status and confirm fresh
phone output before calling the service ready. The launcher writes no terminal
content log and does not replay input. Stopping the task forcibly ends the relay;
never assume an in-flight prompt was not delivered, and do not automatically
resend it. Core/Desktop should remain running.

## Validation boundaries

Portable static tests: `python relay/windows/test_contracts.py`.
On Windows with Pester 5 installed: `Invoke-Pester .\Bridge.Tests.ps1`.
The Pester tests parse scripts and validate paths, origins and task ownership; they do not register tasks.
`pwsh -NoProfile -File .\Test-BridgePortable.ps1` also executes lifecycle functions
with in-memory OS mocks for start, stop, uninstall, collision and missing-task cases.

Windows acceptance still required: create/protect/read DPAPI files as this user;
reject another user; register/start at logon; verify WSS and Core readiness;
stop task and verify relay child exits while Core/Desktop remain; refuse foreign
task collisions; preserve unrelated tasks and files on uninstall; reconnect
without duplicated subscriptions or uncertain-write replay. None of these live
Windows or VPS checks is claimed by the portable static tests.

## Local pairing and device management

Run `Invoke-Bridge.ps1` in a separate PowerShell process (do not dot-source it):

```powershell
.\Invoke-Bridge.ps1 -Action Status -ConfigPath $config
.\Invoke-Bridge.ps1 -Action Pair -ConfigPath $config -Name Phone
# Only when you intentionally authorize phone control:
.\Invoke-Bridge.ps1 -Action Pair -ConfigPath $config -Name Phone -Role controller
.\Invoke-Bridge.ps1 -Action Devices -ConfigPath $config
.\Invoke-Bridge.ps1 -Action Revoke -ConfigPath $config -DeviceId 'ID_FROM_DEVICES'
```

This wrapper sets the same process-local configuration as the running relay.
All results are JSON. Pair defaults to Reader, and its one-use invitation URL is
printed only to the local console. Do not run pairing inside a transcript, save
its output, or share it publicly. Status here is the local management response,
not the similarly named Task Scheduler Status action.

## Explicit executable update or rollback

There are no automatic downloads or unattended updates. `Update-Bridge.ps1`
requires a locally supplied candidate and its expected SHA-256 from a trusted
build manifest. It verifies the Windows/hc version, then copies the candidate
under its hash beside the old executable without overwriting either executable.
The existing adjacent `web` directory is retained; this updates the executable
only, not the separately hosted PWA or its files.

```powershell
.\Update-Bridge.ps1 -ConfigPath $config -CandidatePath 'C:\Downloads\herdr-mobile-relay.exe' -ExpectedSHA256 '64_HEX_DIGITS_FROM_TRUSTED_MANIFEST'
```

Config must be directly in the private Bridge data directory. The updater takes
an exclusive update lock, checks the existing task identity, and stops only that
task if it was running. It captures the running relay PID and refuses to switch while that PID exists,
local management still answers, the task remains running, or Windows reports a
process with the exact relay executable path. Missing process visibility fails
closed. PID reuse conservatively blocks an update; no process is killed by PID. It atomically replaces the same config pathname,
retains a private backup, and restarts only a previously running task. Success for
a running task requires local management to report ready/hc/relay-only and the
candidate's exact version AND revision. A stopped task stays stopped and is not
claimed to have passed runtime readiness.

If candidate readiness fails, the updater stops it, verifies shutdown, restores
the previous config atomically and verifies the old version/revision after
restart. If any rollback step cannot be verified it reports **rollback could not
be verified**, not success. Keep backups and inspect local status before retrying.
Do not resend a prompt whose outcome was uncertain during interruption.

For a deliberate later rollback, invoke the same command with the preserved
`PreviousExecutable` reported by the prior update and that previous build's
trusted SHA-256. Configuration backups and executables are never automatically
deleted. Core/Desktop and unrelated tasks are never changed. Native filesystem
replace, task shutdown/child termination and real rollback remain Windows
acceptance checks; Linux mocks do not prove those OS behaviors.

### Recorded development validation

Pester 5.7.1: `Invoke-Pester .\Bridge.Tests.ps1, .\Update.Tests.ps1`.
The lifecycle/origin suite passed 16 tests with one Windows-only path case skipped
on Linux. Update preflight/state-machine/shutdown-guard tests passed 10 tests using OS mocks:
total Pester result 26 passed, 1 skipped, 0 failures. The standalone portable harness
also passed 27 checks (including parsing the added update scripts). These are not a claim of
successful installation, upgrade, rollback or deployment on your PC.
