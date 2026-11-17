Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-BridgeIdentity {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if ($identity.IsSystem -or $identity.User.Value -in @('S-1-5-19', 'S-1-5-20')) {
        throw 'Run Bridge as your normal Windows user, not a service account.'
    }
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Open a normal, non-elevated PowerShell window. Administrator mode is not supported.'
    }
    return $identity
}

function Get-BridgeFullPath([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Contains('"') -or $Value.Contains("`n") -or $Value.Contains("`r")) {
        throw 'A nonempty local absolute path without quotes or line breaks is required.'
    }
    if ($Value -notmatch '^[A-Za-z]:\\') { throw 'Use an absolute local drive path, not a network share or relative path.' }
    return [IO.Path]::GetFullPath($Value)
}

function Read-BridgeConfig([string]$Path) {
    $Path = Get-BridgeFullPath $Path
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Bridge configuration file is missing.' }
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $required = @('schemaVersion', 'relayPath', 'gatewayUrl', 'phoneAppUrl', 'corePipe', 'dataDir', 'registrationCredentialPath', 'relayKeyCredentialPath')
    foreach ($name in $required) {
        if ($name -notin $config.PSObject.Properties.Name) { throw "Missing configuration field: $name" }
    }
    foreach ($name in $config.PSObject.Properties.Name) {
        if ($name -notin $required) { throw "Unknown configuration field: $name" }
    }
    if ($config.schemaVersion -ne 1) { throw 'Unsupported Bridge configuration version.' }
    foreach ($name in @('relayPath', 'dataDir', 'registrationCredentialPath', 'relayKeyCredentialPath')) {
        $config.$name = Get-BridgeFullPath $config.$name
    }
    $expectedData = Join-Path $env:LOCALAPPDATA 'HerdrRemoteBridge'
    if ($config.dataDir -ne [IO.Path]::GetFullPath($expectedData)) {
        throw 'dataDir must be the dedicated current-user LOCALAPPDATA\HerdrRemoteBridge directory.'
    }
    $dataItem = Get-Item -LiteralPath $config.dataDir -ErrorAction SilentlyContinue
    if ($dataItem -and ($dataItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'The Bridge data directory must not be a junction or symbolic link.'
    }
    foreach ($name in @('relayPath', 'registrationCredentialPath', 'relayKeyCredentialPath')) {
        if (!(Test-Path -LiteralPath $config.$name -PathType Leaf)) { throw "Required file is missing: $name" }
    }
    if ([IO.Path]::GetExtension($config.relayPath) -ne '.exe') { throw 'relayPath must identify the verified relay .exe.' }
    $config.gatewayUrl = ConvertTo-BridgeOrigin $config.gatewayUrl 'wss'
    $config.phoneAppUrl = ConvertTo-BridgeOrigin $config.phoneAppUrl 'https'
    if ($config.corePipe -notmatch '^\\\\\.\\pipe\\[^\\/]+$') { throw 'corePipe must be a local Windows Named Pipe.' }
    return $config
}

function Set-BridgeEnvironment($Config) {
    # Never inherit transport overrides, plaintext tokens or optional integrations.
    Get-ChildItem Env: | Where-Object { $_.Name -like 'HERDR_*' -or $_.Name -like 'HC_*' } | ForEach-Object {
        [Environment]::SetEnvironmentVariable($_.Name, $null, 'Process')
    }
    $values = @{
        HERDR_RELAY_BACKEND = 'hc'
        HERDR_RELAY_HOST = '127.0.0.1'
        HERDR_RELAY_PORT = '8377'
        HERDR_RELAY_ENV = (Join-Path $Config.dataDir 'relay.env')
        HERDR_GATEWAY_URL = $Config.gatewayUrl
        HERDR_ALLOWED_ORIGINS = $Config.phoneAppUrl
        HERDR_PHONE_APP_URL = $Config.phoneAppUrl
        HERDR_TRANSPORT_FORCE_RELAY = '1'
        HERDR_REACHABILITY_PORT_MAPPING = '0'
        HERDR_BOOTSTRAP_ROLE = 'reader'
        HERDR_RELAY_TOKEN_FILE = $Config.relayKeyCredentialPath
        HERDR_GATEWAY_REGISTRATION_TOKEN_FILE = $Config.registrationCredentialPath
        HERDR_RELAY_LOG_LEVEL = 'warn'
        HC_SOCKET_PATH = $Config.corePipe
        XDG_CONFIG_HOME = (Join-Path $Config.dataDir 'config')
        XDG_DATA_HOME = (Join-Path $Config.dataDir 'data')
        XDG_CACHE_HOME = (Join-Path $Config.dataDir 'cache')
    }
    foreach ($name in $values.Keys) { [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process') }
}

function Assert-BridgeOwnedTask($Task, [string]$OwnerSid, [string]$Executable, [string]$Arguments) {
    if (!$Task) { throw 'Bridge is not installed for this Windows user.' }
    $sid = $Task.Principal.UserId
    if ($sid -notmatch '^S-1-') {
        $sid = (New-Object Security.Principal.NTAccount($sid)).Translate([Security.Principal.SecurityIdentifier]).Value
    }
    if ($Task.Description -ne 'Herdr Remote Bridge current-user launcher v1' -or $sid -ne $OwnerSid -or
        $Task.Principal.RunLevel -ne 'Limited' -or $Task.Principal.LogonType -ne 'Interactive' -or
        @($Task.Actions).Count -ne 1 -or $Task.Actions[0].Execute -ne $Executable -or
        $Task.Actions[0].Arguments -ne $Arguments) {
        throw 'The task does not match this Bridge installation. No task was changed.'
    }
}


function Assert-BridgePathOwner([string]$Path, [string]$OwnerSid) {
    $item = Get-Item -LiteralPath $Path
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Bridge paths must not be reparse points.' }
    $acl = Get-Acl -LiteralPath $Path
    $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($owner -ne $OwnerSid) { throw 'Bridge path is not owned by the current Windows user. Nothing was changed.' }
}

function Assert-BridgeCredential([string]$Path, [string]$OwnerSid) {
    Assert-BridgePathOwner $Path $OwnerSid
    $acl = Get-Acl -LiteralPath $Path
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if (!$acl.AreAccessRulesProtected -or $rules.Count -ne 1 -or
        $rules[0].IdentityReference.Value -ne $OwnerSid -or $rules[0].AccessControlType -ne 'Allow') {
        throw 'Credential permissions must be protected and current-user-only; recreate it using protect-secret.'
    }
    $expected = [Text.Encoding]::ASCII.GetBytes("HERDR-DPAPI-CURRENTUSER-v1`0")
    $stream = [IO.File]::OpenRead($Path)
    try {
        $actual = New-Object byte[] $expected.Length
        if ($stream.Length -le $expected.Length -or $stream.Read($actual, 0, $actual.Length) -ne $expected.Length -or
            [BitConverter]::ToString($actual) -ne [BitConverter]::ToString($expected)) {
            throw 'Credential file is not a protected Bridge credential.'
        }
    } finally { $stream.Dispose() }
    # Go opens with no reparse following and revalidates ACL/owner before DPAPI decryption.
}

function Invoke-BridgeTaskAction([string]$Action, [string]$ConfigPath, [string]$ScriptsRoot) {
$identity = Get-BridgeIdentity
$ConfigPath = Get-BridgeFullPath $ConfigPath
$taskName = 'HerdrRemoteBridge-' + $identity.User.Value
$description = 'Herdr Remote Bridge current-user launcher v1'
$launcher = Join-Path $ScriptsRoot 'Start-Bridge.ps1'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -File "' + $launcher + '" -ConfigPath "' + $ConfigPath + '"'
$task = Get-ScheduledTask -TaskPath '\' -TaskName $taskName -ErrorAction SilentlyContinue


switch ($Action) {
    'Install' {
        if ($task) { throw 'A task with this name already exists. Inspect it; installation never overwrites a task.' }
        $config = Read-BridgeConfig $ConfigPath
        # Inspect only the selected binary. Never download or replace it automatically.
        $versionText = & $config.relayPath version --json
        if ($LASTEXITCODE -ne 0) { throw 'Relay version check failed.' }
        $version = $versionText | ConvertFrom-Json
        if ('hc' -notin $version.backends -or $version.target -notlike 'windows/*') { throw 'A Windows hc-capable relay binary is required.' }
        Assert-BridgeCredential $config.registrationCredentialPath $identity.User.Value
        Assert-BridgeCredential $config.relayKeyCredentialPath $identity.User.Value
        if (Test-Path -LiteralPath $config.dataDir) {
            Assert-BridgePathOwner $config.dataDir $identity.User.Value
        }
        $null = New-Item -ItemType Directory -Path $config.dataDir -Force
        $acl = New-Object Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true, $false)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity.User, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
        Set-Acl -LiteralPath $config.dataDir -AclObject $acl
        $taskAction = New-ScheduledTaskAction -Execute $powershell -Argument $arguments -WorkingDirectory $ScriptsRoot
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $identity.User.Value
        $principal = New-ScheduledTaskPrincipal -UserId $identity.User.Value -LogonType Interactive -RunLevel Limited
        $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        # No -Force: a concurrent installation or foreign task fails without replacement.
        $null = Register-ScheduledTask -TaskName $taskName -TaskPath '\' -Action $taskAction -Trigger $trigger -Principal $principal -Settings $settings -Description $description
        Write-Output 'Installed for your next sign-in. Use Start to launch now. Core/Desktop were not changed.'
    }
    'Start' {
        Assert-BridgeOwnedTask $task $identity.User.Value $powershell $arguments
        $config = Read-BridgeConfig $ConfigPath
        Assert-BridgeCredential $config.registrationCredentialPath $identity.User.Value
        Assert-BridgeCredential $config.relayKeyCredentialPath $identity.User.Value
        Start-ScheduledTask -TaskName $taskName -TaskPath '\'
        Write-Output 'Start requested. Use Status and local Bridge health to verify readiness.'
    }
    'Stop' {
        Assert-BridgeOwnedTask $task $identity.User.Value $powershell $arguments
        Stop-ScheduledTask -TaskName $taskName -TaskPath '\'
        Write-Output 'Bridge task stop requested. Core/Desktop were not stopped.'
    }
    'Status' {
        if (!$task) { Write-Output 'Not installed'; break }
        Assert-BridgeOwnedTask $task $identity.User.Value $powershell $arguments
        $info = Get-ScheduledTaskInfo -TaskName $taskName -TaskPath '\'
        [PSCustomObject]@{ Task = $taskName; State = $task.State; LastResult = $info.LastTaskResult; LastRun = $info.LastRunTime }
    }
    'Uninstall' {
        Assert-BridgeOwnedTask $task $identity.User.Value $powershell $arguments
        Stop-ScheduledTask -TaskName $taskName -TaskPath '\'
        Unregister-ScheduledTask -TaskName $taskName -TaskPath '\' -Confirm:$false
        Write-Output 'Removed this user''s Bridge task only. Configuration, credentials and Core/Desktop are unchanged.'
    }
}

}

function ConvertTo-BridgeOrigin([string]$Value, [string]$Scheme) {
    $uri = $null
    if (![Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne $Scheme -or !$uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') {
        throw 'A secure origin without credentials, path, query or fragment is required.'
    }
    return $uri.GetLeftPart([UriPartial]::Authority).ToLowerInvariant()
}
