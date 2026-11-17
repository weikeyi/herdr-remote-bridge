"""Portable static guardrails; not a replacement for Windows/Pester acceptance."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).parent


class BridgeContracts(unittest.TestCase):
    def test_config_has_no_plaintext_secret(self):
        config = json.loads((ROOT / 'bridge.example.json').read_text())
        self.assertEqual(config['schemaVersion'], 1)
        self.assertEqual(set(config), {'schemaVersion', 'relayPath', 'gatewayUrl', 'phoneAppUrl', 'corePipe', 'dataDir', 'registrationCredentialPath', 'relayKeyCredentialPath'})

    def test_current_user_limited_task_only(self):
        script = (ROOT / 'Bridge.Common.ps1').read_text()
        self.assertIn("'HerdrRemoteBridge-' + $identity.User.Value", script)
        self.assertIn('-LogonType Interactive -RunLevel Limited', script)
        self.assertIn('-AtLogOn -User $identity.User.Value', script)
        self.assertNotIn('Stop-Process', script)
        self.assertNotIn('Remove-Item', script)
        for line in script.splitlines():
            if 'Register-ScheduledTask -TaskName' in line:
                self.assertNotIn('-Force', line)
            if 'Unregister-ScheduledTask ' in line or 'Stop-ScheduledTask ' in line:
                self.assertIn("-TaskName $taskName -TaskPath '\\'", line)

    def test_launcher_is_gateway_only_and_uses_protected_files(self):
        script = (ROOT / 'Bridge.Common.ps1').read_text()
        for item in ["HERDR_RELAY_BACKEND = 'hc'", "HERDR_TRANSPORT_FORCE_RELAY = '1'", "HERDR_REACHABILITY_PORT_MAPPING = '0'", "HERDR_BOOTSTRAP_ROLE = 'reader'", 'HERDR_RELAY_TOKEN_FILE =', 'HERDR_GATEWAY_REGISTRATION_TOKEN_FILE =']:
            self.assertIn(item, script)
        self.assertNotIn('HERDR_RELAY_TOKEN =', script)
        self.assertNotIn('HERDR_GATEWAY_REGISTRATION_TOKEN =', script)
        self.assertNotIn('Invoke-Expression', script)

    def test_helper_uses_stdin_not_secret_argv(self):
        script = (ROOT / 'Protect-BridgeSecret.ps1').read_text()
        self.assertIn('-AsSecureString', script)
        self.assertIn('$process.StandardInput.Write($plain)', script)
        self.assertIn('ZeroFreeBSTR', script)
        self.assertNotIn('Write-Output $plain', script)
        self.assertNotIn('Set-Content', script)


if __name__ == '__main__':
    unittest.main()
