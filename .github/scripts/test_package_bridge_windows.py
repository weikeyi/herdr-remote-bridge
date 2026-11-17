import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from package_bridge_windows import package, RUNTIME_SCRIPTS


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.version, self.revision = '0.25.1', 'a' * 40
        data = {'version': self.version, 'revision': self.revision, 'target': 'windows/amd64'}
        self.put('artifacts/windows-version.json', json.dumps(data))
        self.put('artifacts/herdr-mobile-relay.exe', 'synthetic-executable')
        self.put('frontend/dist/version.json', json.dumps(data))
        for name in ('index.html', 'release.json', 'assets/app.js'):
            self.put('frontend/dist/' + name, '{}')
        for name in RUNTIME_SCRIPTS:
            self.put('relay/windows/' + name, 'script or documentation')
        for name in ('LICENSE', 'docs/bridge-package-readme.md', 'docs/bridge-user-testing.zh-CN.md'):
            self.put(name, 'documentation')
        self.put('artifacts/windows-go-tests.txt', 'DO_NOT_PACKAGE_LOGS')
        self.put('relay/windows/local-secret.dpapi', 'DO_NOT_PACKAGE_SECRET')
        self.put('web/stale-upstream.txt', 'DO_NOT_PACKAGE_STALE_WEB')

    def put(self, path, value):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)

    def build(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return package(self.root, self.root / 'output', self.version, self.revision)

    def test_bundle_contains_only_allowlisted_matching_payload_and_valid_hashes(self):
        archive = self.build()
        with zipfile.ZipFile(archive) as bundle:
            names = bundle.namelist()
            manifest = json.loads(bundle.read('build-manifest.json'))
            self.assertEqual(manifest['revision'], self.revision)
            self.assertIn('web/assets/app.js', names)
            self.assertIn('windows/Start-Bridge.ps1', names)
            for name, digest in manifest['files'].items():
                self.assertEqual(hashlib.sha256(bundle.read(name)).hexdigest(), digest)
            for line in bundle.read('SHA256SUMS').decode().splitlines():
                digest, name = line.split('  ', 1)
                self.assertEqual(hashlib.sha256(bundle.read(name)).hexdigest(), digest)
            content = b''.join(bundle.read(name) for name in names)
            self.assertNotIn(b'DO_NOT_PACKAGE', content)
        with self.assertRaises(FileExistsError):
            self.build()

    def test_sensitive_frontend_files_are_refused(self):
        for name in ('.env.local', '.env', 'debug.log', 'private.dpapi', 'signing.key', 'tls.pem', 'activity.jsonl'):
            with self.subTest(name=name):
                self.put('frontend/dist/' + name, 'DO_NOT_PACKAGE_SECRET')
                with self.assertRaisesRegex(ValueError, 'sensitive or diagnostic'):
                    self.build()
                (self.root / 'frontend/dist' / name).unlink()

    def test_mismatched_frontend_fails(self):
        self.put('frontend/dist/version.json', '{"version":"0.0.0","revision":"wrong"}')
        with self.assertRaisesRegex(ValueError, 'frontend identity mismatch'):
            self.build()

    def test_mismatched_executable_fails(self):
        self.put('artifacts/windows-version.json', '{"version":"dev"}')
        with self.assertRaisesRegex(ValueError, 'executable identity mismatch'):
            self.build()

    def test_missing_required_script_fails(self):
        (self.root / 'relay/windows/Start-Bridge.ps1').unlink()
        with self.assertRaisesRegex(ValueError, 'missing or linked'):
            self.build()


if __name__ == '__main__':
    unittest.main()
