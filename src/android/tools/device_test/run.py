#!/usr/bin/env python3
"""Прогон тестов VoiceMind на подключённом телефоне.

    python3 tools/device_test/run.py prepare        # собрать эталонные данные (один раз)
    python3 tools/device_test/run.py migration      # перенос данных 3.9 → Room на копии
    python3 tools/device_test/run.py asr            # распознавание эталонов, WER
    python3 tools/device_test/run.py llm [--only ID] # «Выделить главное» на всех моделях
    python3 tools/device_test/run.py mirror         # скачивание с зеркала кодом приложения
    python3 tools/device_test/run.py report         # отчёт по последним результатам

Тесты движков идут на отладочной сборке (com.example.voicemind.dev, ставится рядом
с рабочим приложением): у неё есть run-as, а тесты не трогают настоящие записи.
Сборка тестов: ./gradlew assembleLegacyDebug assembleLegacyDebugAndroidTest.

Эталоны: tools/vad_bench (synth/eval2) и Golos из ~/voicemind-bench/data.
Результаты: tools/device_test/out/ (в git не попадают).
"""
import argparse
import io
import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import adbui as u  # noqa: E402

PKG = "com.example.voicemind.dev"
TEST_RUNNER = f"{PKG}.test/androidx.test.runner.AndroidJUnitRunner"
BENCH = Path.home() / "voicemind-bench" / "data"
CACHE = Path.home() / ".cache" / "voicemind-device-test"
OUT = HERE / "out"
REMOTE_EVAL = f"/sdcard/Android/data/{PKG}/files/eval"

# Синтетика: по одной записи на условие — ближний/дальний голос, тишина/офис/громко.
SYNTH = ["near__silent__0", "near__office__0", "near__loud__0", "far__silent__0",
         "far__room__0", "near+far__office__0", "near+mid+far__room__0", "near+far__loud__0"]
GOLOS_N = 15


# ── подготовка данных ───────────────────────────────────────────────────────────

def prepare():
    import numpy as np
    import pyarrow.parquet as pq
    import soundfile as sf
    asr = CACHE / "asr"
    asr.mkdir(parents=True, exist_ok=True)
    refs = {}
    for name in SYNTH:
        src = BENCH / "synth" / "eval2" / name
        (asr / f"synth_{name}.s16").write_bytes(src.with_suffix(".s16").read_bytes())
        refs[f"synth_{name}"] = src.with_suffix(".ref.txt").read_text().strip()
    # Golos: первые 15 фраз — быстрый набор (golos_<часть>), фразы 15..114 — большой
    # набор по 25 фраз на «запись» (golos100_<часть>_<k>). Между фразами секунда тишины.
    def golos(part, start, n, name):
        t = pq.read_table(BENCH / "golos" / f"{part}_validation.parquet").slice(start, n).to_pylist()
        chunks, words = [], []
        for row in t:
            pcm, sr = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="int16")
            assert sr == 16000, sr
            if pcm.ndim > 1:
                pcm = pcm[:, 0]
            chunks += [pcm, np.zeros(16000, dtype=np.int16)]
            words.append(row["transcription"])
        (asr / f"{name}.s16").write_bytes(np.concatenate(chunks).astype("<i2").tobytes())
        refs[name] = " ".join(words)
    for part in ["crowd", "farfield"]:
        golos(part, 0, GOLOS_N, f"golos_{part}")
        for k in range(4):
            golos(part, GOLOS_N + 25 * k, 25, f"golos100_{part}_{k}")
    # Английская речь: автоопределение языка не должно уводить её в русский.
    jfk = Path(__file__).resolve().parents[2] / "app/src/main/cpp/whisper.cpp/samples/jfk.wav"
    if jfk.exists():
        pcm, sr = sf.read(jfk, dtype="int16")
        (asr / "en_jfk.s16").write_bytes(pcm.astype("<i2").tobytes())
        refs["en_jfk"] = ("and so my fellow americans ask not what your country can do for you "
                          "ask what you can do for your country")
    # Диалоги для разделения по голосам: фразы Golos crowd 115..174 (каждая — один
    # человек, соседние — как правило, разные люди), паузы 0.3–1.0 с.
    diar = CACHE / "diar"; diar.mkdir(exist_ok=True)
    rng = np.random.default_rng(7)
    rows = pq.read_table(BENCH / "golos" / "crowd_validation.parquet").slice(115, 60).to_pylist()
    for k in range(3):
        chunks, spans, t = [], [], 0
        for row in rows[k * 20:(k + 1) * 20]:
            a, sr = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="int16")
            a = a if a.ndim == 1 else a[:, 0]
            spans.append([t * 1000 // 16000, (t + len(a)) * 1000 // 16000, row["transcription"]])
            gap = int(rng.uniform(0.3, 1.0) * 16000)
            chunks += [a, np.zeros(gap, dtype=np.int16)]; t += len(a) + gap
        (diar / f"dialog_{k}.s16").write_bytes(np.concatenate(chunks).astype("<i2").tobytes())
        (diar / f"dialog_{k}.json").write_text(json.dumps(spans, ensure_ascii=False))
    (CACHE / "asr_refs.json").write_text(json.dumps(refs, ensure_ascii=False, indent=1))
    print(f"готово: {len(refs)} аудио в {asr}")


def push_eval(kind, only=None):
    u.sh(f"rm -rf {REMOTE_EVAL}/{kind} {REMOTE_EVAL}/out/{kind}.json", check=False)
    u.sh(f"mkdir -p {REMOTE_EVAL}/{kind}")
    src = {"asr": CACHE / "asr", "diar": CACHE / "diar"}.get(kind, HERE / "data" / "llm")
    for f in sorted(src.iterdir()):
        if f.suffix in (".s16", ".txt") and (not only or only in f.name):
            u.adb("push", str(f), f"{REMOTE_EVAL}/{kind}/{f.name}")


def instrument(test, extra=()):
    args = ["shell", "am", "instrument", "-w", "-e", "class", f"com.example.voicemind.{test}"]
    for k, v in extra:
        args += ["-e", k, v]
    t0 = time.time()
    out = u.adb(*args, TEST_RUNNER, check=False, timeout=6 * 3600)
    ok = "OK (" in out
    print(out.strip()[-1500:])
    print(f"── {test}: {'OK' if ok else 'FAIL'} за {time.time() - t0:.0f} с")
    return ok


def pull(name):
    OUT.mkdir(exist_ok=True)
    data = u.sh(f"cat {REMOTE_EVAL}/out/{name}.json", check=False)
    if data.strip().startswith(("{", "[")):
        (OUT / f"{name}.json").write_text(data)
        return json.loads(data)
    return None


# ── метрики ───────────────────────────────────────────────────────────────────

def norm(s):
    """Нижний регистр, ё→е, без меток говорящих и пунктуации; цифры — словами
    (эталоны Golos записаны словами, а Whisper пишет «900»)."""
    s = s.lower().replace("ё", "е")
    s = re.sub(r"\[[^\]]*\]", " ", s)            # [Говорящий 1] и т.п.
    try:
        from num2words import num2words
        s = re.sub(r"(?<=\d)[\s\u00a0](?=\d{3}\b)", "", s)   # 42 500 → 42500
        s = re.sub(r"\d+", lambda m: " " + num2words(int(m.group()), lang="ru") + " ", s)
    except ImportError:
        pass
    s = re.sub(r"[^\w\s]", " ", s)
    return s.split()


def wer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))


def report_asr(res):
    refs = json.loads((CACHE / "asr_refs.json").read_text())
    print(f"\n{'запись':40} {'аудио':>7} {'время':>7} {'RTF':>5} {'WER':>6}")
    tot_e = tot_w = 0
    for k, v in res.items():
        w = wer(refs[k], v["hyp"])
        n = len(norm(refs[k]))
        tot_e += w * n; tot_w += n
        print(f"{k:40} {v['audio_s']:6.0f}с {v['ms']/1000:6.0f}с {v['ms']/1000/v['audio_s']:5.2f} {w*100:5.1f}%")
    print(f"{'итого (взвешенно по словам)':40} {'':7} {'':7} {'':5} {tot_e/max(1,tot_w)*100:5.1f}%")
    groups = {}
    for k, v in res.items():
        g = re.sub(r"_\d+$", "", k).replace("__0", "")
        e = wer(refs[k], v["hyp"]) * len(norm(refs[k]))
        a = groups.setdefault(g, [0.0, 0]); a[0] += e; a[1] += len(norm(refs[k]))
    if any(a[1] for g, a in groups.items() if g.startswith("golos100")):
        for g, a in sorted(groups.items()):
            if g.startswith("golos100"):
                print(f"{g:40} {'':7} {'':7} {'':5} {a[0]/a[1]*100:5.1f}%  ({a[1]} слов)")


def align_to_utterances(units, spans):
    """Для каждой единицы (слова) — индекс эталонной фразы, по выравниванию текста
    (таймкоды не используем: их и проверяем). Невыровненные слова — к соседям."""
    import difflib
    ref, ref_utt = [], []
    for k, (_, _, text) in enumerate(spans):
        for w in norm(text):
            ref.append(w); ref_utt.append(k)
    hyp, hyp_unit = [], []
    for ui, u in enumerate(units):
        for w in norm(u[2]):
            hyp.append(w); hyp_unit.append(ui)
    utt_of_unit = [None] * len(units)
    sm = difflib.SequenceMatcher(a=hyp, b=ref, autojunk=False)
    for blk in sm.get_matching_blocks():
        for d in range(blk.size):
            utt_of_unit[hyp_unit[blk.a + d]] = ref_utt[blk.b + d]
    for idx in range(len(units)):          # заполняем пропуски ближайшим слева/справа
        if utt_of_unit[idx] is None:
            left = next((utt_of_unit[j] for j in range(idx - 1, -1, -1) if utt_of_unit[j] is not None), None)
            right = next((utt_of_unit[j] for j in range(idx + 1, len(units)) if utt_of_unit[j] is not None), None)
            utt_of_unit[idx] = left if left is not None else right
    matched = {ref_utt[blk.b + d] for blk in sm.get_matching_blocks() for d in range(blk.size)}
    lost = [k for k in range(len(spans)) if k not in matched]
    return utt_of_unit, lost


def report_diar(res):
    """Слова, чей говорящий не совпадает с большинством внутри своей фразы (фраза
    Golos — всегда один человек); смены говорящего внутри фраз; пропавшие фразы;
    ошибка таймкодов слов относительно эталонной фразы."""
    print("Диагностика целостности фраз: это НЕ DER и не точность определения личности говорящего. Для DER используйте diar_score.py с размеченными speaker_id.")
    print(f"\n{'диалог':12} {'слов':>5} {'голосов':>7}  {'оторвано (до → после)':>22}  {'смен внутри':>12}  {'пропало фраз':>12}  {'таймкоды ±1с':>12}")
    tot = {"raw": [0, 0, 0], "snap": [0, 0, 0]}; lost_t = utt_t = 0; in_t = n_t = 0
    for name, r in res.items():
        spans = json.loads((CACHE / "diar" / f"{name}.json").read_text())
        units = r["units"]
        utt, lost = align_to_utterances(units, spans)
        lost_t += len(lost); utt_t += len(spans)
        # таймкод слова внутри своей фразы (с запасом 1 с)
        for u, k in zip(units, utt):
            if k is None: continue
            a, b, _ = spans[k]; mid = (u[0] + u[1]) / 2
            in_t += (a - 1000 <= mid <= b + 1000); n_t += 1
        row = {}
        for key, col in [("raw", 4), ("snap", 3)]:
            by_utt = {}
            for u, k in zip(units, utt):
                if k is not None:
                    by_utt.setdefault(k, []).append(u[col])
            off = sum(sum(s != max(set(v), key=v.count) for s in v) for v in by_utt.values())
            changes = sum(sum(1 for x, y in zip(v, v[1:]) if x != y) for v in by_utt.values())
            n = sum(len(v) for v in by_utt.values())
            row[key] = (off, n, changes)
            t = tot[key]; t[0] += off; t[1] += n; t[2] += changes
        nspk = len({u[3] for u in units})
        print(f"{name:12} {row['snap'][1]:5} {nspk:7}  {row['raw'][0]/max(1,row['raw'][1])*100:8.1f}% → {row['snap'][0]/max(1,row['snap'][1])*100:5.1f}%"
              f"  {row['raw'][2]:5} → {row['snap'][2]:3}  {len(lost):6}/{len(spans)}")
    r, s_ = tot["raw"], tot["snap"]
    print(f"{'ИТОГО':12} {s_[1]:5} {'':7}  {r[0]/max(1,r[1])*100:8.1f}% → {s_[0]/max(1,s_[1])*100:5.1f}%  {r[2]:5} → {s_[2]:3}"
          f"  {lost_t:6}/{utt_t}  {in_t/max(1,n_t)*100:9.0f}%")


def report_llm(res):
    exp = json.loads((HERE / "data" / "llm" / "expected.json").read_text())
    for model, m in res.items():
        print(f"\n■ {model}: загрузка {m.get('load_ms', 0)/1000:.0f} с, {'GPU' if m.get('gpu') else 'CPU'}")
        hit = total = extra = items_n = neg_clean = neg_total = 0
        ms = 0
        for name, r in m.get("structure", {}).items():
            items = [l.lstrip("• ").strip() for l in r["out"].splitlines() if l.strip().startswith("•")]
            e = exp.get(name, {"must": [], "ok": []})
            want, allowed = e["must"], e["must"] + e["ok"]
            found = [p for p in want if any(re.search(p, it, re.I) for it in items)]
            junk = [it for it in items if not any(re.search(p, it, re.I) for p in allowed)]
            hit += len(found); total += len(want); extra += len(junk); items_n += len(items); ms += r["ms"]
            if not want:
                neg_total += 1; neg_clean += not items
            flag = "" if want else (" ВЫДУМАЛА!" if items else " (пусто — верно)")
            print(f"  {name:22} {r['ms']/1000:5.0f} с  найдено {len(found)}/{len(want)}  лишних {len(junk)}{flag}")
            for it in items:
                print(f"      {'✗' if it in junk else '•'} {it}")
        if total:
            print(f"  ИТОГО: полнота {hit}/{total} ({hit/total*100:.0f}%), лишних пунктов {extra} из {items_n}, "
                  f"тексты без задач чистые {neg_clean}/{neg_total}, время {ms/1000:.0f} с")
        if "qa" in m:
            print(f"  вопрос к записи ({m['qa']['ms']/1000:.0f} с): {m['qa']['answer'][:200]}")


# ── сценарии ──────────────────────────────────────────────────────────────────

def guard():
    if u.in_call():
        sys.exit("на телефоне идёт звонок — тесты не запускаю")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["prepare", "migration", "asr", "diar", "llm", "mirror", "report"])
    ap.add_argument("--only", help="llm: id модели; asr: подстрока имени записи")
    a = ap.parse_args()
    if a.cmd == "prepare":
        return prepare()
    guard()
    if a.cmd == "migration":
        ok = instrument("LegacyMigrationDeviceTest")
    elif a.cmd == "asr":
        push_eval("asr", a.only)
        ok = instrument("EngineEvalDeviceTest#asrPipeline")
        res = pull("asr")
        if res:
            report_asr(res)
    elif a.cmd == "diar":
        push_eval("diar")
        ok = instrument("EngineEvalDeviceTest#diarization")
        res = pull("diar")
        if res:
            report_diar(res)
    elif a.cmd == "llm":
        push_eval("llm")
        ok = instrument("EngineEvalDeviceTest#llmModels", [("llm", a.only)] if a.only else [])
        res = pull("llm")
        if res:
            report_llm(res)
    elif a.cmd == "mirror":
        ok = instrument("EngineEvalDeviceTest#mirrorDownload", [("mirror", "1")])
    else:
        for name, fn in [("asr", report_asr), ("diar", report_diar), ("llm", report_llm)]:
            if (OUT / f"{name}.json").exists():
                fn(json.loads((OUT / f"{name}.json").read_text()))
        return
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
