#!/usr/bin/env python3
"""Reproduce local machine candidates; recordings are never sent to a remote API."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from prepare_review_candidates import asr_words


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--whisper-cli', type=Path, required=True)
    parser.add_argument('--large-model', type=Path, required=True)
    parser.add_argument('--turbo-model', type=Path, required=True)
    parser.add_argument('--onnx-models', type=Path, required=True)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--ml-python', type=Path, default=Path(sys.executable))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    tool = repo / 'src/android/tools/device_test'
    expected = 'd75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1'
    if digest(args.large_model) != expected:
        parser.error('Unexpected large-v3 model SHA-256')
    if digest(args.turbo_model) != '172d761c921345082986689cb622f891184da29476b85e873bb272687a3c73a7':
        parser.error('Unexpected podlodka model SHA-256')
    args.workspace.mkdir(parents=True, exist_ok=True)
    cases = []
    for audio in sorted((repo / 'data').glob('*.wav')):
        directory = args.workspace / audio.stem
        directory.mkdir(exist_ok=True)
        identity = directory / 'audio-sha256.txt'
        sha = digest(audio)
        if identity.exists() and identity.read_text().strip() != sha:
            parser.error(f'Workspace belongs to different audio: {audio.stem}')
        identity.write_text(sha + '\n')
        print('ASR', audio.stem, flush=True)
        for name, model in [('large-v3', args.large_model), ('podlodka', args.turbo_model)]:
            # Always recompute ASR for reproducibility; no reference labels are read.
            with (directory / f'{name}-asr.log').open('w') as log:
                subprocess.run([str(args.whisper_cli), '-m', str(model), '-f', str(audio), '-l', 'ru',
                                '-ojf', '-ml', '1', '-sow', '-of', str(directory / name), '-t', '4'],
                               stdout=log, stderr=subprocess.STDOUT, check=True)
        document = json.loads((directory / 'large-v3.json').read_text())
        (directory / 'words.json').write_text(json.dumps(asr_words(document), ensure_ascii=False))
        subprocess.run([str(args.ml_python), str(tool / 'capture_evidence_host.py'), str(audio),
                        str(directory / 'full-evidence.json'), '--models', str(args.onnx_models), '--workers', '2'], check=True)
        (directory / 'refinement-evidence.json').unlink(missing_ok=True)
        cases.append((audio, directory))
    env = dict(os.environ, JAVA_HOME=args.java_home, VOICEMIND_DIAR_BENCHMARK=str(args.workspace.resolve()))
    gradle = ['./gradlew', '--offline', ':app:testStoreDebugUnitTest', '--tests',
              'com.example.voicemind.LocalDiarizationBenchmarkTest', '--console=plain']
    subprocess.run(gradle, cwd=repo / 'src/android', env=env, check=True)
    for audio, directory in cases:
        subprocess.run([str(args.ml_python), str(tool / 'capture_refinement_host.py'), str(directory),
                        '--audio', str(audio), '--model', str(args.onnx_models / 'speaker_model.onnx')], check=True)
    subprocess.run(gradle, cwd=repo / 'src/android', env=env, check=True)
    subprocess.run([sys.executable, str(repo / 'scripts/prepare_review_candidates.py'), '--workspace', str(args.workspace),
                    '--audio-directory', str(repo / 'data'), '--output', str(repo / 'data/review')], check=True)
    identity = dict(primary_model_sha256=expected, secondary_model_sha256=digest(args.turbo_model),
                    whisper_binary_sha256=digest(args.whisper_cli), reference_labels_used_in_inference=False,
                    manually_verified=False, runtime='local macOS ASR + CPU ONNX + Kotlin JVM; not Android timing')
    (repo / 'data/review/run-identity.json').write_text(json.dumps(identity, indent=2) + '\n')


if __name__ == '__main__':
    main()
