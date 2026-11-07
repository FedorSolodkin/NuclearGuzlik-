#!/usr/bin/env python3
"""Summarize on-device synthetic smoke tests; this is not a human-speech benchmark."""
import argparse
import hashlib
import json
from pathlib import Path
from diar_score import score


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('results', type=Path)
    ap.add_argument('--fixtures', type=Path, default=Path(__file__).parent / 'assets' / 'diar')
    ap.add_argument('--output', type=Path)
    a = ap.parse_args()
    results = json.loads(a.results.read_text())
    summary = {'kind': 'macOS TTS acoustic smoke; no ASR; coarse utterance boundaries', 'cases': {}}
    for name, r in results.items():
        pcm = a.fixtures / f'{name}.s16'
        audio_ms = pcm.stat().st_size * 1000 // 32000
        if audio_ms != r['audio_ms']:
            raise ValueError(f'{name}: fixture duration differs from device result; re-push fixtures')
        ref = json.loads((a.fixtures / f'{name}.reference.json').read_text())
        metrics = score(ref, r['turns'])
        case = {**{k: v for k, v in r.items() if k != 'turns'}, **metrics,
                'pcm_sha256': hashlib.sha256(pcm.read_bytes()).hexdigest()}
        summary['cases'][name] = case
        print(f"{name:24} {audio_ms/1000:5.1f}s audio  {r['diar_ms']/1000:5.2f}s processing  "
              f"voices {r['speakers']}/{metrics['reference_speakers']}  "
              f"DER {metrics['der']*100:5.1f}%  confusion {metrics['confusion_ms']:.0f}ms")
    if a.output:
        a.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
