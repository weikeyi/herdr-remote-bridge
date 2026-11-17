Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-BridgeBinaryVersion([string]$Path) {
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $Path
    $start.Arguments = 'version --json'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(10000)) {
            $process.Kill()
            throw 'Candidate version check timed out.'
        }
        if ($process.ExitCode -ne 0) { throw 'Candidate version check failed.' }
        $result = $output.GetAwaiter().GetResult() | ConvertFrom-Json
        $null = $errors.GetAwaiter().GetResult()
    } finally { $process.Dispose() }
    if (!$result.version -or !$result.revision -or $result.target -notlike 'windows/*' -or 'hc' -notin $result.backends) {
        throw 'Candidate must be a compatible Windows hc relay with version and revision.'
    }
    return $result
}
function Get-BridgeLiveStatus($Config) {
    Set-BridgeEnvironment $Config
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $text = & $Config.relayPath bridge status 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return ($text | ConvertFrom-Json)
    } finally { $ErrorActionPreference = $saved }
}
function Test-BridgeExecutableRunning([string]$Path) {
    # Inspect, never kill: an orphaned relay child blocks replacement even if its pipe is unresponsive.
    $name = [IO.Path]::GetFileName($Path).Replace("'", "''")
    $processes = @(Get-CimInstance -ClassName Win32_Process -Filter ("Name='" + $name + "'") -ErrorAction Stop)
    foreach ($process in $processes) {
        if (!$process.ExecutablePath) { throw 'Cannot establish executable ownership for a running relay process.' }
        if ($process.ExecutablePath -eq $Path) { return $true }
    }
    return $false
}
function Wait-BridgeStopped($Config, [string]$ConfigPath, [string]$ScriptsRoot, [int]$ExpectedProcessId = 0) {
    for ($i = 0; $i -lt 8; $i++) {
        $state = Invoke-BridgeTaskAction Status $ConfigPath $ScriptsRoot
        $pidAlive = $ExpectedProcessId -gt 0 -and @(Get-CimInstance -ClassName Win32_Process -Filter ("ProcessId=" + $ExpectedProcessId) -ErrorAction Stop).Count -gt 0
        if (!$pidAlive -and $state.State -ne 'Running' -and !(Get-BridgeLiveStatus $Config) -and !(Test-BridgeExecutableRunning $Config.relayPath)) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'Cannot confirm the relay stopped. No binary/config switch is safe.'
}
function Wait-BridgeReady($Config, $Version) {
    for ($i = 0; $i -lt 12; $i++) {
        $state = Get-BridgeLiveStatus $Config
        if ($state -and $state.ready -eq $true -and $state.backend -eq 'hc' -and $state.relay_only -eq $true -and $state.version -eq $Version.version -and $state.revision -eq $Version.revision) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'The requested relay version did not become ready.'
}
function Set-BridgePrivateFile([string]$Path, $Identity) {
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetOwner($Identity.User)
    $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($Identity.User, 'FullControl', 'Allow')))
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Write-BridgeConfigAtomic([string]$ConfigPath, [string]$Text, [string]$BackupPath, $Identity) {
    $temporary = $ConfigPath + '.' + [guid]::NewGuid().ToString('N') + '.pending'
    [IO.File]::WriteAllText($temporary, $Text, (New-Object Text.UTF8Encoding($false)))
    Set-BridgePrivateFile $temporary $Identity
    # Destination and temporary are on the same volume. Never replace in-place bytes.
    [IO.File]::Replace($temporary, $ConfigPath, $BackupPath)
}
function Stage-BridgeBinary([string]$CandidatePath, [string]$OldPath, [string]$Hash, $Identity) {
    $directory = Split-Path -Parent $OldPath
    Assert-BridgePathOwner $directory $Identity.User.Value
    $destination = Join-Path $directory ('herdr-mobile-relay-' + $Hash.ToLowerInvariant() + '.exe')
    if (!(Test-Path -LiteralPath $destination)) {
        # Copy without overwrite. Existing release files are immutable to this updater.
        [IO.File]::Copy($CandidatePath, $destination, $false)
        Set-BridgePrivateFile $destination $Identity
    }
    Assert-BridgePathOwner $destination $Identity.User.Value
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $Hash) { throw 'Staged executable hash mismatch.' }
    return $destination
}
function Invoke-BridgeUpdate([string]$ConfigPath, [string]$CandidatePath, [string]$ExpectedSHA256, [string]$ScriptsRoot) {
    $identity = Get-BridgeIdentity
    $ConfigPath = Get-BridgeFullPath $ConfigPath
    $CandidatePath = Get-BridgeFullPath $CandidatePath
    if ($ExpectedSHA256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'An exact expected SHA-256 is required.' }
    $config = Read-BridgeConfig $ConfigPath
    Assert-BridgePathOwner $config.dataDir $identity.User.Value
    Assert-BridgePathOwner $ConfigPath $identity.User.Value
    if ((Split-Path -Parent $ConfigPath) -ne $config.dataDir) { throw 'Update requires the config directly inside the private Bridge data directory.' }
    Assert-BridgeCredential $config.registrationCredentialPath $identity.User.Value
    Assert-BridgeCredential $config.relayKeyCredentialPath $identity.User.Value
    Assert-BridgePathOwner $CandidatePath $identity.User.Value
    if ((Get-FileHash -LiteralPath $CandidatePath -Algorithm SHA256).Hash -ne $ExpectedSHA256) { throw 'Candidate hash mismatch. Nothing was stopped.' }
    $candidateVersion = Get-BridgeBinaryVersion $CandidatePath
    $oldVersion = Get-BridgeBinaryVersion $config.relayPath
    $taskState = Invoke-BridgeTaskAction Status $ConfigPath $ScriptsRoot
    if ($taskState -is [string]) { throw 'Install the Bridge task before updating.' }
    $wasRunning = $taskState.State -eq 'Running'
    # A stray/manual relay is not ours to kill, even if the scheduled task is stopped.
    if (!$wasRunning -and (Get-BridgeLiveStatus $config)) { throw 'An unmanaged relay is running. Stop it manually before updating.' }
    $lock = [IO.File]::Open($ConfigPath + '.update.lock', [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        Set-BridgePrivateFile $ConfigPath $identity
        Invoke-BridgeUpdateLocked $config $ConfigPath $CandidatePath $ExpectedSHA256 $candidateVersion $oldVersion $wasRunning $identity $ScriptsRoot
    } finally { $lock.Dispose() }
}
function Invoke-BridgeUpdateLocked($Config, [string]$ConfigPath, [string]$CandidatePath, [string]$Hash, $CandidateVersion, $OldVersion, [bool]$WasRunning, $Identity, [string]$ScriptsRoot) {
    $oldProcessId = 0
    $newProcessId = 0
    if ($WasRunning) {
        $before = Get-BridgeLiveStatus $Config
        if (!$before -or !$before.process_id -or [int]$before.process_id -le 0) {
            throw 'Cannot identify the running relay process. Stop it manually before updating.'
        }
        $oldProcessId = [int]$before.process_id
    }
    $oldText = Get-Content -LiteralPath $ConfigPath -Raw
    if (($oldText | ConvertFrom-Json).relayPath -ne $Config.relayPath) { throw 'Configuration changed during update preparation; retry without concurrent changes.' }
    $staged = Stage-BridgeBinary $CandidatePath $Config.relayPath $Hash $Identity
    $next = $oldText | ConvertFrom-Json
    $next.relayPath = $staged
    $backup = $ConfigPath + '.' + [guid]::NewGuid().ToString('N') + '.backup'
    $changed = $false
    $stopRequested = $false
    try {
        if ($WasRunning) {
            $stopRequested = $true
            Invoke-BridgeTaskAction Stop $ConfigPath $ScriptsRoot | Out-Null
        }
        Wait-BridgeStopped $Config $ConfigPath $ScriptsRoot $oldProcessId
        Write-BridgeConfigAtomic $ConfigPath ($next | ConvertTo-Json) $backup $Identity
        $changed = $true
        if ($WasRunning) {
            Invoke-BridgeTaskAction Start $ConfigPath $ScriptsRoot | Out-Null
            $candidateStatus = Get-BridgeLiveStatus $next
            if ($candidateStatus -and $candidateStatus.process_id) { $newProcessId = [int]$candidateStatus.process_id }
            Wait-BridgeReady $next $CandidateVersion
        }
        [PSCustomObject]@{ Updated = $true; Version = $CandidateVersion.version; Revision = $CandidateVersion.revision; Running = $WasRunning; ConfigBackup = $backup; PreviousExecutable = $Config.relayPath }
    } catch {
        $failure = $_
        try {
            if ($changed) {
                $candidateStatus = Get-BridgeLiveStatus $next
                if ($candidateStatus -and $candidateStatus.process_id) { $newProcessId = [int]$candidateStatus.process_id }
                Invoke-BridgeTaskAction Stop $ConfigPath $ScriptsRoot | Out-Null
                Wait-BridgeStopped $next $ConfigPath $ScriptsRoot $newProcessId
                Write-BridgeConfigAtomic $ConfigPath $oldText ($backup + '.failed-update') $Identity
            }
            if ($WasRunning -and $stopRequested) {
                Invoke-BridgeTaskAction Start $ConfigPath $ScriptsRoot | Out-Null
                Wait-BridgeReady $Config $OldVersion
            }
        } catch {
            throw 'Update failed and rollback could not be verified. Keep all backups; inspect the task and local status before retrying. Do not resend uncertain prompts.'
        }
        throw ('Update failed; previous configuration/running state restored and verified where running: ' + $failure.Exception.Message)
    }
}
