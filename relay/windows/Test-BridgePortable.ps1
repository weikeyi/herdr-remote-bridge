# Runs actual lifecycle/ownership functions with in-memory OS mocks, never a task.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Bridge.Common.ps1')
$script:checks = 0
function Check([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
    $script:checks++
}
function Throws([scriptblock]$Block) {
    $threw = $false
    try { & $Block } catch { $threw = $true }
    Check $threw 'Expected a fail-closed rejection.'
}
Get-ChildItem $PSScriptRoot -Filter '*.ps1' | ForEach-Object {
    $tokens = $null; $errors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$tokens, [ref]$errors)
    Check ($errors.Count -eq 0) ('Syntax error: ' + $_.Name)
}
Throws { Get-BridgeFullPath 'relative.exe' }
Throws { Get-BridgeFullPath '\\remote\share\relay.exe' }
Throws { Get-BridgeFullPath 'C:\relay.exe" -Command injected' }
$owner = 'S-1-5-21-111-222-333-1001'
function New-TestTask {
    [PSCustomObject]@{
        Description = 'Herdr Remote Bridge current-user launcher v1'
        Principal = [PSCustomObject]@{ UserId = $owner; RunLevel = 'Limited'; LogonType = 'Interactive' }
        Actions = @([PSCustomObject]@{ Execute = '/mock/System32/WindowsPowerShell/v1.0/powershell.exe'; Arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -File "/scripts/Start-Bridge.ps1" -ConfigPath "C:\config.json"' })
        State = 'Ready'
    }
}
$task = New-TestTask
Assert-BridgeOwnedTask $task $owner $task.Actions[0].Execute $task.Actions[0].Arguments
$script:checks++
foreach ($field in @('RunLevel', 'LogonType', 'UserId')) {
    $task = New-TestTask
    $task.Principal.$field = if ($field -eq 'UserId') { 'S-1-5-21-111-222-333-1002' } else { 'Other' }
    Throws { Assert-BridgeOwnedTask $task $owner '/mock/System32/WindowsPowerShell/v1.0/powershell.exe' 'irrelevant' }
}
$script:task = New-TestTask
$script:calls = @()
$env:SystemRoot = '/mock'
# Replace only platform-bound functions in this test process.
function Get-BridgeIdentity { [PSCustomObject]@{ User = [PSCustomObject]@{ Value = $owner } } }
function Get-BridgeFullPath($Value) { $Value }
function Read-BridgeConfig($Path) { [PSCustomObject]@{ registrationCredentialPath = '/credential1'; relayKeyCredentialPath = '/credential2' } }
function Assert-BridgeCredential($Path, $OwnerSid) { $script:calls += 'credential' }
function Get-ScheduledTask { $script:task }
function Start-ScheduledTask { param($TaskName, $TaskPath); $script:calls += "start:${TaskName}:${TaskPath}" }
function Stop-ScheduledTask { param($TaskName, $TaskPath); $script:calls += "stop:${TaskName}:${TaskPath}" }
function Unregister-ScheduledTask { param($TaskName, $TaskPath, [switch]$Confirm); $script:calls += "remove:${TaskName}:${TaskPath}" }
function Get-ScheduledTaskInfo { [PSCustomObject]@{ LastTaskResult = 0; LastRunTime = [datetime]'2026-01-01' } }
Invoke-BridgeTaskAction Start 'C:\config.json' '/scripts' | Out-Null
Check ($script:calls.Count -eq 3) 'Start must validate both credentials before requesting the task.'
Check ($script:calls[2] -eq "start:HerdrRemoteBridge-${owner}:\") 'Start used an unexpected task.'
$script:calls = @()
Invoke-BridgeTaskAction Stop 'C:\config.json' '/scripts' | Out-Null
Check ($script:calls.Count -eq 1) 'Stop must affect one task only.'
$script:calls = @()
Invoke-BridgeTaskAction Uninstall 'C:\config.json' '/scripts' | Out-Null
Check ($script:calls.Count -eq 2) 'Uninstall must stop and remove only the owned task.'
Check ($script:calls[1] -eq "remove:HerdrRemoteBridge-${owner}:\") 'Uninstall used an unexpected task.'
$script:calls = @()
$script:task.Principal.RunLevel = 'Highest'
Throws { Invoke-BridgeTaskAction Uninstall 'C:\config.json' '/scripts' }
Check ($script:calls.Count -eq 0) 'Foreign task was modified.'
Throws { Invoke-BridgeTaskAction Install 'C:\config.json' '/scripts' }
Check ($script:calls.Count -eq 0) 'Install overwrote an existing task.'
$script:task = $null
Check ((Invoke-BridgeTaskAction Status 'C:\config.json' '/scripts') -eq 'Not installed') 'Missing task status was incorrect.'
Write-Output "Portable PowerShell checks passed: $script:checks (OS interactions mocked)."
