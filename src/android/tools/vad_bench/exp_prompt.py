#!/usr/bin/env python3
"""A/B стилевого промпта Whisper на стенде (как в TranscriptionSession).

Варианты:
  style — как в приложении: 1-й кусок со стилевым промптом, дальше хвост прошлого текста;
  tail  — 1-й кусок без промпта, дальше хвост прошлого текста;
  none  — без промптов.
Нарезка — как в приложении: куски 40–58 с, разрез в самом тихом окне 200 мс.
VAD — автопорог, запас 300 мс (как whisper_jni.cpp). WER с цифрами, переведёнными в слова.

    python3 exp_prompt.py <vmbench> <whisper.bin> <silero.bin> [N на условие]
"""
import io
import json
import re
import subprocess
import sys
from pathlib import Path

import numpy as np

DATA = Path.home() / "voicemind-bench" / "data"
STYLE = ("Ну, в общем, я думаю, что это важно. То есть, на самом деле, если подумать... "
         "Значит, вот как это работает. Расскажите, пожалуйста, поподробнее. "
         "Конечно, я согласен. А вы как считаете? Ладно, спасибо большое!")
SR = 16000


def norm(s):
    from num2words import num2words
    s = s.lower().replace("ё", "е")
    s = re.sub(r"(?<=\d)[\s ](?=\d{3}\b)", "", s)            # 42 500 → 42500
    s = re.sub(r"\d+", lambda m: " " + num2words(int(m.group()), lang="ru") + " ", s)
    s = re.sub(r"[^\w\s]", " ", s)
    return s.split()


def edits(r, h):
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)]


def chunks(pcm):
    """Нарезка как TranscriptionSession.cutChunkLocked + quietestPoint."""
    out, start = [], 0
    while len(pcm) - start > 58 * SR:
        lo, hi = start + 40 * SR, start + 58 * SR
        win, hop = SR // 5, SR // 50
        best, best_e = hi, None
        for p in range(lo, hi - win, hop):
            e = float(np.mean(pcm[p:p + win].astype(np.float64) ** 2))
            if best_e is None or e < best_e:
                best, best_e = p + win // 2, e
        out.append(pcm[start:best]); start = best
    out.append(pcm[start:])
    return out


class Bench:
    def __init__(self, exe, wmodel, vmodel):
        self.p = subprocess.Popen([exe, wmodel, vmodel], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, text=True)
        assert "ready" in self.p.stdout.readline()

    def call(self, line):
        self.p.stdin.write(line + "\n"); self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())


def run_file(b, pcm, variant, tmp):
    texts = []
    for i, c in enumerate(chunks(pcm)):
        tmp.write_bytes(c.astype("<i2").tobytes())
        thr = b.call(f"autothr\tpcm={tmp}")["thr"]
        if variant == "none":
            prompt = ""
        elif i == 0:
            prompt = STYLE if variant == "style" else ""
        else:
            prompt = " ".join(texts)[-500:]
        line = f"asr\tpcm={tmp}\tvad=1\tthr={thr}\tpad=300\tmin_silence=300"
        if prompt:
            line += f"\tprompt={prompt}"
        texts.append(" ".join(s["text"].strip() for s in b.call(line)["segments"]))
    return " ".join(texts)


def dataset(n_per_cond):
    import pyarrow.parquet as pq
    import soundfile as sf
    items = []
    ev = DATA / "synth" / "eval2"
    conds = sorted({p.name.rsplit("__", 1)[0] for p in ev.glob("*.s16")})
    for c in conds:
        for k in range(n_per_cond):
            f = ev / f"{c}__{k}.s16"
            items.append((f"synth/{c}/{k}", np.fromfile(f, dtype="<i2"), f.with_suffix(".ref.txt").read_text()))
    for part in ["crowd", "farfield"]:
        rows = pq.read_table(DATA / "golos" / f"{part}_validation.parquet").slice(0, 60).to_pylist()
        for g in range(4):
            pcm, ref = [], []
            for row in rows[g * 15:(g + 1) * 15]:
                a, sr = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="int16")
                pcm += [a if a.ndim == 1 else a[:, 0], np.zeros(SR, dtype=np.int16)]
                ref.append(row["transcription"])
            items.append((f"golos/{part}/{g}", np.concatenate(pcm), " ".join(ref)))
    return items


def main():
    exe, wm, vm = sys.argv[1:4]
    n = int(sys.argv[4]) if len(sys.argv) > 4 else 2
    b = Bench(exe, wm, vm)
    tmp = Path("/tmp/exp_prompt_chunk.s16") if len(sys.argv) <= 5 else Path(sys.argv[5])
    variants = ["style", "tail", "none"]
    agg = {v: {} for v in variants}
    for name, pcm, ref in dataset(n):
        r = norm(ref)
        row = []
        for v in variants:
            e = edits(r, norm(run_file(b, pcm, v, tmp)))
            grp = name.split("/")[0] + "/" + name.split("/")[1].split("__")[0]
            a = agg[v].setdefault(grp, [0, 0]); a[0] += e; a[1] += len(r)
            row.append(f"{v} {e / len(r) * 100:5.1f}%")
        print(f"{name:40} " + "  ".join(row), flush=True)
    print()
    for grp in sorted(agg["style"]):
        print(f"{grp:25} " + "  ".join(f"{v} {agg[v][grp][0] / agg[v][grp][1] * 100:5.1f}%" for v in variants))
    tot = {v: sum(x[0] for x in agg[v].values()) / sum(x[1] for x in agg[v].values()) * 100 for v in variants}
    print(f"{'ИТОГО':25} " + "  ".join(f"{v} {tot[v]:5.1f}%" for v in variants))


if __name__ == "__main__":
    main()
