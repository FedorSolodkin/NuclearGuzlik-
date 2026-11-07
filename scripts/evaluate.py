#!/usr/bin/env python3
"""Validate the contest contract and measure lexical WER/RTF, without model inference."""
import argparse
import json
import math
from pathlib import Path
import re


def load(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(data, dict) or set(data) != {"transcript"} or not isinstance(data["transcript"], list):
        raise ValueError("Expected exactly {transcript: array}")
    previous = -1
    for index, turn in enumerate(data["transcript"]):
        if not isinstance(turn, dict) or set(turn) != {"speaker_id", "start_ms", "end_ms", "text"}:
            raise ValueError(f"Turn {index}: expected exactly four contract fields")
        if not isinstance(turn["speaker_id"], str) or not turn["speaker_id"].strip():
            raise ValueError(f"Turn {index}: empty speaker_id")
        if not isinstance(turn["text"], str) or not turn["text"].strip():
            raise ValueError(f"Turn {index}: empty text")
        start, end = turn["start_ms"], turn["end_ms"]
        if type(start) is not int or type(end) is not int or not 0 <= start < end:
            raise ValueError(f"Turn {index}: invalid integer millisecond interval")
        if start < previous:
            raise ValueError(f"Turn {index}: turns must be ordered by start time")
        previous = start
    return data["transcript"]


def words(turns):
    # Explicit normalization: Unicode letters/digits, casefold, ё→е, no punctuation.
    # Does not spell out numbers or erase hesitation words.
    return re.findall(r"[^\W_]+", " ".join(t["text"] for t in turns).casefold().replace("ё", "е"))


def distance(reference, hypothesis):
    previous = list(range(len(hypothesis) + 1))
    for i, expected in enumerate(reference, 1):
        row = [i]
        for j, actual in enumerate(hypothesis, 1):
            row.append(min(row[-1] + 1, previous[j] + 1, previous[j - 1] + (expected != actual)))
        previous = row
    return previous[-1]


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("Expected a finite positive number")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("hypothesis", type=Path)
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--elapsed-seconds", type=positive)
    parser.add_argument("--audio-seconds", type=positive)
    args = parser.parse_args()
    if (args.elapsed_seconds is None) != (args.audio_seconds is None):
        parser.error("Supply both --elapsed-seconds and --audio-seconds for RTF")
    try:
        hypothesis = load(args.hypothesis)
        result = {"contract_valid": True, "turns": len(hypothesis),
                  "uncertain_turns": sum(t["speaker_id"] in {"unknown", "overlap"} for t in hypothesis)}
        if args.audio_seconds is not None and any(t["end_ms"] > round(args.audio_seconds * 1000) for t in hypothesis):
            raise ValueError("A turn exceeds the supplied audio duration")
        if args.reference:
            reference = words(load(args.reference))
            recognized = words(hypothesis)
            edits = distance(reference, recognized)
            result.update(reference_words=len(reference), edit_distance=edits,
                          wer=edits / len(reference) if reference else (0.0 if not recognized else None))
            result["wer_scope"] = "lexical only; ignores speaker identities and time errors"
        if args.elapsed_seconds is not None:
            result.update(elapsed_seconds=args.elapsed_seconds, audio_seconds=args.audio_seconds,
                          rtf=args.elapsed_seconds / args.audio_seconds)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, OSError) as error:
        parser.exit(1, f"Evaluation failed: {error}\n")


if __name__ == "__main__":
    main()
