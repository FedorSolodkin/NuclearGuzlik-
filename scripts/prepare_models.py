#!/usr/bin/env python3
"""Download the declared models before judging; never reads user recordings."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=root / 'config/models')
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    for model in json.loads((root / 'config/models.json').read_text()):
        target = args.directory / model['device_name']
        if target.is_file() and sha256(target) == model['sha256']:
            print(f'OK {target.name}')
            continue
        if args.verify_only:
            parser.exit(1, f'Missing or invalid: {target.name}\n')
        partial = target.with_name(target.name + '.partial')
        print(f'Downloading {target.name}', flush=True)
        try:
            request = urllib.request.Request(model['url'], headers={'User-Agent': 'GuzlikVoice-model-preparation'})
            with urllib.request.urlopen(request, timeout=120) as response, partial.open('wb') as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            if sha256(partial) != model['sha256']:
                raise ValueError(f'SHA-256 mismatch: {target.name}')
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    print('All models verified. Install them before enabling airplane mode.')


if __name__ == '__main__':
    main()
