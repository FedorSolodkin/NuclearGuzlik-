#!/usr/bin/env python3
"""Run local ONNX -> actual Kotlin core -> short refinement -> external scoring.

Requires the ML Python environment used by capture_evidence_host.py and an
already prepared frozen corpus manifest. No downloads or remote inference.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('corpus',type=Path)
    parser.add_argument('--java-home',default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--whisper-cli',type=Path)
    parser.add_argument('--whisper-model',type=Path)
    parser.add_argument('--language',default='en')
    args=parser.parse_args()
    if bool(args.whisper_cli)!=bool(args.whisper_model):parser.error('Provide both Whisper CLI and model')
    if not args.java_home:parser.error('JAVA_HOME or --java-home is required')
    repository=Path(__file__).resolve().parents[2]
    corpus=args.corpus.resolve()
    manifest=json.loads((corpus/'manifest.json').read_text())
    cases=[]
    for record in manifest['records']:
        if not re.fullmatch(r'[A-Za-z0-9_-]+',record['id']):raise ValueError('Invalid case ID')
        directory=corpus/record['id']
        if directory.resolve().parent!=corpus:raise ValueError('Case outside corpus directory')
        if hashlib.sha256((directory/'audio-16k.wav').read_bytes()).hexdigest()!=record['audio_sha256']:
            raise ValueError('Frozen input changed')
        cases.append(directory)
    def run(command,env=None):subprocess.run([str(x) for x in command],cwd=repository,env=env,check=True)
    env=dict(os.environ,JAVA_HOME=args.java_home,GUZLIKVOICE_DIAR_BENCHMARK=str(corpus))
    gradle=['./gradlew',':app:testStoreDebugUnitTest','--tests','com.example.guzlikvoice.LocalDiarizationBenchmarkTest','--offline']
    for directory in cases:
        print('CAPTURE',directory.name,flush=True)
        run([sys.executable,'tools/device_test/capture_evidence_host.py',directory/'audio-16k.wav',directory/'full-evidence.json'])
        if args.whisper_cli:
            run([args.whisper_cli,'-m',args.whisper_model,'-f',directory/'audio-16k.wav','-l',args.language,
                 '-oj','-of',directory/'asr-words','-ml','1','-sow','-t','4'])
            data=json.loads((directory/'asr-words.json').read_text())
            words=[dict(text=s['text'].strip(),startMs=s['offsets']['from'],endMs=s['offsets']['to'])
                   for s in data['transcription'] if any(c.isalnum() for c in s['text'])]
            (directory/'words.json').write_text(json.dumps(words))
        (directory/'refinement-evidence.json').unlink(missing_ok=True)
    run(gradle,env)
    for directory in cases:run([sys.executable,'tools/device_test/capture_refinement_host.py',directory])
    run(gradle,env)
    run([sys.executable,'tools/device_test/evaluate_diarization_benchmark.py',corpus])
    digest=hashlib.sha256()
    for source in sorted((repository/'app/src/main/java/com/example/guzlikvoice').glob('*.kt')):
        digest.update(source.name.encode());digest.update(source.read_bytes())
    identity=dict(kotlin_sources_sha256=digest.hexdigest(),runtime='host CPU ONNX + JVM; not Android timing',
                  manifest_sha256=hashlib.sha256((corpus/'manifest.json').read_bytes()).hexdigest(),
                  asr_language=args.language if args.whisper_cli else None)
    (corpus/'run-identity.json').write_text(json.dumps(identity,indent=2)+'\n')


if __name__=='__main__':main()
