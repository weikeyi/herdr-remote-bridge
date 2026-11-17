[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Start', 'Stop', 'Status', 'Uninstall')][string]$Action,
    [Parameter(Mandatory = $true)][string]$ConfigPath
)
. (Join-Path $PSScriptRoot 'Bridge.Common.ps1')
Invoke-BridgeTaskAction $Action $ConfigPath $PSScriptRoot
