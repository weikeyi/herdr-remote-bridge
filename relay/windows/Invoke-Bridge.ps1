# Invoke through a separate PowerShell process, not dot-sourced into your shell.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Pair', 'Devices', 'Revoke', 'Status')][string]$Action,
    [Parameter(Mandatory = $true)][string]$ConfigPath,
    [ValidateSet('reader', 'controller')][string]$Role = 'reader',
    [string]$Name = 'Phone',
    [string]$DeviceId
)
. (Join-Path $PSScriptRoot 'Bridge.Common.ps1')
$identity = Get-BridgeIdentity
$config = Read-BridgeConfig $ConfigPath
Assert-BridgePathOwner $config.dataDir $identity.User.Value
Assert-BridgeCredential $config.registrationCredentialPath $identity.User.Value
Assert-BridgeCredential $config.relayKeyCredentialPath $identity.User.Value
Set-BridgeEnvironment $config
$command = @('bridge', $Action.ToLowerInvariant())
if ($Action -eq 'Pair') {
    $command += @('--origin', $config.phoneAppUrl, '--role', $Role, '--name', $Name)
} elseif ($Action -eq 'Revoke') {
    if ([string]::IsNullOrWhiteSpace($DeviceId)) { throw 'Revoke requires -DeviceId from Devices.' }
    $command += @('--device-id', $DeviceId)
}
# Pair output is a one-use invitation secret. Do not transcript or redirect it.
& $config.relayPath @command
exit $LASTEXITCODE
