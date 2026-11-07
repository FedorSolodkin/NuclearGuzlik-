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
namespace autovad {
inline constexpr float kAuto = -1.0f;          // vadThreshold < 0 — автоматический режим
inline constexpr float kBase = 0.5f, kLo = 0.1f, kHi = 0.6f, kMargin = 0.1f;
inline constexpr float kFloorDb = -58.0f, kQuietPct = 10.0f, kNoiseQ = 95.0f;
inline constexpr int   kFrame = 512;

// Перцентиль с линейной интерполяцией — как numpy.percentile по умолчанию.
inline float percentile(std::vector<float> v, float q) {
    if (v.empty()) return 0.0f;
    std::sort(v.begin(), v.end());
    const double pos = (v.size() - 1) * (q / 100.0);
    const size_t i = (size_t)pos;
    const double frac = pos - i;
    return (float)(i + 1 < v.size() ? v[i] + (v[i + 1] - v[i]) * frac : v[i]);
}

inline float choose(const std::vector<float>& pcm, const std::string& vadPath, float* floorDbOut = nullptr) {
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    whisper_vad_context* v = whisper_vad_init_from_file_with_params(vadPath.c_str(), cp);
    if (!v) return kBase;
    float thr = kBase;
    if (whisper_vad_detect_speech(v, pcm.data(), (int)pcm.size())) {
        const int n = whisper_vad_n_probs(v);
        const float* probs = whisper_vad_probs(v);
        std::vector<float> energy(n, -100.0f);
        const int m = std::min(n, (int)(pcm.size() / kFrame));
        for (int f = 0; f < m; f++) {
            double e = 0;
            for (int k = 0; k < kFrame; k++) { double x = pcm[f * kFrame + k]; e += x * x; }
            energy[f] = (float)(10.0 * std::log10(e / kFrame + 1e-12));
        }
        const float cut = percentile(energy, kQuietPct);
        std::vector<float> quietProbs;
        for (int f = 0; f < n; f++) if (energy[f] <= cut) quietProbs.push_back(probs[f]);
        if (cut >= kFloorDb && !quietProbs.empty()) {
            thr = std::clamp(percentile(quietProbs, kNoiseQ) + kMargin, kLo, kHi);
        }
        if (floorDbOut) *floorDbOut = cut;
    }
    whisper_vad_free(v);
    return thr;
}
} // namespace autovad
