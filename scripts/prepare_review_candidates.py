#!/usr/bin/env python3
"""Build review drafts from real ASR words + Kotlin labels; never declares gold labels."""
import argparse
from difflib import SequenceMatcher
import hashlib
import json
from pathlib import Path
import re
import wave


def normalized(text):
    return re.findall(r'[^\W_]+', text.casefold().replace('ё', 'е'))


def asr_words(document):
    return [dict(text=x['text'].strip(), startMs=x['offsets']['from'], endMs=x['offsets']['to'])
            for x in document['transcription'] if any(c.isalnum() for c in x['text'])]


def join_words(texts):
    return re.sub(r'\s+([,.!?;:…])', r'\1', ' '.join(texts)).strip()


def candidate(assignments, duration):
    turns = []
    adjusted = []
    for i, (start, end, text, speaker) in enumerate(assignments):
        if not text.strip():
            continue
        a = max(0, min(int(start), duration - 1))
        b = max(a + 1, min(int(end), duration))
        if (a, b) != (start, end):
            adjusted.append(dict(word=i, original=[start, end], candidate=[a, b]))
        identity = f'speaker_{speaker + 1}' if speaker >= 0 else ('overlap' if speaker == -2 else 'unknown')
        if turns and turns[-1]['speaker_id'] == identity and a - turns[-1]['end_ms'] <= 1200:
            turns[-1]['end_ms'] = max(turns[-1]['end_ms'], b)
            turns[-1]['text'] = join_words([turns[-1]['text'], text])
        else:
            turns.append(dict(speaker_id=identity, start_ms=a, end_ms=b, text=text.strip()))
    expected = normalized(' '.join(x[2] for x in assignments))
    assert normalized(' '.join(x['text'] for x in turns)) == expected, 'Grouping changed recognized words'
    return dict(transcript=turns), adjusted


def disagreement(primary, secondary):
    def flatten(units):
        out = []
        for x in units:
            out.extend((word, x['startMs'], x['endMs']) for word in normalized(x['text']))
        return out
    a, b = flatten(primary), flatten(secondary)
    queue = []
    for op, i, j, k, l in SequenceMatcher(None, [x[0] for x in a], [x[0] for x in b], autojunk=False).get_opcodes():
        if op == 'equal':
            continue
        spans = a[i:j] + b[k:l]
        if not spans:
            continue
        queue.append(dict(reason='asr_disagreement_not_a_proven_error',
                          start_ms=min(x[1] for x in spans), end_ms=max(x[2] for x in spans),
                          large_v3=' '.join(x[0] for x in a[i:j]),
                          podlodka=' '.join(x[0] for x in b[k:l])))
    return queue


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audio-directory', type=Path, default=Path('data'))
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('data/review'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    for audio in sorted(args.audio_directory.glob('*.wav')):
        directory = args.workspace / audio.stem
        primary_doc = json.loads((directory / 'large-v3.json').read_text())
        secondary_doc = json.loads((directory / 'podlodka.json').read_text())
        primary, secondary = asr_words(primary_doc), asr_words(secondary_doc)
        assignments = json.loads((directory / 'word-assignments.json').read_text())
        assert normalized(' '.join(x['text'] for x in primary)) == normalized(' '.join(x[2] for x in assignments)), 'ASR/label inputs differ'
        with wave.open(str(audio)) as stream:
            duration = round(stream.getnframes() * 1000 / stream.getframerate())
        proposal, adjusted = candidate(assignments, duration)
        output = args.output / f'{audio.stem}.candidate.json'
        output.write_text(json.dumps(proposal, ensure_ascii=False, indent=1) + '\n')
        queue = disagreement(primary, secondary)
        asr_disagreements = len(queue)
        for i, turn in enumerate(proposal['transcript']):
            if turn['speaker_id'] in {'unknown', 'overlap'}:
                queue.append(dict(reason='uncertain_speaker', turn=i + 1, start_ms=turn['start_ms'], end_ms=turn['end_ms']))
            if re.search(r'\d', turn['text']):
                queue.append(dict(reason='write_numbers_as_spoken_words', turn=i + 1, start_ms=turn['start_ms'], end_ms=turn['end_ms']))
        queue.sort(key=lambda x: x['start_ms'])
        original = json.loads(audio.with_suffix('.draft.json').read_text())['transcript']
        result = json.loads((directory / 'result.json').read_text())
        records.append(dict(id=audio.stem, status='machine_candidate_requires_full_human_listening',
                            audio_sha256=hashlib.sha256(audio.read_bytes()).hexdigest(), audio_ms=duration,
                            candidate_sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                            manually_verified=False, reviewed_by=[], reviewed_ms=0,
                            speakers_in_original_draft=len({x['speaker_id'] for x in original}),
                            acoustic_clusters=result['speakers'],
                            primary_model='Whisper large-v3 q5_0',
                            primary_model_sha256='d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1',
                            secondary_model='Whisper podlodka turbo q4_0',
                            asr_disagreements=asr_disagreements, review_items=len(queue), time_adjustments=adjusted,
                            review_queue=queue))
        print(audio.stem, len(proposal['transcript']), 'candidate turns;', len(queue), 'review items')
    (args.output / 'review-status.json').write_text(json.dumps(dict(records=records), ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
