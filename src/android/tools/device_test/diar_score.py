#!/usr/bin/env python3
"""Exact no-collar, overlap-inclusive DER with one global speaker mapping.

Input: JSON [[start_ms, end_ms, speaker_id], ...]. For Android diar.json use
--recording NAME to select that recording's acoustic `turns` (not word timings).
No third-party dependencies. At most 8 identities on either side.
"""
import argparse
import json
import math
from functools import lru_cache
from pathlib import Path


def score(reference, hypothesis):
    events = {}
    ids = [set(), set()]
    for side, regions in enumerate((reference, hypothesis)):
        for start, end, speaker in regions:
            if not (math.isfinite(start) and math.isfinite(end) and 0 <= start < end):
                raise ValueError('Intervals must have finite 0 <= start < end, in milliseconds')
            speaker = str(speaker)
            ids[side].add(speaker)
            events.setdefault(start, []).append((side, speaker, 1))
            events.setdefault(end, []).append((side, speaker, -1))
    if max(map(len, ids)) > 8:
        raise ValueError('GuzlikVoice evaluation supports at most 8 speakers per side')
    ref_ids, hyp_ids = map(sorted, ids)
    ref_index = {s: i for i, s in enumerate(ref_ids)}
    hyp_index = {s: i for i, s in enumerate(hyp_ids)}
    matrix = [[0.0] * len(hyp_ids) for _ in ref_ids]
    active = [{}, {}]
    reference_ms = missed = false_alarm = common = 0.0
    times = sorted(events)
    for i, time in enumerate(times[:-1]):
        for side, speaker, delta in events[time]:
            active[side][speaker] = active[side].get(speaker, 0) + delta
            if active[side][speaker] == 0:
                del active[side][speaker]
        duration = times[i + 1] - time
        r, h = active
        reference_ms += len(r) * duration
        missed += max(0, len(r) - len(h)) * duration
        false_alarm += max(0, len(h) - len(r)) * duration
        common += min(len(r), len(h)) * duration
        for rs in r:
            for hs in h:
                matrix[ref_index[rs]][hyp_index[hs]] += duration

    @lru_cache(None)
    def assign(row, used):
        if row == len(ref_ids):
            return 0.0, ()
        best, rest = assign(row + 1, used)
        choice = (-1,) + rest
        for j in range(len(hyp_ids)):
            if used & (1 << j):
                continue
            value, suffix = assign(row + 1, used | (1 << j))
            value += matrix[row][j]
            if value > best:
                best, choice = value, (j,) + suffix
        return best, choice

    correct, mapping = assign(0, 0)
    confusion = max(0.0, common - correct)
    return dict(der=(missed + false_alarm + confusion) / reference_ms if reference_ms else None,
                reference_ms=reference_ms, missed_ms=missed, false_alarm_ms=false_alarm,
                confusion_ms=confusion, reference_speakers=len(ref_ids), hypothesis_speakers=len(hyp_ids),
                mapping={hyp_ids[j]: ref_ids[i] for i, j in enumerate(mapping) if j >= 0},
                collar_ms=0, overlap_included=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('reference', type=Path)
    ap.add_argument('hypothesis', type=Path)
    ap.add_argument('--recording', help='Select a recording in Android diar.json')
    args = ap.parse_args()
    ref = json.loads(args.reference.read_text())
    hyp = json.loads(args.hypothesis.read_text())
    if args.recording:
        record = hyp[args.recording]
        if 'turns' not in record:
            ap.error('No acoustic turns: rebuild the Android instrumentation APK')
        hyp = record['turns']
    print(json.dumps(score(ref, hyp), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
