"""Build a source-identity-checked Windows bundle; never include runner state/logs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

RUNTIME_SCRIPTS = (
    'Bridge.Common.ps1', 'Bridge.Update.ps1', 'Invoke-Bridge.ps1',
    'Manage-Bridge.ps1', 'Protect-BridgeSecret.ps1', 'Start-Bridge.ps1',
    'Update-Bridge.ps1', 'bridge.example.json', 'README.md',
)


def package(root: Path, output: Path, version: str, revision: str) -> Path:
    if not re.fullmatch(r'\d+\.\d+\.\d+', version) or not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('expected semantic version and complete commit SHA')
    identity = json.loads((root / 'artifacts/windows-version.json').read_text(encoding='utf-8-sig'))
    web = root / 'frontend/dist'
    if web.is_symlink():
        raise ValueError('frontend symlinks are not permitted')
    metadata = json.loads((web / 'version.json').read_text())
    if identity.get('version') != version or identity.get('revision') != revision or identity.get('target') != 'windows/amd64':
        raise ValueError('executable identity mismatch')
    if metadata.get('version') != version or metadata.get('revision') != revision:
        raise ValueError('frontend identity mismatch')
    for required in ('index.html', 'release.json'):
        if not (web / required).is_file():
            raise ValueError(f'missing built frontend {required}')
    files = {
        'herdr-mobile-relay.exe': root / 'artifacts/herdr-mobile-relay.exe',
        'LICENSE': root / 'LICENSE',
        'README.md': root / 'docs/bridge-package-readme.md',
        'README.zh-CN.md': root / 'docs/bridge-user-testing.zh-CN.md',
    }
    files.update({f'windows/{name}': root / 'relay/windows' / name for name in RUNTIME_SCRIPTS})
    for path in sorted(web.rglob('*')):
        if path.is_symlink():
            raise ValueError('frontend symlinks are not permitted')
        if path.is_file():
            # Built PWA assets are public. Refuse common accidental local secret
            # and diagnostic files instead of silently shipping them.
            basename = path.name.lower()
            if (basename == '.env' or basename.startswith('.env.')
                    or path.suffix.lower() in {'.dpapi', '.log', '.jsonl', '.pem', '.key'}):
                raise ValueError('sensitive or diagnostic file in frontend output')
            files['web/' + path.relative_to(web).as_posix()] = path
    contents = {}
    for name, path in files.items():
        if path.is_symlink() or not path.is_file():
            raise ValueError(f'missing or linked bundle input: {name}')
        contents[name] = path.read_bytes()
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(contents.items())}
    manifest = {'schema_version': 1, 'version': version, 'revision': revision,
                'target': 'windows/amd64', 'files': hashes}
    contents['build-manifest.json'] = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode()
    contents['SHA256SUMS'] = ''.join(
        f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(contents.items())
    ).encode()
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f'herdr-remote-bridge_{version}_windows_amd64_{revision[:12]}.zip'
    # Exclusive creation prevents accidental replacement of a previous bundle.
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, data in sorted(contents.items()):
            bundle.writestr(name, data)
    with zipfile.ZipFile(archive) as bundle:
        if bundle.testzip() is not None:
            raise ValueError('archive integrity check failed')
        for name, digest in hashes.items():
            if hashlib.sha256(bundle.read(name)).hexdigest() != digest:
                raise ValueError('archive manifest verification failed')
    print(f'{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}')
    return archive


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path, default=Path('artifacts/bundle'))
    parser.add_argument('--version', required=True)
    parser.add_argument('--revision', required=True)
    args = parser.parse_args()
    package(args.root, args.output, args.version, args.revision)
