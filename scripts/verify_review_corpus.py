#!/usr/bin/env python3
"""Check candidate provenance and the JSON contract without certifying audio accuracy."""
import hashlib
import json
from pathlib import Path

from evaluate import load


def verify(root):
    root = Path(root)
    index = json.loads((root / 'data/index.json').read_text())
    records = json.loads((root / 'data/review/review-status.json').read_text())['records']
    ids = [x['id'] for x in records]
    assert len(ids) == len(set(ids)), 'Duplicate review record'
    total = 0
    for item in index:
        if not item.get('review_draft'):
            continue
        record = next(x for x in records if x['id'] == item['id'])
        audio = root / 'data' / (item['id'] + '.wav')
        candidate = (root / 'data' / item['review_draft']).resolve()
        assert candidate.parent == (root / 'data/review').resolve(), 'Candidate outside review folder'
        assert hashlib.sha256(audio.read_bytes()).hexdigest() == record['audio_sha256'] == item['audio_sha256'], 'Audio changed'
        assert hashlib.sha256(candidate.read_bytes()).hexdigest() == record['candidate_sha256'], 'Candidate changed'
        assert record['manually_verified'] is False and record['reviewed_by'] == [] and record['reviewed_ms'] == 0, 'Machine candidate was marked as human-verified'
        turns = load(candidate)
        assert all(x['end_ms'] <= record['audio_ms'] for x in turns), 'Turn outside recording'
        total += len(turns)
    print(f'{len(records)} machine candidates, {total} turns: hashes and contract verified; human review pending')


if __name__ == '__main__':
    verify(Path(__file__).resolve().parents[1])
