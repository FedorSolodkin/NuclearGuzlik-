#!/usr/bin/env python3
"""Verify the exact contest model files in a directory or inside the signed APK."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def digest(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
        h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--bundle', type=Path)
    source.add_argument('--apk', type=Path)
    args = parser.parse_args()
    spec = json.loads((Path(__file__).resolve().parents[1] / 'config/contest-models.json').read_text())
    expected = {m['file']: m for m in spec['models']}
    if args.bundle:
        folder = args.bundle / 'models'
        actual = {f.name for f in folder.iterdir() if f.is_file()}
        if actual != expected.keys():
            raise SystemExit(f'Model set mismatch: expected {sorted(expected)}, got {sorted(actual)}')
        for name, model in expected.items():
            path = folder / name
            with path.open('rb') as stream:
                if path.stat().st_size != model['bytes'] or digest(stream) != model['sha256']:
                    raise SystemExit(f'Bad model: {name}')
        if not (args.bundle / 'GEMMA-NOTICE.txt').is_file():
            raise SystemExit('Copy docs/GEMMA-NOTICE.txt to the bundle root')
    else:
        with zipfile.ZipFile(args.apk) as archive:
            prefix = 'assets/models/'
            actual = {i.filename[len(prefix):] for i in archive.infolist() if i.filename.startswith(prefix)}
            if actual != expected.keys():
                raise SystemExit(f'Wrong APK models: {sorted(actual)}')
            for name, model in expected.items():
                entry = archive.getinfo(prefix + name)
                with archive.open(entry) as stream:
                    if entry.file_size != model['bytes'] or entry.compress_type != zipfile.ZIP_STORED or digest(stream) != model['sha256']:
                        raise SystemExit(f'Bad APK model: {name}')
            auxiliary = {m['asset']: m for m in spec['auxiliary_assets']}
            allowed = {prefix + name for name in expected if name.endswith(('.bin', '.gguf', '.onnx'))} | auxiliary.keys()
            actual_weights = {i.filename for i in archive.infolist() if i.filename.startswith('assets/')
                              and i.filename.endswith(('.bin', '.gguf', '.onnx'))}
            if actual_weights != allowed:
                raise SystemExit(f'Unexpected model assets: {sorted(actual_weights ^ allowed)}')
            for name, model in auxiliary.items():
                with archive.open(name) as stream:
                    if archive.getinfo(name).file_size != model['bytes'] or digest(stream) != model['sha256']:
                        raise SystemExit(f'Bad auxiliary model: {name}')
            if not archive.read('assets/GEMMA-NOTICE.txt').strip():
                raise SystemExit('Missing Gemma notice')
    print('Verified: only exact GigaAM v3 RNNT (encoder + decoder + joiner + tokens), Gemma ru90k and required diarization/VAD assets')


if __name__ == '__main__':
    main()
