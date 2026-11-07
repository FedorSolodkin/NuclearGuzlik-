#!/usr/bin/env python3
"""
Стенд чувствительности VAD: прогоняет записи через тот же конвейер, что
TranscriptionSession в приложении, и считает метрики.

Конвейер (повторяет app/…/TranscriptionSession.kt и whisper_jni.cpp):
  • нарезка: кусок режется, как только накопилось 58 с, в самой тихой 200-мс
    точке в [40, 58] с (энергия по каждому 4-му отсчёту); хвост > 0.5 с — последним куском;
  • промпт: стилевой пример для ru; дальше хвост прошлого куска (≤500 симв.), если он
    «безопасный» (HallucinationFilter.isSafePrompt);
  • Whisper с VAD внутри whisper_full (параметры VAD — из конфигурации);
  • HallucinationFilter.clean по сегментам.
Язык фиксирован "ru" (определение языка вне задачи; ограничение стенда).

Метрики:
  • слова: WER и отдельно доли пропусков (D), замен (S), вставок (I) — по нормализованному тексту;
  • кадры (только синтетика, есть разметка): пропуск речи (miss) и ложная речь (false alarm)
    по кадрам 32 мс — считаются БЕЗ Whisper, мгновенно, по вероятностям Silero;
  • время: сколько секунд аудио ушло в Whisper (speech_s) и время работы.

Кэш (runs/cache): вероятности Silero — по хэшу куска; результат Whisper — по хэшу
куска + промпта + параметров. Повторные прогоны с тем же VAD бесплатны.
"""
import hashlib
import json
import os
import re
import subprocess
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

import numpy as np

ROOT = Path(os.environ.get("VMBENCH_ROOT", Path.home() / "guzlikvoice-bench"))
SR = 16000
FRAME = 512
CHUNK_MAX = 58 * SR
CHUNK_MIN = 40 * SR
QUIET_WIN = 200 * SR // 1000
QUIET_HOP = 20 * SR // 1000
PROMPT_MAX_CHARS = 500
STYLE_PROMPT = ("Ну, в общем, я думаю, что это важно. То есть, на самом деле, если подумать... "
                "Значит, вот как это работает. Расскажите, пожалуйста, поподробнее. "
                "Конечно, я согласен. А вы как считаете? Ладно, спасибо большое!")

# Текущие режимы приложения (AppPrefs: 60 / 150 / 330 → getVadThreshold).
# Параметры VAD приложения: до v4.0 — pad=30, min_silence=100 (конфигурация "base");
# с v4.0 — запас 300 мс (whisper_jni.cpp, конфигурация "pad300").
APP_DEFAULT_VAD = dict(thr=0.5, min_speech=250, min_silence=300, pad=300)


# ── vmbench ───────────────────────────────────────────────────────────────────

class Vmbench:
    def __init__(self, threads: int = 7):
        exe = ROOT / "build/vmbench"
        self.threads = threads
        self.p = subprocess.Popen(
            [str(exe), str(ROOT / "models/ggml-whisper-podlodka-turbo-q4_0.bin"),
             str(ROOT / "models/ggml-silero-v6.2.0.bin")],
            # errors="replace" — как String(bytes, UTF_8) в приложении: Whisper может отдать
            # обрывок многобайтового символа на границе токенов.
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
        assert json.loads(self.p.stdout.readline()).get("ready")
        self.lock = threading.Lock()

    def call(self, cmd: str, **kv) -> dict:
        line = "\t".join([cmd] + [f"{k}={v}" for k, v in kv.items()])
        assert "\n" not in line
        with self.lock:
            self.p.stdin.write(line + "\n"); self.p.stdin.flush()
            out = json.loads(self.p.stdout.readline())
        if "error" in out:
            raise RuntimeError(out["error"])
        return out

    def close(self):
        try:
            self.p.stdin.write("quit\n"); self.p.stdin.flush(); self.p.wait(10)
        except Exception:
            self.p.kill()


_tls = threading.local()
_pool_threads = int(os.environ.get("VMBENCH_THREADS", "7"))


def vm() -> Vmbench:
    if not hasattr(_tls, "vm"):
        _tls.vm = Vmbench(_pool_threads)
    return _tls.vm


def _sha(*parts) -> str:
    h = hashlib.sha1()
    for p in parts:
        h.update(p if isinstance(p, bytes) else str(p).encode())
        h.update(b"\0")
    return h.hexdigest()


def _tmp_pcm(pcm: np.ndarray) -> Path:
    d = ROOT / "runs/tmp"; d.mkdir(parents=True, exist_ok=True)
    path = d / f"{threading.get_ident()}.s16"
    path.write_bytes(pcm.astype("<i2").tobytes())
    return path


def vad_probs(pcm: np.ndarray, gain: float = 1.0) -> np.ndarray:
    key = _sha(pcm.tobytes(), gain)
    f = ROOT / "runs/cache/probs" / f"{key}.npy"
    if f.exists():
        return np.load(f)
    out = np.array(vm().call("probs", pcm=_tmp_pcm(pcm), gain=gain)["probs"], dtype=np.float32)
    f.parent.mkdir(parents=True, exist_ok=True); np.save(f, out)
    return out


def asr(pcm: np.ndarray, prompt: str, vad: Optional[dict], gain: float = 1.0) -> dict:
    v = vad or {}
    params = dict(lang="ru", carry=0, beam=1, vad=1 if vad else 0, gain=gain,
                  thr=v.get("thr", 0.5), min_speech=v.get("min_speech", 250),
                  min_silence=v.get("min_silence", 100), pad=v.get("pad", 30))
    key = _sha(pcm.tobytes(), prompt, json.dumps(params, sort_keys=True))
    f = ROOT / "runs/cache/asr" / f"{key}.json"
    if f.exists():
        return json.loads(f.read_text())
    out = vm().call("asr", pcm=_tmp_pcm(pcm), prompt=prompt, threads=vm().threads, **params)
    f.parent.mkdir(parents=True, exist_ok=True); f.write_text(json.dumps(out, ensure_ascii=False))
    return out


# ── VAD-сегментация (порт whisper_vad_segments_from_probs, whisper.cpp) ───────

def segments_from_probs(probs: np.ndarray, thr: float, min_speech: int = 250, min_silence: int = 100,
                        pad: int = 30, **_) -> list:
    """Отрезки речи в отсчётах — дословный порт whisper.cpp (max_speech не ограничен)."""
    n_window = FRAME
    min_silence_samples = SR * min_silence // 1000
    min_speech_samples = SR * min_speech // 1000
    pad_samples = SR * pad // 1000
    audio_len = len(probs) * n_window
    neg = max(thr - 0.15, 0.01)
    speeches = []
    is_speech = False
    temp_end = prev_end = next_start = cur_start = 0
    has_cur = False
    for i, p in enumerate(probs):
        cur = n_window * i
        if p >= thr and temp_end:
            temp_end = 0
            if next_start < prev_end:
                next_start = cur
        if p >= thr and not is_speech:
            is_speech = True; cur_start = cur; has_cur = True
            continue
        if p < neg and is_speech:
            if not temp_end:
                temp_end = cur
            if cur - temp_end > SR * 98 // 1000:
                prev_end = temp_end
            if cur - temp_end < min_silence_samples:
                continue
            if temp_end - cur_start > min_speech_samples:
                speeches.append([cur_start, temp_end])
            prev_end = next_start = temp_end = 0
            is_speech = False; has_cur = False
            continue
    if has_cur and audio_len - cur_start > min_speech_samples:
        speeches.append([cur_start, audio_len])
    i = 0
    while i < len(speeches) - 1:
        if speeches[i + 1][0] - speeches[i][1] < SR * 200 // 1000:
            speeches[i][1] = speeches[i + 1][1]; del speeches[i + 1]
        else:
            i += 1
    speeches = [s for s in speeches if s[1] - s[0] >= min_speech_samples]
    for i in range(len(speeches)):
        if i == 0:
            speeches[i][0] = max(speeches[i][0] - pad_samples, 0)
        if i < len(speeches) - 1:
            gap = speeches[i + 1][0] - speeches[i][1]
            if gap < 2 * pad_samples:
                speeches[i][1] += gap // 2
                speeches[i + 1][0] = max(speeches[i + 1][0] - gap // 2, 0)
            else:
                speeches[i][1] = min(speeches[i][1] + pad_samples, audio_len)
                speeches[i + 1][0] = max(speeches[i + 1][0] - pad_samples, 0)
        else:
            speeches[i][1] = min(speeches[i][1] + pad_samples, audio_len)
    # в C отрезки переводятся в сотые доли секунды с округлением
    return [(int(round(s / SR * 100)) * SR // 100, int(round(e / SR * 100)) * SR // 100) for s, e in speeches]


# ── HallucinationFilter (порт HallucinationFilter.kt; с v4.0 — вместе с «титрами») ───

_BRACKET_RUN = re.compile(r"(\[[^\]]{2,30}\]\s*){3,}")
_ONLY_BRACKETS = re.compile(r"^(\s*[\[(][^\])]{1,40}[\])]\s*)+$")


_CREDITS = re.compile(
    r"\s*(?:(?:субтитры|титры)\s+(?:сделал|создавал|подготовил[аи]?|делал)\w*|добавил\w*\s+субтитры|"
    r"редактор\s+субтитров|корректор)\s+[^\W\d_][\w.\-]*(?:\s+(?!корректор|редактор)[^\W\d_][\w.\-]*)?[\s.!]*",
    re.IGNORECASE)
_OUTRO_ONLY = re.compile(r"^[\s.!]*(?:продолжение следует|спасибо за просмотр|подписывайтесь на канал)[\s.!…]*$",
                         re.IGNORECASE)


def strip_credits(text: str) -> str:
    return re.sub(r"\s+", " ", _CREDITS.sub(" ", text)).strip()


def _hnorm(w: str) -> str:
    return re.sub(r"^\W+|\W+$", "", w).lower()


def is_garbage(text: str) -> bool:
    t = text.strip()
    if not t or _ONLY_BRACKETS.fullmatch(t):
        return True
    if _OUTRO_ONLY.match(t) or not strip_credits(t):
        return True
    if _BRACKET_RUN.search(t) and not _BRACKET_RUN.sub("", t).strip():
        return True
    words = [w for w in map(_hnorm, t.split()) if w]
    return len(words) >= 20 and len(set(words)) / len(words) < 0.25


def collapse_runs(text: str) -> str:
    toks = text.strip().split()
    if len(toks) <= 5:
        return text.strip()
    out, i = [], 0
    while i < len(toks):
        key = _hnorm(toks[i]); j = i + 1
        while j < len(toks) and key and _hnorm(toks[j]) == key:
            j += 1
        out += toks[i:i + (3 if j - i > 5 else j - i)]
        i = j
    return " ".join(out)


def is_safe_prompt(text: str) -> bool:
    return not is_garbage(text) and collapse_runs(text) == text.strip()


# ── конвейер приложения ───────────────────────────────────────────────────────

def quietest_point(pcm: np.ndarray, frm: int, to: int) -> int:
    if to - frm < QUIET_WIN:
        return to
    x = pcm.astype(np.float64)
    best, best_e, pos = to, float("inf"), frm
    while pos + QUIET_WIN <= to:
        seg = x[pos:pos + QUIET_WIN:4]
        e = float((seg * seg).sum())
        if e < best_e:
            best_e, best = e, pos + QUIET_WIN // 2
        pos += QUIET_HOP
    return best


def app_chunks(pcm: np.ndarray, frame: int = 1600):
    """Нарезка как в TranscriptionSession.pushAudio/flushAndWait (кадры микрофона по 100 мс)."""
    chunks, start, pending = [], 0, 0
    total = len(pcm)
    pos = 0
    while pos < total:
        pending = min(pending + frame, total - start)
        pos = start + pending
        if pending >= CHUNK_MAX:
            cut = quietest_point(pcm[start:start + pending], CHUNK_MIN, min(pending, CHUNK_MAX))
            chunks.append((start, pcm[start:start + cut]))
            start += cut; pending -= cut
    if pending > SR // 2:
        chunks.append((start, pcm[start:start + pending]))
    return chunks


@dataclass
class ChunkCtx:
    """Что известно стратегии VAD о куске: сам звук, вероятности Silero, номер."""
    pcm: np.ndarray
    probs: np.ndarray
    index: int


# Стратегия: кусок → (параметры VAD, усиление). Параметры VAD — как в whisper_vad_params.
Strategy = Callable[[ChunkCtx], tuple]


def fixed(thr=0.5, min_speech=250, min_silence=100, pad=30, gain=1.0) -> Strategy:
    return lambda ctx: (dict(thr=thr, min_speech=min_speech, min_silence=min_silence, pad=pad), gain)


@dataclass
class Result:
    hyp: str = ""
    speech_s: float = 0.0
    asr_ms: float = 0.0
    chunks: list = field(default_factory=list)   # [(start, vad_params, gain, segments_in_samples)]


def run_app(pcm: np.ndarray, strategy: Strategy, with_asr: bool = True) -> Result:
    res = Result()
    tail = ""
    for i, (start, chunk) in enumerate(app_chunks(pcm)):
        ctx = ChunkCtx(chunk, vad_probs(chunk), i)
        vad, gain = strategy(ctx)   # vad=None — без VAD, весь кусок в Whisper
        if vad is None:
            segs = [(0, len(chunk))]
        else:
            probs = ctx.probs if gain == 1.0 else vad_probs(chunk, gain)
            segs = segments_from_probs(probs, **vad)
        res.chunks.append((start, vad, gain, segs))
        if not with_asr:
            continue
        prompt = tail if tail and is_safe_prompt(tail) else STYLE_PROMPT
        out = asr(chunk, prompt, vad, gain)
        res.speech_s += out["speech_s"]; res.asr_ms += out["ms"]
        texts = [collapse_runs(strip_credits(s["text"])) for s in out["segments"] if not is_garbage(s["text"])]
        text = " ".join(t.strip() for t in texts).strip()
        if text:
            res.hyp = (res.hyp + " " + text).strip()
            tail = text[-PROMPT_MAX_CHARS:]
    return res


# ── метрики ───────────────────────────────────────────────────────────────────

def normalize(text: str) -> list:
    from num2words import num2words
    t = text.lower().replace("ё", "е")
    t = re.sub(r"\d+", lambda m: " " + num2words(int(m.group()), lang="ru") + " ", t)
    t = re.sub(r"[^\w\s]|_", " ", t)
    return t.split()


def wer_counts(ref: list, hyp: list) -> dict:
    """Выравнивание Левенштейна: замены, пропуски, вставки."""
    n, m = len(ref), len(hyp)
    d = np.zeros((n + 1, m + 1), dtype=np.int32)
    d[:, 0] = np.arange(n + 1); d[0, :] = np.arange(m + 1)
    for i in range(1, n + 1):
        ri = ref[i - 1]
        for j in range(1, m + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + (ri != hyp[j - 1]))
    i, j, S, D, I = n, m, 0, 0, 0
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i, j] == d[i - 1, j - 1] + (ref[i - 1] != hyp[j - 1]):
            S += ref[i - 1] != hyp[j - 1]; i -= 1; j -= 1
        elif i > 0 and d[i, j] == d[i - 1, j] + 1:
            D += 1; i -= 1
        else:
            I += 1; j -= 1
    return dict(N=n, S=S, D=D, I=I)


def frame_metrics(res: Result, labels: np.ndarray, n_samples: int) -> dict:
    """Пропуск речи и ложная речь по кадрам — по отрезкам VAD всех кусков."""
    covered = np.zeros(len(labels), dtype=bool)
    for start, _vad, _g, segs in res.chunks:
        for s, e in segs:
            covered[(start + s) // FRAME:(start + e + FRAME - 1) // FRAME] = True
    lab = labels.astype(bool)
    return dict(speech=int(lab.sum()), miss=int((lab & ~covered).sum()),
                nonspeech=int((~lab).sum()), fa=int((~lab & covered).sum()))


# ── данные ────────────────────────────────────────────────────────────────────

@dataclass
class Item:
    id: str
    pcm: np.ndarray
    ref: str
    labels: Optional[np.ndarray]
    meta: dict


def load_set(name: str) -> list:
    """name: 'synth/tune', 'synth/eval', 'real' — файлы <id>.s16 + <id>.ref.txt (+ .lab.npy, .json)."""
    d = ROOT / "data" / name
    items = []
    for f in sorted(d.glob("*.s16")):
        ref = f.with_suffix(".ref.txt")
        if not ref.exists():
            continue
        lab = f.with_suffix(".lab.npy")
        meta = f.with_suffix(".json")
        items.append(Item(f.stem, np.fromfile(f, dtype="<i2"), ref.read_text(encoding="utf-8").strip(),
                          np.load(lab) if lab.exists() else None,
                          json.loads(meta.read_text(encoding="utf-8")) if meta.exists() else {}))
    return items


# ── адаптивные стратегии ──────────────────────────────────────────────────────

def frame_energy_db(pcm: np.ndarray, n: int) -> np.ndarray:
    """Уровень каждого кадра Silero (dBFS) — первые n кадров куска."""
    x = pcm.astype(np.float64) / 32768
    m = min(n, len(x) // FRAME)
    e = np.full(n, -100.0)
    e[:m] = 10 * np.log10((x[:m * FRAME].reshape(m, FRAME) ** 2).mean(1) + 1e-12)
    return e


def noise_adaptive(quiet_pct=15.0, noise_q=95.0, margin=0.1, lo=0.1, hi=0.6,
                   pad=300, min_silence=300, min_speech=250) -> Strategy:
    """
    Кандидат D: порог по фону самого куска.
      1. Самые тихие quiet_pct% кадров куска считаем фоном (в разговоре паузы есть всегда).
      2. Какую вероятность речи Silero даёт этому фону — её noise_q-й перцентиль.
      3. Порог = этот уровень + margin, в пределах [lo, hi].
    Спокойный фон → порог вниз (ловим тихую/дальнюю речь); фон похожий на речь → вверх.
    """
    def strat(ctx: ChunkCtx):
        p = ctx.probs
        e = frame_energy_db(ctx.pcm, len(p))
        quiet = e <= np.percentile(e, quiet_pct)
        noise_p = float(np.percentile(p[quiet], noise_q)) if quiet.any() else 0.5
        thr = float(np.clip(noise_p + margin, lo, hi))
        return dict(thr=thr, min_speech=min_speech, min_silence=min_silence, pad=pad), 1.0
    return strat


def gated_adaptive(floor_db=-40.0, quiet_pct=10.0, noise_q=95.0, margin=0.1, lo=0.1, hi=0.6,
                   base_thr=0.5, pad=300, min_silence=300, min_speech=250) -> Strategy:
    """
    Кандидат D с «воротами»: порог опускаем только при ГРОМКОМ фоне.

    В тишине речь и так хорошо отделяется при 0.5, а низкий порог пускает в Whisper
    паузы: окно 30 с становится почти пустым, и защита Whisper (no_speech_thold)
    выбрасывает его целиком вместе с настоящими фразами (замер: тишина 20.3 → 24.3 WER).
      фон (уровень тихих кадров) < floor_db → base_thr;
      иначе → порог по вероятности, которую Silero даёт фону (+margin), в [lo, hi].
    """
    def strat(ctx: ChunkCtx):
        p = ctx.probs
        e = frame_energy_db(ctx.pcm, len(p))
        cut = np.percentile(e, quiet_pct)
        quiet = e <= cut
        if cut < floor_db or not quiet.any():
            thr = base_thr
        else:
            thr = float(np.clip(np.percentile(p[quiet], noise_q) + margin, lo, hi))
        return dict(thr=thr, min_speech=min_speech, min_silence=min_silence, pad=pad), 1.0
    return strat
