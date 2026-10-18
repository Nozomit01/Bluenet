#!/usr/bin/env python3
"""Package verified artifacts and explicitly allowlisted public source files."""
import hashlib
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    version = re.search(r"versionName '([^']+)'", (ROOT / 'app/build.gradle').read_text()).group(1)
    release = ROOT / 'dist' / ('release-' + version)
    apk = ROOT / 'dist' / ('BlueNetwork-' + version + '.apk')
    report = json.loads((release / 'verification.json').read_text())
    assert report['version'] == version and report['build'] == 'pass'
    assert report['apk_sha256'] == digest(apk)
    paths = set(json.loads((ROOT / 'tools/source-allowlist.json').read_text()))
    inventory = []
    for name in sorted(paths):
        path = ROOT / name
        assert not Path(name).is_absolute() and '..' not in Path(name).parts
        assert path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(ROOT)
        assert not any(p in {'.signing', '.git', 'profiles', 'captures', 'credentials', 'secrets', 'target', '.gradle'} for p in Path(name).parts)
        assert Path(name).parts[0] not in {'build', 'dist', '.gradle'}
        assert not path.name.endswith(('.enc', '.jks', '.p12', '.keystore')) and not path.name.startswith('.env')
        inventory.append({'path': name, 'sha256': digest(path)})
    (release / 'source-allowlist.json').write_text(json.dumps(sorted(paths), indent=2) + '\n')
    (release / 'source-inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
    source = ROOT / 'dist' / ('BlueNetwork-' + version + '-source.zip')
    prefix = 'BlueNetwork-' + version + '-source/'
    with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED, compresslevel=6, strict_timestamps=False) as archive:
        for name in sorted(paths):
            archive.write(ROOT / name, prefix + name)
        for path in sorted(release.rglob('*')):
            if path.is_file():
                archive.write(path, prefix + 'release-verification/' + str(path.relative_to(release)))
    with zipfile.ZipFile(source) as archive:
        assert archive.testzip() is None
        for entry in inventory:
            assert hashlib.sha256(archive.read(prefix + entry['path'])).hexdigest() == entry['sha256']
    for artifact in (apk, source):
        artifact.with_name(artifact.name + '.sha256').write_text(digest(artifact) + '  ' + artifact.name + '\n')
    print(json.dumps({'version': version, 'source_files': len(paths), 'apk': apk.name, 'source': source.name}))


if __name__ == '__main__':
    main()
