# Pester 5. Pure validation tests: never register tasks, start a relay or read secrets.
BeforeAll {
    . (Join-Path $PSScriptRoot 'Bridge.Common.ps1')
}
Describe 'Bridge path validation' {
    It 'rejects relative paths' { { Get-BridgeFullPath 'relay.exe' } | Should -Throw }
    It 'rejects remote shares' { { Get-BridgeFullPath '\\server\share\relay.exe' } | Should -Throw }
    It 'rejects argument injection' { { Get-BridgeFullPath 'C:\relay.exe" -Command bad' } | Should -Throw }
    It 'accepts spaces in absolute paths' -Skip:($env:OS -ne 'Windows_NT') { Get-BridgeFullPath 'C:\My Apps\relay.exe' | Should -Be 'C:\My Apps\relay.exe' }
}
Describe 'Bridge PowerShell syntax' {
    It 'parses each script without errors' {
        Get-ChildItem $PSScriptRoot -Filter '*.ps1' | ForEach-Object {
            $tokens = $null
            $errors = $null
            $null = [Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$tokens, [ref]$errors)
            $errors.Count | Should -Be 0
        }
    }
}

Describe 'Task ownership guard before start stop or uninstall' {
    BeforeEach {
        $owner = 'S-1-5-21-111-222-333-1001'
        $task = [PSCustomObject]@{
            Description = 'Herdr Remote Bridge current-user launcher v1'
            Principal = [PSCustomObject]@{ UserId = $owner; RunLevel = 'Limited'; LogonType = 'Interactive' }
            Actions = @([PSCustomObject]@{ Execute = 'powershell.exe'; Arguments = 'expected args' })
        }
    }
    It 'accepts only the expected current-user installation' {
        { Assert-BridgeOwnedTask $task $owner 'powershell.exe' 'expected args' } | Should -Not -Throw
    }
    It 'rejects a missing task' {
        { Assert-BridgeOwnedTask $null $owner 'powershell.exe' 'expected args' } | Should -Throw
    }
    It 'rejects a task belonging to another user' {
        $task.Principal.UserId = 'S-1-5-21-111-222-333-1002'
        { Assert-BridgeOwnedTask $task $owner 'powershell.exe' 'expected args' } | Should -Throw
    }
    It 'rejects elevated tasks' {
        $task.Principal.RunLevel = 'Highest'
        { Assert-BridgeOwnedTask $task $owner 'powershell.exe' 'expected args' } | Should -Throw
    }
    It 'rejects noninteractive tasks' {
        $task.Principal.LogonType = 'ServiceAccount'
        { Assert-BridgeOwnedTask $task $owner 'powershell.exe' 'expected args' } | Should -Throw
    }
    It 'rejects another executable' {
        $task.Actions[0].Execute = 'other.exe'
        { Assert-BridgeOwnedTask $task $owner 'powershell.exe' 'expected args' } | Should -Throw
    }
    It 'rejects a changed configuration path or launcher argument' {
        $task.Actions[0].Arguments = 'other config'
        { Assert-BridgeOwnedTask $task $owner 'powershell.exe' 'expected args' } | Should -Throw
    }
    It 'rejects additional task actions' {
        $task.Actions += [PSCustomObject]@{ Execute = 'other.exe'; Arguments = '' }
        { Assert-BridgeOwnedTask $task $owner 'powershell.exe' 'expected args' } | Should -Throw
    }
}

Describe 'Bridge origin normalization' {
    It 'removes trailing slash default port and hostname case' {
        ConvertTo-BridgeOrigin 'https://EXAMPLE.com:443/' 'https' | Should -Be 'https://example.com'
        ConvertTo-BridgeOrigin 'wss://EXAMPLE.com:443/' 'wss' | Should -Be 'wss://example.com'
    }
    It 'preserves a nondefault TLS port' {
        ConvertTo-BridgeOrigin 'https://EXAMPLE.com:8443/' 'https' | Should -Be 'https://example.com:8443'
    }
    It 'rejects credentials and paths' {
        { ConvertTo-BridgeOrigin 'https://user:secret@example.com/' 'https' } | Should -Throw
        { ConvertTo-BridgeOrigin 'https://example.com/app' 'https' } | Should -Throw
    }
    It 'rejects plaintext origins' {
        { ConvertTo-BridgeOrigin 'http://example.com/' 'https' } | Should -Throw
        { ConvertTo-BridgeOrigin 'ws://example.com/' 'wss' } | Should -Throw
    }
}
