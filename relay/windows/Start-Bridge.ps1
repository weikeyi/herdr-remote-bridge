# Run in a separate PowerShell process; environment changes are process-local.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$ConfigPath)
. (Join-Path $PSScriptRoot 'Bridge.Common.ps1')
$identity = Get-BridgeIdentity
$config = Read-BridgeConfig $ConfigPath
if (!(Test-Path -LiteralPath $config.dataDir -PathType Container)) { throw 'Install Bridge before starting it.' }
Assert-BridgePathOwner $config.dataDir $identity.User.Value
Assert-BridgeCredential $config.registrationCredentialPath $identity.User.Value
Assert-BridgeCredential $config.relayKeyCredentialPath $identity.User.Value
Set-BridgeEnvironment $config
Set-Location -LiteralPath $config.dataDir
# No terminal content, credentials or pairing links are redirected to disk.
& $config.relayPath serve
exit $LASTEXITCODE
