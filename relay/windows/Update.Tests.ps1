BeforeAll {
    . (Join-Path $PSScriptRoot 'Bridge.Common.ps1')
    . (Join-Path $PSScriptRoot 'Bridge.Update.ps1')
}
Describe 'Explicit Bridge update state machine (OS mocked)' {
    BeforeEach {
        $config = [PSCustomObject]@{ relayPath = 'old.exe' }
        $oldVersion = [PSCustomObject]@{ version = '1'; revision = 'old' }
        $newVersion = [PSCustomObject]@{ version = '2'; revision = 'new' }
        $identity = [PSCustomObject]@{ User = [PSCustomObject]@{ Value = 'S-1-5-21-1' } }
        Mock Get-BridgeLiveStatus { [PSCustomObject]@{ process_id = 4242 } }
        Mock Get-Content { '{"relayPath":"old.exe"}' }
        Mock Stage-BridgeBinary { 'new.exe' }
        Mock Invoke-BridgeTaskAction {}
        Mock Wait-BridgeStopped {}
        Mock Write-BridgeConfigAtomic {}
        Mock Wait-BridgeReady {}
    }
    It 'stages a verified candidate and restarts only the previously running task' {
        $result = Invoke-BridgeUpdateLocked $config config.json candidate.exe ('a' * 64) $newVersion $oldVersion $true $identity scripts
        $result.Updated | Should -BeTrue
        $result.Running | Should -BeTrue
        Should -Invoke Invoke-BridgeTaskAction -Times 1 -ParameterFilter { $Action -eq 'Stop' }
        Should -Invoke Invoke-BridgeTaskAction -Times 1 -ParameterFilter { $Action -eq 'Start' }
        Should -Invoke Wait-BridgeReady -Times 1 -ParameterFilter { $Version.revision -eq 'new' }
    }
    It 'preserves a stopped task without starting or stopping it' {
        $result = Invoke-BridgeUpdateLocked $config config.json candidate.exe ('a' * 64) $newVersion $oldVersion $false $identity scripts
        $result.Running | Should -BeFalse
        Should -Invoke Invoke-BridgeTaskAction -Times 0
        Should -Invoke Wait-BridgeReady -Times 0
    }
    It 'fails before touching the task when candidate staging fails' {
        Mock Stage-BridgeBinary { throw 'hash mismatch' }
        { Invoke-BridgeUpdateLocked $config config.json candidate.exe ('a' * 64) $newVersion $oldVersion $true $identity scripts } | Should -Throw '*hash mismatch*'
        Should -Invoke Invoke-BridgeTaskAction -Times 0
        Should -Invoke Write-BridgeConfigAtomic -Times 0
    }
    It 'restores config and restarts old version if candidate readiness fails' {
        Mock Wait-BridgeReady { if ($Version.revision -eq 'new') { throw 'new build failed' } }
        { Invoke-BridgeUpdateLocked $config config.json candidate.exe ('a' * 64) $newVersion $oldVersion $true $identity scripts } | Should -Throw '*previous configuration*'
        Should -Invoke Write-BridgeConfigAtomic -Times 2
        Should -Invoke Wait-BridgeReady -Times 1 -ParameterFilter { $Version.revision -eq 'old' }
    }
    It 'reports an uncertain rollback without claiming success' {
        Mock Wait-BridgeReady { throw 'still unavailable' }
        { Invoke-BridgeUpdateLocked $config config.json candidate.exe ('a' * 64) $newVersion $oldVersion $true $identity scripts } | Should -Throw '*rollback could not be verified*'
    }
    It 'never swaps config while stopping cannot be verified' {
        Mock Wait-BridgeStopped { throw 'child still running' }
        { Invoke-BridgeUpdateLocked $config config.json candidate.exe ('a' * 64) $newVersion $oldVersion $true $identity scripts } | Should -Throw '*previous configuration*'
        Should -Invoke Write-BridgeConfigAtomic -Times 0
    }
}
Describe 'Update rejects an incorrect supplied checksum before stopping anything' {
    BeforeAll {
        Mock Get-BridgeIdentity { [PSCustomObject]@{ User = [PSCustomObject]@{ Value = 'S-1-5-21-1' } } }
        Mock Get-BridgeFullPath { $Value }
        Mock Read-BridgeConfig { [PSCustomObject]@{ dataDir = '/private'; registrationCredentialPath = '/private/gateway'; relayKeyCredentialPath = '/private/key' } }
        Mock Assert-BridgePathOwner {}
        Mock Assert-BridgeCredential {}
        Mock Get-FileHash { [PSCustomObject]@{ Hash = 'b' * 64 } }
        Mock Invoke-BridgeTaskAction {}
        Mock Get-BridgeBinaryVersion {}
    }
    It 'rejects wrong hash without executing the candidate or touching task state' {
        { Invoke-BridgeUpdate '/private/config.json' '/candidate.exe' ('a' * 64) '/scripts' } | Should -Throw '*hash mismatch*'
        Should -Invoke Get-BridgeBinaryVersion -Times 0
        Should -Invoke Invoke-BridgeTaskAction -Times 0
    }
}

Describe 'Shutdown guard retains process evidence even when the pipe is gone' {
    BeforeEach {
        $config = [PSCustomObject]@{ relayPath = 'relay.exe' }
        Mock Invoke-BridgeTaskAction { [PSCustomObject]@{ State = 'Ready' } }
        Mock Get-BridgeLiveStatus { $null }
        Mock Test-BridgeExecutableRunning { $false }
        Mock Start-Sleep {}
        # Shim the Windows-only cmdlet so Linux Pester can mock it.
        if (!(Get-Command Get-CimInstance -ErrorAction SilentlyContinue)) {
            function global:Get-CimInstance { param($ClassName, $Filter, $ErrorAction) }
        }
        Mock Get-CimInstance { @() }
    }
    It 'blocks a lingering captured PID even after task and pipe stop' {
        Mock Get-CimInstance { [PSCustomObject]@{ ProcessId = 4242 } }
        { Wait-BridgeStopped $config config scripts 4242 } | Should -Throw '*Cannot confirm*'
    }
    It 'blocks an unresponsive candidate found by exact executable path' {
        Mock Test-BridgeExecutableRunning { $true }
        { Wait-BridgeStopped $config config scripts 0 } | Should -Throw '*Cannot confirm*'
    }
    It 'accepts shutdown only after PID path task and management all clear' {
        { Wait-BridgeStopped $config config scripts 4242 } | Should -Not -Throw
        Should -Invoke Get-CimInstance -Times 1
        Should -Invoke Test-BridgeExecutableRunning -Times 1
    }
}
