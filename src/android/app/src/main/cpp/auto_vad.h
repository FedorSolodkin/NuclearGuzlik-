// Автоматический порог VAD — общий код для приложения (whisper_jni.cpp) и стенда
// (tools/vad_bench/vmbench.cpp): стенд проверяет ровно то, что работает в приложении.
#pragma once
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>
#include "whisper.h"

// ── Автоматический порог VAD («Авто» в настройках) ────────────────────────────
// Исследование и обоснование — tools/vad_bench (конфигурация "gate58"); формулы
// здесь должны совпадать с bench.gated_adaptive, иначе замеры перестанут описывать
// приложение. Порог выбирается для каждого куска (40–58 с) по его собственному фону:
//   • фон — 10% самых тихих кадров (по 512 отсчётов, как кадры Silero);
//   • фон тише −58 dBFS → 0.5: в тишине речь и так отделяется, а низкий порог пускает
//     в Whisper паузы, и тот выбрасывает «пустые» 30-сек окна вместе с фразами;
//   • иначе → 95-й перцентиль вероятности речи, которую Silero даёт фону, + 0.1,
//     в пределах [0.1, 0.6]: спокойный шум (кондиционер, кухня) — порог вниз, ловим
//     дальнюю речь; шум, похожий на речь (кафе, гул голосов), — вверх.
// Замер: WER 39.0 → 37.4 к фиксированному 0.5 (запас 300 мс у обоих), аудио в Whisper +5%.
//
// Проверка покрытия (chooseGuarded): Silero может занизить вероятность речи на узкополосном
// («телефонном») звуке, и при пороге 0.5 от куска остаются крохи. Автопорог этого не видит:
// в записи с цифровой тишиной фон < −58 dBFS, «ворота» оставляют 0.5. Поэтому после выбора порога
// считаем, какая доля «заведомо слышимых» кадров (на 20 дБ выше фона и не тише −45 dBFS) попала в
// отрезки VAD. Покрытие < 0.85 → порог по лесенке 0.35 → 0.25 → 0.15, а если и это не помогает,
// кусок идёт в Whisper без VAD (kNoVad). Замер на стенде (синтетика, 4 «телефонные» записи):
// WER 20–31% → 6–10% (без VAD 4–7%); на остальных записях и на живых r001–r004 не срабатывает.
// Порог 0.95 вместо 0.85 на живой r002 ухудшил результат (35% → 55%), поэтому 0.85.
namespace autovad {
inline constexpr float kAuto = -1.0f;          // vadThreshold < 0 — автоматический режим
inline constexpr float kBase = 0.5f, kLo = 0.1f, kHi = 0.6f, kMargin = 0.1f;
inline constexpr float kFloorDb = -58.0f, kQuietPct = 10.0f, kNoiseQ = 95.0f;
inline constexpr int   kFrame = 512;
// Проверка покрытия речи (см. выше). kNoVad — «этот кусок в Whisper без VAD».
inline constexpr float kNoVad = -2.0f;
inline constexpr float kLoudAboveFloorDb = 20.0f, kLoudAbsDb = -45.0f, kMinCover = 0.85f;
inline constexpr int   kMinLoudFrames = 20;
inline constexpr float kLadder[3] = {0.35f, 0.25f, 0.15f};

// Перцентиль с линейной интерполяцией — как numpy.percentile по умолчанию.
inline float percentile(std::vector<float> v, float q) {
    if (v.empty()) return 0.0f;
    std::sort(v.begin(), v.end());
    const double pos = (v.size() - 1) * (q / 100.0);
    const size_t i = (size_t)pos;
    const double frac = pos - i;
    return (float)(i + 1 < v.size() ? v[i] + (v[i + 1] - v[i]) * frac : v[i]);
}

// Уровень каждого кадра Silero (dBFS); кадры сверх конца звука — −100.
inline std::vector<float> frameEnergy(const std::vector<float>& pcm, int n) {
    std::vector<float> energy(n, -100.0f);
    const int m = std::min(n, (int)(pcm.size() / kFrame));
    for (int f = 0; f < m; f++) {
        double e = 0;
        for (int k = 0; k < kFrame; k++) { double x = pcm[f * kFrame + k]; e += x * x; }
        energy[f] = (float)(10.0 * std::log10(e / kFrame + 1e-12));
    }
    return energy;
}

// Порог по фону куска (формулы — см. выше). cut — уровень, ниже которого кадры считаются фоном.
inline float thresholdFromProbs(const float* probs, int n, const std::vector<float>& energy, float cut) {
    std::vector<float> quietProbs;
    for (int f = 0; f < n; f++) if (energy[f] <= cut) quietProbs.push_back(probs[f]);
    if (cut >= kFloorDb && !quietProbs.empty()) {
        return std::clamp(percentile(quietProbs, kNoiseQ) + kMargin, kLo, kHi);
    }
    return kBase;
}

inline float choose(const std::vector<float>& pcm, const std::string& vadPath, float* floorDbOut = nullptr) {
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    whisper_vad_context* v = whisper_vad_init_from_file_with_params(vadPath.c_str(), cp);
    if (!v) return kBase;
    float thr = kBase;
    if (whisper_vad_detect_speech(v, pcm.data(), (int)pcm.size())) {
        const int n = whisper_vad_n_probs(v);
        const float* probs = whisper_vad_probs(v);
        const std::vector<float> energy = frameEnergy(pcm, n);
        const float cut = percentile(energy, kQuietPct);
        thr = thresholdFromProbs(probs, n, energy, cut);
        if (floorDbOut) *floorDbOut = cut;
    }
    whisper_vad_free(v);
    return thr;
}

// Доля «заведомо слышимых» кадров, попавших в отрезки VAD при пороге thr (1.0 — проверять нечего).
// Требует, чтобы в v уже были вероятности (whisper_vad_detect_speech).
inline float coverage(whisper_vad_context* v, const std::vector<float>& energy, float cut, float thr,
                      int minSpeechMs, int minSilenceMs, int padMs) {
    const int n = (int)energy.size();
    const float lim = std::max(cut + kLoudAboveFloorDb, kLoudAbsDb);
    int loud = 0;
    for (int f = 0; f < n; f++) if (energy[f] > lim) loud++;
    if (loud < kMinLoudFrames) return 1.0f;
    whisper_vad_params vp = whisper_vad_default_params();
    vp.threshold = thr;
    vp.min_speech_duration_ms = minSpeechMs;
    vp.min_silence_duration_ms = minSilenceMs;
    vp.speech_pad_ms = padMs;
    std::vector<char> cov(n, 0);
    if (whisper_vad_segments* segs = whisper_vad_segments_from_probs(v, vp)) {
        for (int i = 0; i < whisper_vad_segments_n_segments(segs); i++) {
            // t0/t1 — сотые доли секунды; 160 отсчётов = 10 мс при 16 кГц
            const int a = (int)std::lround(whisper_vad_segments_get_segment_t0(segs, i)) * 160 / kFrame;
            const int b = (int)std::lround(whisper_vad_segments_get_segment_t1(segs, i)) * 160 / kFrame;
            for (int f = std::max(a, 0); f <= b && f < n; f++) cov[f] = 1;
        }
        whisper_vad_free_segments(segs);
    }
    int hit = 0;
    for (int f = 0; f < n; f++) if (cov[f] && energy[f] > lim) hit++;
    return (float)hit / (float)loud;
}

// choose + проверка покрытия: порог для куска или kNoVad (Whisper без VAD). coverageOut — покрытие
// при исходном пороге (для журнала). Параметры сегментации — те же, что задаёт whisper_jni.cpp.
inline float chooseGuarded(const std::vector<float>& pcm, const std::string& vadPath,
                           float* floorDbOut = nullptr, float* coverageOut = nullptr,
                           int minSpeechMs = 250, int minSilenceMs = 300, int padMs = 300) {
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    whisper_vad_context* v = whisper_vad_init_from_file_with_params(vadPath.c_str(), cp);
    if (!v) return kBase;
    float thr = kBase;
    if (whisper_vad_detect_speech(v, pcm.data(), (int)pcm.size())) {
        const int n = whisper_vad_n_probs(v);
        const float* probs = whisper_vad_probs(v);
        const std::vector<float> energy = frameEnergy(pcm, n);
        const float cut = percentile(energy, kQuietPct);
        thr = thresholdFromProbs(probs, n, energy, cut);
        if (floorDbOut) *floorDbOut = cut;
        const float c0 = coverage(v, energy, cut, thr, minSpeechMs, minSilenceMs, padMs);
        if (coverageOut) *coverageOut = c0;
        if (c0 < kMinCover) {
            float next = kNoVad;
            for (float t : kLadder) {
                if (t < thr && coverage(v, energy, cut, t, minSpeechMs, minSilenceMs, padMs) >= kMinCover) { next = t; break; }
            }
            thr = next;
        }
    }
    whisper_vad_free(v);
    return thr;
}
} // namespace autovad
