# Explicit update or rollback to a locally supplied, hash-verified executable.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigPath,
    [Parameter(Mandatory = $true)][string]$CandidatePath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSHA256
)
. (Join-Path $PSScriptRoot 'Bridge.Common.ps1')
. (Join-Path $PSScriptRoot 'Bridge.Update.ps1')
Invoke-BridgeUpdate $ConfigPath $CandidatePath $ExpectedSHA256 $PSScriptRoot
