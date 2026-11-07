#!/usr/bin/env python3
"""
Ошибки распознавания по словам для конфигураций VAD — через полный конвейер приложения.

  python exp_wer.py run synth/tune base thr35 pad300 ...   — посчитать (кэшируется)
  python exp_wer.py report synth/tune base thr35 ...       — таблицы + бутстреп против первой

Результаты по сессиям: runs/wer/<split>/<config>.jsonl
"""
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

import bench

CONFIGS = {
    # текущие режимы приложения
    "base":       bench.fixed(thr=0.5),
    "thr35":      bench.fixed(thr=0.35),
    "thr64":      bench.fixed(thr=0.64),
    "novad":      None,
    # кандидат A: запас по краям и минимальная пауза
    "pad300":     bench.fixed(thr=0.5, pad=300, min_silence=300),
    "thr35pad300": bench.fixed(thr=0.35, pad=300, min_silence=300),
    "thr20pad300": bench.fixed(thr=0.2, pad=300, min_silence=300),
    # раунд 2: размер запаса и минимальной паузы
    "pad150":     bench.fixed(thr=0.5, pad=150, min_silence=300),
    "pad500":     bench.fixed(thr=0.5, pad=500, min_silence=300),
    "pad300sil100": bench.fixed(thr=0.5, pad=300, min_silence=100),
    "pad300sil600": bench.fixed(thr=0.5, pad=300, min_silence=600),
    # кандидат D: порог по фону куска (поверх запаса 300)
    "adapt_q10_m10": bench.noise_adaptive(quiet_pct=10, margin=0.1, lo=0.1, hi=0.6),
    "adapt_q10_m05": bench.noise_adaptive(quiet_pct=10, margin=0.05, lo=0.05, hi=0.6),
    "adapt_q15_m20": bench.noise_adaptive(quiet_pct=15, margin=0.2, lo=0.1, hi=0.6),
    # раунд 3: порог опускаем только при громком фоне
    # ворота по фактическому фону кусков (p10 уровня кадров): тишина ≤ −60, комната ~−56,
    # офис ~−53, громко ~−41 dBFS
    "gate58":     bench.gated_adaptive(floor_db=-58),
    "guard58":    bench.guarded_adaptive(),               # gate58 + проверка покрытия (как в приложении)
    "gate54":     bench.gated_adaptive(floor_db=-54),
    "gate50":     bench.gated_adaptive(floor_db=-50),
}


def out_path(split, cfg):
    p = bench.ROOT / "runs/wer" / split.replace("/", "_") / f"{cfg}.jsonl"
    p.parent.mkdir(parents=True, exist_ok=True)
    return p


def run_one(item, cfg):
    strat = CONFIGS[cfg]
    if strat is None:   # без VAD: весь кусок в Whisper
        strat = lambda ctx: (None, 1.0)
    res = bench.run_app(item.pcm, strat)
    c = bench.wer_counts(bench.normalize(item.ref), bench.normalize(res.hyp))
    return dict(id=item.id, noise=item.meta.get("noise_level"), speakers="+".join(item.meta.get("speakers", [])),
                speech_s=res.speech_s, asr_ms=res.asr_ms, hyp=res.hyp, **c)


def run(split, cfgs):
    items = bench.load_set(split)
    workers = int(os.environ.get("VMBENCH_WORKERS", "2"))
    for cfg in cfgs:
        path = out_path(split, cfg)
        done = {json.loads(l)["id"] for l in path.open()} if path.exists() else set()
        todo = [it for it in items if it.id not in done]
        with ThreadPoolExecutor(workers) as ex, path.open("a") as f:
            for r in ex.map(lambda it: run_one(it, cfg), todo):
                f.write(json.dumps(r, ensure_ascii=False) + "\n"); f.flush()
        print(f"{cfg}: done ({len(todo)} new)", flush=True)


def load(split, cfg):
    return {r["id"]: r for r in map(json.loads, out_path(split, cfg).open())}


def agg(rows):
    N = sum(r["N"] for r in rows)
    return dict(wer=100 * sum(r["S"] + r["D"] + r["I"] for r in rows) / N,
                D=100 * sum(r["D"] for r in rows) / N, I=100 * sum(r["I"] for r in rows) / N,
                S=100 * sum(r["S"] for r in rows) / N, speech=sum(r["speech_s"] for r in rows))


def bootstrap(a_rows, b_rows, n=10000, seed=0):
    """Парный бутстреп по сессиям: доверительный интервал разницы WER (b − a), п.п."""
    rng = np.random.default_rng(seed)
    E_a = np.array([r["S"] + r["D"] + r["I"] for r in a_rows], float)
    E_b = np.array([r["S"] + r["D"] + r["I"] for r in b_rows], float)
    N = np.array([r["N"] for r in a_rows], float)
    idx = rng.integers(0, len(N), (n, len(N)))
    d = 100 * (E_b[idx].sum(1) - E_a[idx].sum(1)) / N[idx].sum(1)
    return np.percentile(d, [2.5, 50, 97.5]), float((d < 0).mean())


def report(split, cfgs):
    data = {c: load(split, c) for c in cfgs}
    ids = sorted(set.intersection(*(set(d) for d in data.values())))
    base = cfgs[0]
    print(f"{split}: {len(ids)} сессий, слов в эталоне {sum(data[base][i]['N'] for i in ids)}")
    print("конфиг        |  WER  | пропуски | вставки | замены | в Whisper, с | Δ WER к " + base + " [95% ДИ]  P(лучше)")
    for c in cfgs:
        rows = [data[c][i] for i in ids]
        a = agg(rows)
        if c == base:
            tail = ""
        else:
            ci, p = bootstrap([data[base][i] for i in ids], rows)
            tail = f"{ci[1]:+5.1f} [{ci[0]:+5.1f}, {ci[2]:+5.1f}]   {100 * p:4.0f}%"
        print(f"{c:13} | {a['wer']:5.1f} | {a['D']:8.1f} | {a['I']:7.1f} | {a['S']:6.1f} | {a['speech']:12.0f} | {tail}")
    for key, title in (("noise", "по шуму"), ("speakers", "по говорящим")):
        groups = sorted({data[base][i][key] for i in ids})
        print(f"\nWER {title}: " + " | ".join(f"{g}" for g in groups))
        for c in cfgs:
            cells = [agg([data[c][i] for i in ids if data[c][i][key] == g])["wer"] for g in groups]
            print(f"  {c:13} " + " | ".join(f"{v:6.1f}" for v in cells))


if __name__ == "__main__":
    mode, split, *cfgs = sys.argv[1:]
    (run if mode == "run" else report)(split, cfgs)
