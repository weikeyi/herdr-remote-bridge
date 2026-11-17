[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RelayPath,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][ValidateSet('RelayKey', 'GatewayRegistration')][string]$Kind
)
. (Join-Path $PSScriptRoot 'Bridge.Common.ps1')
$null = Get-BridgeIdentity
$RelayPath = Get-BridgeFullPath $RelayPath
$OutputPath = Get-BridgeFullPath $OutputPath
if (!(Test-Path -LiteralPath $RelayPath -PathType Leaf)) { throw 'Verified relay executable is missing.' }
if (Test-Path -LiteralPath $OutputPath) { throw 'Credential already exists. This helper never replaces it.' }
$secret = $null
$buffer = [IntPtr]::Zero
$process = $null
try {
    if ($Kind -eq 'GatewayRegistration') {
        $secret = Read-Host 'Private Gateway registration token (not printed or saved in plaintext)' -AsSecureString
        $buffer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($buffer)
        if ([string]::IsNullOrWhiteSpace($plain) -or $plain.Length -gt 4096) { throw 'Registration token must be 1 to 4096 characters.' }
    }
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $RelayPath
    $start.Arguments = 'protect-secret --output "' + $OutputPath + '"'
    if ($Kind -eq 'RelayKey') { $start.Arguments += ' --generate-relay-key' }
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    # A short-lived child stdin is the only plaintext boundary, never argv/env/disk.
    if ($Kind -eq 'GatewayRegistration') { $process.StandardInput.Write($plain) }
    $process.StandardInput.Close()
    $plain = $null
    $null = $process.StandardOutput.ReadToEnd()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Credential protection failed; no secret was printed. Check the output location and relay build.' }
    Write-Output 'Protected credential created for this Windows user. Keep it private.'
} finally {
    $plain = $null
    if ($buffer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($buffer) }
    if ($secret) { $secret.Dispose() }
    if ($process) { $process.Dispose() }
}
