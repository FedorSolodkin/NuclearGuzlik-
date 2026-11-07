#!/usr/bin/env python3
"""Audit reference drafts; structural checks do not certify what was spoken."""
import argparse
from difflib import SequenceMatcher
import hashlib
import json
from pathlib import Path
import re
import wave


def tokens(text):
    return re.findall(r'[^\W_]+', text.casefold().replace('ё', 'е'))


def audit(audio, draft):
    with wave.open(str(audio)) as stream:
        duration = stream.getnframes() * 1000 / stream.getframerate()
    turns = json.loads(draft.read_text(encoding='utf-8'))['transcript']
    issues = []
    for i, turn in enumerate(turns):
        a, b = turn['start_ms'], turn['end_ms']
        if type(a) is not int or type(b) is not int or a < 0 or b <= a or b > duration + 1:
            issues.append(dict(kind='invalid_interval', turn=i + 1, start_ms=a, end_ms=b))
        if isinstance(a, (int, float)) and isinstance(b, (int, float)) and b - a > 15000:
            issues.append(dict(kind='long_turn_review_speaker_changes', turn=i + 1, start_ms=a, end_ms=b))
        if re.search(r'\d', turn['text']):
            issues.append(dict(kind='numbers_need_annotation_convention', turn=i + 1))
        for j in range(i):
            other = turns[j]
            overlap = min(b, other['end_ms']) - max(a, other['start_ms'])
            if overlap <= 0:
                continue
            short = min((tokens(turn['text']), tokens(other['text'])), key=len)
            long = max((tokens(turn['text']), tokens(other['text'])), key=len)
            matched = sum(x.size for x in SequenceMatcher(None, short, long, autojunk=False).get_matching_blocks())
            kind = 'same_speaker_overlap' if turn['speaker_id'] == other['speaker_id'] else 'overlap_requires_listening'
            issues.append(dict(kind=kind, turns=[j + 1, i + 1], overlap_ms=overlap,
                               possible_text_duplication=len(short) >= 3 and matched / len(short) >= .75))
    return dict(id=audio.stem, audio_ms=round(duration), audio_sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                draft_sha256=hashlib.sha256(draft.read_bytes()).hexdigest(), turns=len(turns),
                speaker_ids=sorted({t['speaker_id'] for t in turns}), issues=issues,
                manually_verified=False, status='needs_human_audio_review')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    records = [audit(p, p.with_suffix('.draft.json')) for p in sorted(args.directory.glob('*.wav'))]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(records=records), ensure_ascii=False, indent=2) + '\n')
    for record in records:
        counts = {}
        for issue in record['issues']:
            counts[issue['kind']] = counts.get(issue['kind'], 0) + 1
        print(record['id'], record['turns'], 'turns;', counts)


if __name__ == '__main__':
    main()
