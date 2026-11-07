#!/usr/bin/env python3
"""
Исключение шумов с разборчивой чужой речью (выполняется ДО генерации данных).

VAD не может отличить «нашу» речь от чужой — это задача разделения говорящих,
а не чувствительности. В MS-SNSD категория Babble неоднородна: часть файлов —
неразборчивый гул, часть — внятный разговор рядом. Критерий: доля кадров, которые
Silero считает речью (p ≥ 0.5), во всём файле, приведённом к −30 dBFS.
Распределение двугорбое (≤0.03 у шумов, 0.11–0.95 у файлов с речью),
порог MAX_SPEECH_FRACTION — в разрыве.

Выход: data/noise/speech_fraction.json; gen_synth.py пропускает файлы выше порога.
"""
import json
import wave

import numpy as np

import bench

MAX_SPEECH_FRACTION = 0.05


def main():
    res = {}
    for split in ("tune", "eval"):
        for f in sorted((bench.ROOT / "data/noise" / split).glob("*.wav")):
            w = wave.open(str(f))
            x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
            level = 20 * np.log10(np.sqrt((x ** 2).mean()) + 1e-12)
            y = (np.clip(x * 10 ** ((-30 - level) / 20), -1, 1) * 32767).astype(np.int16)
            res[f"{split}/{f.name}"] = float((bench.vad_probs(y) >= 0.5).mean())
    (bench.ROOT / "data/noise/speech_fraction.json").write_text(json.dumps(res, indent=1))
    bad = [k for k, v in res.items() if v > MAX_SPEECH_FRACTION]
    print(f"исключено {len(bad)} из {len(res)}: {', '.join(sorted(bad))}")
    bench.vm().close()


if __name__ == "__main__":
    main()
