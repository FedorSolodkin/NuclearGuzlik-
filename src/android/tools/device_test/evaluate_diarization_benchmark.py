#!/usr/bin/env python3
"""Score frozen external references; never feed gold labels back into inference."""
import argparse
import hashlib
import json
from pathlib import Path
from diar_score import score


def turns(record):
    return [[r['startMs'], r['endMs'], r['speaker']] if isinstance(r, dict) else r
            for r in record['turns']]


def merged_length(intervals):
    end = -1
    total = 0
    for a, b in sorted(intervals):
        total += max(0, b-max(a, end))
        end = max(end, b)
    return total


def speech_recall(regions, hypothesis, mapping):
    """Correct-identity time recall under the whole-recording DER mapping."""
    per_voice = {}
    for a, b, person in regions:
        per_voice.setdefault(str(person), []).append((a, b))
    total = correct = 0
    for person, spans in per_voice.items():
        total += merged_length(spans)
        covered = [(max(a, x), min(b, y)) for a, b in spans for x, y, speaker in hypothesis
                   if mapping.get(str(speaker)) == person and min(b, y) > max(a, x)]
        correct += merged_length(covered)
    return dict(reference_ms=total, correct_ms=correct, recall=correct/total if total else None)


def diagnostics(reference, hypothesis, mapping):
    short = [r for r in reference if r[1]-r[0] <= 1500]
    durations = {}
    for a, b, speaker in reference:
        durations[str(speaker)] = durations.get(str(speaker), 0) + b-a
    total = sum(durations.values())
    rare = [r for r in reference if durations[str(r[2])] < total*.05]
    # Gold overlap is independent of prediction and includes all identities.
    overlap = []
    for i, (a, b, s) in enumerate(reference):
        for x, y, other in reference[i+1:]:
            if s != other and min(b, y) > max(a, x): overlap.append((max(a, x), min(b, y)))
    return dict(short_replies=len(short), short_reply_identity_recall=speech_recall(short, hypothesis, mapping),
                rare_speakers=[s for s, d in durations.items() if d < total*.05],
                rare_speaker_identity_recall=speech_recall(rare, hypothesis, mapping),
                overlap_wall_ms=merged_length(overlap))


def word_attribution(reference, words, mapping):
    """Conditional identity accuracy at ASR word times, not transcription WER."""
    eligible=correct=unknown=overlap=ambiguous=zero=0
    for a,b,text,speaker in words:
        if b<=a:
            zero+=1
            continue
        active={}
        local=[]
        for x,y,person in reference:
            if min(b,y)>max(a,x):
                active.setdefault(str(person),[]).append((max(a,x),min(b,y)))
                local.append((max(a,x),min(b,y),person))
        intersections=[(max(x,u),min(y,v)) for i,(x,y,p) in enumerate(local)
                       for u,v,q in local[i+1:] if p!=q and min(y,v)>max(x,u)]
        if intersections:
            overlap+=1
            continue
        ranked=sorted(((merged_length(spans),person) for person,spans in active.items()),reverse=True)
        if not ranked or ranked[0][0]<(b-a)*.8 or (len(ranked)>1 and ranked[1][0]>(b-a)*.05):
            ambiguous+=1
            continue
        eligible+=1
        if int(speaker)<0:unknown+=1
        if mapping.get(str(speaker))==ranked[0][1]:correct+=1
    return dict(total=len(words),eligible=eligible,correct=correct,unknown_or_overlap_prediction=unknown,
                accuracy=correct/eligible if eligible else None,excluded_overlap=overlap,
                excluded_ambiguous_boundary_or_silence=ambiguous,excluded_zero_duration=zero,
                definition='Identity at host Whisper word intervals with >=80% single-speaker gold coverage; not WER or forced-aligned word truth')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.root/'manifest.json').read_text())
    rows = []
    for case in manifest['records']:
        directory = args.root/case['id']
        if hashlib.sha256((directory/'audio-16k.wav').read_bytes()).hexdigest() != case['audio_sha256']:
            raise ValueError('Frozen input changed: '+case['id'])
        reference = json.loads((directory/'reference.json').read_text())
        if reference != case['reference']: raise ValueError('Frozen annotation changed')
        row = dict(id=case['id'], audio_ms=case['audio_ms'], reference_speakers=case['reference_speakers'])
        for name, filename in [('baseline', 'baseline-result.json'), ('refined', 'result.json')]:
            path = directory/filename
            if not path.exists():
                row[name] = dict(status='pending')
                continue
            record = json.loads(path.read_text())
            if record.get('requested_speakers') is not None: raise ValueError('Speaker-count hint is forbidden')
            hypothesis = turns(record)
            metrics = score(reference, hypothesis)
            row[name] = dict(status='measured', **metrics, count_correct=record['speakers']==case['reference_speakers'],
                             predicted_clusters=record['speakers'],
                             **diagnostics(reference, hypothesis, metrics['mapping']))
            words=directory/'word-assignments.json'
            if name=='refined' and words.exists():
                row[name]['word_interval_attribution']=word_attribution(reference,json.loads(words.read_text()),metrics['mapping'])
        rows.append(row)
    report = dict(dataset=manifest['dataset'], source=manifest['source'], selection=manifest['selection'],
                  speaker_count_hint=False, runtime='host CPU ONNX + actual Kotlin core; NOT Android performance',
                  word_attribution='See per-case word_interval_attribution when host Whisper word output is available; uniform probes are never counted as words', records=rows)
    for name in ('baseline', 'refined'):
        measured = [r[name] for r in rows if r[name]['status']=='measured']
        ref_ms = sum(r['reference_ms'] for r in measured)
        report[name] = dict(measured=len(measured), pending=len(rows)-len(measured),
                            count_accuracy=sum(r['count_correct'] for r in measured)/len(measured) if measured else None,
                            der=sum(r['missed_ms']+r['false_alarm_ms']+r['confusion_ms'] for r in measured)/ref_ms if ref_ms else None)
    text = json.dumps(report, ensure_ascii=False, indent=2)+'\n'
    (args.output or args.root/'quality-report.json').write_text(text)
    print(json.dumps({k:report[k] for k in ('baseline', 'refined')}, indent=2))


if __name__ == '__main__': main()
