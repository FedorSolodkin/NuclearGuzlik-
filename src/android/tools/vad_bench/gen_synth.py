#!/usr/bin/env python3
"""
Синтетические «разговоры» для исследования чувствительности VAD.

Сессия ~75 с: фразы из Golos (эталонный текст известен) с паузами между ними,
у каждого говорящего свой уровень громкости (близко / средне / далеко), поверх —
реальный фоновый шум MS-SNSD заданной громкости. Плюс одна длинная пауза
(5–12 с, только шум): на ней видно, принимает ли VAD шум за речь.

Уровни откалиброваны по реальной записи с телефона (Redmi Note 10 Pro,
VOICE_RECOGNITION): речь вблизи ≈ −20 dBFS, фон тихой комнаты ≈ −44 dBFS.
  говорящий:  near −20, mid −30, far −40 dBFS (≈ 0.5 м / 1.5 м / 4 м — −6 дБ на удвоение)
  шум:        −60 (тишина, только шум микрофона), −45 (комната), −35 (офис), −28 (кафе/улица) dBFS
Near — фразы из Golos crowd (чистые, близко к микрофону), mid/far — из Golos farfield
(настоящая дальняя речь с реверберацией), дополнительно ослабленные.

Разбиение tune/eval: разные фразы (по хэшу) и РАЗНЫЕ файлы шума (data/noise/tune|eval),
чтобы подобранные параметры проверялись на том, чего метод не видел.

Выход: data/synth/<split>/<id>.s16 (16 кГц mono s16le), <id>.ref.txt, <id>.json
(фразы: чья, где, какой текст), <id>.lab.npy (речь/не речь по кадрам 32 мс —
по чистому сигналу до шума, для кадровых метрик VAD).
"""
import argparse
import hashlib
import io
import json
import os
import wave
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq

SR = 16000
SPEAKER_LEVELS = {"near": -20.0, "mid": -30.0, "far": -40.0}
NOISE_LEVELS = {"silent": -60.0, "room": -45.0, "office": -35.0, "loud": -28.0}
SPEAKER_SETS = {
    "near":          ["near"],
    "near+far":      ["near", "far"],
    "far":           ["far"],
    "near+mid+far":  ["near", "mid", "far"],
}


def active_level_db(x: np.ndarray) -> float:
    """Активный уровень речи (упрощённо по ITU-T P.56): кадры не тише пика−30 дБ."""
    f = 512
    n = len(x) // f
    if n == 0:
        return -100.0
    rms = np.sqrt((x[: n * f].reshape(n, f) ** 2).mean(1) + 1e-12)
    db = 20 * np.log10(rms)
    act = db[db > db.max() - 30]
    return float(10 * np.log10((10 ** (act / 10)).mean()))


FRAME = 512  # кадр Silero в whisper.cpp (32 мс)
LABEL_REL_DB = -25.0   # кадр — «речь», если не тише активного уровня своей фразы на 25 дБ…
LABEL_FLOOR_DB = 10.0  # …и громче её собственного фона хотя бы на 10 дБ


def frame_labels(x: np.ndarray) -> np.ndarray:
    """Где в чистой (до шума) фразе реально звучит голос — по кадрам FRAME."""
    n = len(x) // FRAME
    rms = np.sqrt((x[: n * FRAME].reshape(n, FRAME) ** 2).mean(1) + 1e-12)
    db = 20 * np.log10(rms)
    # Голос — не тише активного уровня на 25 дБ И минимум на 10 дБ громче собственного
    # фона исходной записи (p10 кадров): у Golos свой фон, SNR farfield ~25 дБ, и без
    # второго условия в «речь» попадал фон между словами.
    lab = (db > active_level_db(x) + LABEL_REL_DB) & (db > np.percentile(db, 10) + LABEL_FLOOR_DB)
    out = np.zeros(int(np.ceil(len(x) / FRAME)), dtype=np.uint8)
    out[:n] = lab
    return out


def rms_db(x: np.ndarray) -> float:
    return float(20 * np.log10(np.sqrt((x ** 2).mean()) + 1e-12))


def load_utts(parquet: Path):
    t = pq.read_table(parquet).to_pydict()
    out = []
    for a, txt in zip(t["audio"], t["transcription"]):
        w = wave.open(io.BytesIO(a["bytes"]))
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
        if len(x) < SR or not txt.strip():
            continue
        # path в parquet пустой — ключ фразы (и её tune/eval) берём по хэшу звука.
        out.append((hashlib.sha1(a["bytes"]).hexdigest()[:16], txt.strip(), x))
    return out


def split_of(key: str) -> str:
    return "tune" if int(hashlib.sha1(key.encode()).hexdigest(), 16) % 2 == 0 else "eval"


MAX_SPEECH_FRACTION = 0.05  # см. noise_check.py — шумы с разборчивой чужой речью не берём


def load_noises(d: Path):
    frac_file = d.parent / "speech_fraction.json"
    frac = json.loads(frac_file.read_text()) if frac_file.exists() else {}
    if not frac:
        raise SystemExit("сначала запустите noise_check.py")
    out = []
    for f in sorted(d.glob("*.wav")):
        if frac.get(f"{d.name}/{f.name}", 1.0) > MAX_SPEECH_FRACTION:
            continue
        w = wave.open(str(f))
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
        if rms_db(x) > -70:   # пустые/битые файлы пропускаем
            out.append((f.name, x))
    return out


def make_session(rng, near_pool, far_pool, noises, speakers, noise_level, target_s=75.0):
    audio, ref, marks = [], [], []
    t = 0
    long_pause_at = rng.uniform(0.3, 0.7) * target_s
    long_pause_done = False
    lead = int(rng.uniform(0.5, 1.5) * SR)
    audio.append(np.zeros(lead)); t += lead
    turn = 0
    while t < target_s * SR:
        spk = speakers[turn % len(speakers)] if rng.random() < 0.8 else speakers[rng.integers(len(speakers))]
        turn += 1
        pool = near_pool if spk == "near" else far_pool
        path, txt, x = pool[rng.integers(len(pool))]
        x = x * 10 ** ((SPEAKER_LEVELS[spk] + rng.uniform(-3, 3) - active_level_db(x)) / 20)
        marks.append({"t0": t / SR, "t1": (t + len(x)) / SR, "speaker": spk, "src": path, "text": txt})
        audio.append(x); ref.append(txt); t += len(x)
        if not long_pause_done and t > long_pause_at * SR:
            gap = rng.uniform(5, 12); long_pause_done = True
        else:
            gap = rng.uniform(0.3, 2.5)
        g = int(gap * SR)
        audio.append(np.zeros(g)); t += g
    speech = np.concatenate(audio)
    # Разметка речи по кадрам — по чистому сигналу каждой фразы отдельно (уровень своей фразы),
    # иначе тихие дальние фразы целиком ушли бы в «не речь».
    labels = np.zeros(int(np.ceil(len(speech) / FRAME)) + 1, dtype=np.uint8)
    for m in marks:
        s0 = int(round(m["t0"] * SR))
        lab = frame_labels(speech[s0:int(round(m["t1"] * SR))])
        f0 = s0 // FRAME
        labels[f0:f0 + len(lab)] |= lab[: len(labels) - f0]

    # Шум: случайный файл, случайное смещение, по кругу до длины сессии.
    if noise_level == "silent":
        name, nz = "white", rng.standard_normal(len(speech))
    else:
        name, src = noises[rng.integers(len(noises))]
        start = rng.integers(len(src))
        reps = int(np.ceil((start + len(speech)) / len(src))) + 1
        nz = np.tile(src, reps)[start:start + len(speech)]
    nz = nz * 10 ** ((NOISE_LEVELS[noise_level] - rms_db(nz)) / 20)
    y = speech + nz
    peak = np.abs(y).max()
    if peak > 0.99:   # клиппинг недопустим — ослабляем всё вместе (уровни сдвинутся одинаково)
        y *= 0.99 / peak
    return y, labels[: int(np.ceil(len(y) / FRAME))], " ".join(ref), {"noise": name, "noise_level": noise_level, "speakers": speakers,
                              "duration_s": len(y) / SR, "speech": marks}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.expanduser("~/voicemind-bench"))
    ap.add_argument("--seeds", type=int, default=2, help="сессий на каждое сочетание условий")
    args = ap.parse_args()
    root = Path(args.root)

    crowd = load_utts(root / "data/golos/crowd_validation.parquet")
    farf = load_utts(root / "data/golos/farfield_validation.parquet")
    # eval2 — свежий проверочный набор (фразы и шумы eval, новые сессии, 4 повтора):
    # первый eval был просмотрен при разборе ошибки данных и для финальной проверки не годится.
    for split, pool_split, seeds, stream in (("tune", "tune", args.seeds, 0), ("eval", "eval", args.seeds, 1),
                                             ("eval2", "eval", 4, 2)):
        near_pool = [u for u in crowd if split_of(u[0]) == pool_split]
        far_pool = [u for u in farf if split_of(u[0]) == pool_split]
        noises = load_noises(root / "data/noise" / pool_split)
        out = root / "data/synth" / split
        out.mkdir(parents=True, exist_ok=True)
        n = 0
        for si, (sname, speakers) in enumerate(SPEAKER_SETS.items()):
            for ni, nlevel in enumerate(NOISE_LEVELS):
                for seed in range(seeds):
                    rng = np.random.default_rng([stream, si, ni, seed])
                    y, labels, ref, meta = make_session(rng, near_pool, far_pool, noises, speakers, nlevel)
                    sid = f"{sname}__{nlevel}__{seed}"
                    (out / f"{sid}.s16").write_bytes((np.clip(y, -1, 1) * 32767).astype("<i2").tobytes())
                    np.save(out / f"{sid}.lab.npy", labels)
                    (out / f"{sid}.ref.txt").write_text(ref + "\n", encoding="utf-8")
                    (out / f"{sid}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
                    n += 1
        print(f"{split}: {n} sessions, near utts {len(near_pool)}, far utts {len(far_pool)}, noises {len(noises)}")


if __name__ == "__main__":
    main()
