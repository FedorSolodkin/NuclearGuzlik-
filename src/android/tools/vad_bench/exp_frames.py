#!/usr/bin/env python3
"""
Кадровые метрики VAD (без Whisper) на синтетике: доля пропущенной речи по
говорящим (near/mid/far) и доля ложной речи — по условиям шума.

  python exp_frames.py synth/tune baseline     — три режима приложения
  python exp_frames.py synth/tune sweep        — сетка порогов: потолок «оракула»
"""
import sys
from collections import defaultdict

import numpy as np

import bench

MODES = {"тихо 0.35": 0.35, "обычно 0.5": 0.5, "шумно 0.64": 0.64}


def speaker_frames(item):
    """Номер кадра → говорящий (near/mid/far) по разметке фраз."""
    spk = np.full(len(item.labels), "", dtype=object)
    for m in item.meta["speech"]:
        spk[int(m["t0"] * bench.SR) // bench.FRAME:int(m["t1"] * bench.SR) // bench.FRAME + 1] = m["speaker"]
    return spk


GUARD_FRAMES = 10  # 320 мс: шум у самых границ фраз ложной речью не считаем
EDGE_FRAMES = 4    # 128 мс от края голосового участка — «срезанный край», дальше — «потерян кусок»


def nonspeech_mask(item):
    """Кадры вне всех фраз, с отступом от границ — здесь VAD должен молчать."""
    ns = np.ones(len(item.labels), dtype=bool)
    for m in item.meta["speech"]:
        a = max(0, int(m["t0"] * bench.SR) // bench.FRAME - GUARD_FRAMES)
        ns[a:int(m["t1"] * bench.SR) // bench.FRAME + 1 + GUARD_FRAMES] = False
    return ns


def edge_mask(lab):
    """Голосовые кадры не дальше EDGE_FRAMES от края голосового участка."""
    edge = np.zeros(len(lab), dtype=bool)
    d = np.diff(np.concatenate([[0], lab.astype(np.int8), [0]]))
    for s, e in zip(np.where(d == 1)[0], np.where(d == -1)[0]):
        edge[s:min(e, s + EDGE_FRAMES)] = True
        edge[max(s, e - EDGE_FRAMES):e] = True
    return edge & lab


def covered_mask(item, strategy):
    res = bench.run_app(item.pcm, strategy, with_asr=False)
    cov = np.zeros(len(item.labels), dtype=bool)
    for start, _v, _g, segs in res.chunks:
        for s, e in segs:
            cov[(start + s) // bench.FRAME:(start + e + bench.FRAME - 1) // bench.FRAME] = True
    return cov


def evaluate(items, strategy):
    """→ miss {(noise, speaker): [miss, speech]}, fa {noise: [fa, nonspeech]}, edge/interior пропуски."""
    miss = defaultdict(lambda: [0, 0])
    fa = defaultdict(lambda: [0, 0])
    parts = defaultdict(int)
    for it in items:
        cov = covered_mask(it, strategy)
        lab = it.labels.astype(bool)
        spk = speaker_frames(it)
        nl = it.meta["noise_level"]
        for s in ("near", "mid", "far"):
            m = lab & (spk == s)
            miss[(nl, s)][0] += int((m & ~cov).sum()); miss[(nl, s)][1] += int(m.sum())
        ns = nonspeech_mask(it)
        fa[nl][0] += int((ns & cov).sum()); fa[nl][1] += int(ns.sum())
        edge = edge_mask(lab)
        parts["edge"] += int((edge & ~cov).sum()); parts["interior"] += int((lab & ~edge & ~cov).sum())
        parts["speech"] += int(lab.sum())
    return miss, fa, parts


NOISES = ["silent", "room", "office", "loud"]


def table(name, miss, fa, parts):
    print(f"\n{name}")
    print("  шум      | пропуск near | пропуск mid | пропуск far | ложная речь")
    for nl in NOISES:
        cells = []
        for s in ("near", "mid", "far"):
            a, b = miss[(nl, s)]
            cells.append(f"{100 * a / b:10.1f}%" if b else "        —  ")
        a, b = fa[nl]
        print(f"  {nl:8} | {cells[0]:>12} | {cells[1]:>11} | {cells[2]:>11} | {100 * a / b:9.1f}%")
    print(f"  пропуски: срезанные края слов {100 * parts['edge'] / parts['speech']:.1f}% речи, "
          f"потерянные куски {100 * parts['interior'] / parts['speech']:.1f}%")


def main():
    split, what = sys.argv[1], sys.argv[2]
    items = bench.load_set(split)
    if what == "baseline":
        for name, thr in MODES.items():
            table(name, *evaluate(items, bench.fixed(thr=thr)))
    elif what == "sweep":
        thrs = [0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.5, 0.6, 0.7]
        print("порог | пропуск речи (все) | пропуск far | ложная речь (все) | ложная речь loud")
        for t in thrs:
            miss, fa, _ = evaluate(items, bench.fixed(thr=t))
            ma = sum(v[0] for v in miss.values()) / sum(v[1] for v in miss.values())
            mf = sum(miss[(n, "far")][0] for n in NOISES) / max(1, sum(miss[(n, "far")][1] for n in NOISES))
            fa_all = sum(v[0] for v in fa.values()) / sum(v[1] for v in fa.values())
            fl = fa["loud"][0] / fa["loud"][1]
            print(f" {t:4.2f} | {100 * ma:17.1f}% | {100 * mf:10.1f}% | {100 * fa_all:16.1f}% | {100 * fl:15.1f}%")
    bench.vm().close()


if __name__ == "__main__":
    main()
