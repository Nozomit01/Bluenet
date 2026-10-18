#!/usr/bin/env python3
"""Generate GitHub release bodies and the changelog from the application's notes."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def documents():
    entries = json.loads((ROOT / 'app/src/main/assets/release-notes.json').read_text())['releases']
    assert len({e['version'] for e in entries}) == len(entries)
    changelog = '# История изменений / Release notes\n\nИзменения перечислены от новых версий к предыдущим. Даты указаны только для подтверждённых выпусков.\n\n'
    for entry in entries:
        heading = entry['version'] + (' — ' + entry['date'] if entry.get('date') else '')
        body = ''
        for language, name in [('ru', 'Русский'), ('en', 'English')]:
            section = entry[language]
            assert section['title'].strip() and section['changes']
            body += '### ' + name + '\n\n**' + section['title'] + '**\n\n'
            body += ''.join('- ' + line + '\n' for line in section['changes']) + '\n'
        yield ROOT / 'docs/github-releases' / ('v' + entry['version'] + '.md'), '# ' + heading + '\n\n' + body
        changelog += '## ' + heading + '\n\n' + body
    yield ROOT / 'CHANGELOG.md', changelog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, contents in documents():
        if args.check:
            assert path.read_text() == contents, f'Outdated release notes: {path}'
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents)
    print('Release notes are synchronized.' if args.check else 'Release notes generated.')


if __name__ == '__main__':
    main()
