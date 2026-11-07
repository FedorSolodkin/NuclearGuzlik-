#!/usr/bin/env python3
"""Run local Russian WER/RTF acceptance on a phone, with manually transcribed PCM fixtures.

Manifest: [{"id":"dialog1", "pcm":"dialog1.s16", "reference":"ручной эталон", "speakers":3}]
PCM must be 16000 Hz mono signed int16 LE. No uploads, models or ground truth generation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def words(text):
    return re.findall(r"[\w]+", text.lower().replace("ё", "е"), flags=re.UNICODE)


def edits(reference, hypothesis):
    row = list(range(len(hypothesis) + 1))
    for i, a in enumerate(reference, 1):
        prev, row[0] = row[0], i
        for j, b in enumerate(hypothesis, 1):
            old = row[j]
            row[j] = min(row[j] + 1, row[j - 1] + 1, prev + (a != b))
            prev = old
    return row[-1]


def summarize(cases, results):
    rows = []
    for case in cases:
        result = results[case["id"]]
        ref = words(case["reference"])
        if not ref:
            raise ValueError("Manual reference cannot be empty: " + case["id"])
        hyp = words(result.get("final") or result["hyp"])
        duration = float(result["audio_s"])
        if duration <= 0:
            raise ValueError("Invalid duration")
        error = edits(ref, hyp)
        rows.append({"id": case["id"], "speaker_count_reference": case["speakers"],
                     "reference_words": len(ref), "errors": error, "wer": error / len(ref),
                     "audio_seconds": duration, "processing_ms": result["ms"],
                     "rtf": result["ms"] / (1000 * duration), "model": result.get("model")})
    return {"cases": rows, "wer": sum(r["errors"] for r in rows) / sum(r["reference_words"] for r in rows),
            "rtf": sum(r["processing_ms"] for r in rows) / (1000 * sum(r["audio_seconds"] for r in rows)),
            "timing_scope": "Phone TranscriptionSession including ASR/finalization, excluding model load, Bluetooth capture and storage. Device service metrics separately measure WAV decode + ASR + finalization.",
            "normalization": "lowercase, ё→е, ignore punctuation; digits are not expanded; word Levenshtein; weighted by reference words"}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("manifest", type=Path)
    p.add_argument("--package", default="ru.guzlikvoice.app.prototype")
    p.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    p.add_argument("--serial")
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.]+\.(prototype|dev)", a.package):
        p.error("Only separate prototype/dev applications are permitted")
    cases = json.loads(a.manifest.read_text())
    if not cases or len({c["id"] for c in cases}) != len(cases):
        p.error("Manifest must contain unique nonempty cases")
    for c in cases:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", c["id"]) or int(c["speakers"]) < 1 or not words(c["reference"]):
            p.error("Each case needs safe id, manual reference and actual speaker count")
        f = a.manifest.parent / c["pcm"]
        if f.stat().st_size == 0 or f.stat().st_size % 2:
            p.error("PCM is empty or has a partial sample")
        c["pcm_sha256"] = hashlib.sha256(f.read_bytes()).hexdigest()
    prefix = [a.adb] + (["-s", a.serial] if a.serial else [])
    def adb(*args, timeout=120):
        r = subprocess.run(prefix + list(args), text=True, capture_output=True, timeout=timeout, check=True)
        return r.stdout
    device = adb("shell", "getprop", "ro.product.model").strip()
    remote = f"/sdcard/Android/data/{a.package}/files/eval"
    # The reserved eval/asr fixture folder only; never touch recordings, models or production app.
    adb("shell", "rm", "-rf", remote + "/asr")
    adb("shell", "mkdir", "-p", remote + "/asr")
    for c in cases:
        adb("push", str(a.manifest.parent / c["pcm"]), remote + "/asr/" + c["id"] + ".s16")
    log = adb("shell", "am", "instrument", "-w", "-e", "class",
              "com.example.guzlikvoice.EngineEvalDeviceTest#asrPipeline",
              a.package + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=6 * 3600)
    if "OK (" not in log or "FAILURES" in log:
        raise RuntimeError(log)
    results = json.loads(adb("shell", "cat", remote + "/out/asr.json"))
    report = summarize(cases, results)
    report.update(device=device, package=a.package, fixture_hashes={c["id"]: c["pcm_sha256"] for c in cases})
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({"device": device, "wer": report["wer"], "rtf": report["rtf"]}, ensure_ascii=False))

if __name__ == "__main__":
    main()
