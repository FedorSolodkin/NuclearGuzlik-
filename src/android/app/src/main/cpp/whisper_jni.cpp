#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <cstdio>
#include <memory>
#include <cctype>
#include "whisper.h"
#include "ggml_android.h"
#include "auto_vad.h"
#include <android/log.h>

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Один контекст на процесс. Все обращения — под g_mutex: расшифровку зовут и
// TranscriptionSession, и экран записи (переразбор/диаризация), а release может
// прийти из UI-потока посреди whisper_full — без мьютекса это use-after-free.
static std::mutex         g_mutex;
static whisper_context*   g_ctx = nullptr;
static bool               g_gpu_active = false;
static std::string        g_model_path;

// Отмена — по токену вызывающего (у каждой TranscriptionSession свой), а не одним
// общим флагом. Раньше nativeAbort ставил общий флаг, а nativeTranscribe сбрасывал
// его, только дождавшись g_mutex. Отсюда две беды:
//  • отмена, пришедшая, пока вызов ждал мьютекс, терялась — чанк считался целиком;
//  • отмена записи обрывала чужой вызов (импорт файла на том же контексте), и из
//    импортированной расшифровки молча пропадал кусок.
// Отменённые токены запоминаются навсегда: вызов этой сессии, дошедший до
// мьютекса позже отмены, тоже не запустится. Токен 0 — «не отменяемый».
static std::mutex                 g_tok_mutex;      // охраняет два поля ниже
static std::unordered_set<jlong>  g_aborted_tokens;
static jlong                      g_running_token = 0;
static std::atomic<bool>  g_abort{false};       // отменён идущий сейчас вызов
static std::atomic<bool>  g_abort_all{false};   // nativeFree: прервать что угодно

static bool abort_cb(void* /*user_data*/) { return g_abort.load() || g_abort_all.load(); }

// Под g_mutex: вызов с [token] начинается. false — его уже отменили.
static bool begin_call(jlong token) {
    std::lock_guard<std::mutex> lock(g_tok_mutex);
    g_running_token = token;
    const bool aborted = token != 0 && g_aborted_tokens.count(token) > 0;
    g_abort.store(aborted);
    return !aborted && !g_abort_all.load();
}

static void end_call() {
    std::lock_guard<std::mutex> lock(g_tok_mutex);
    g_running_token = 0;
    g_abort.store(false);
}

static jbyteArray to_jbytes(JNIEnv* env, const std::string& s) {
    // Байты UTF-8 отдаём как есть, декодирует Kotlin. NewStringUTF требует
    // Modified UTF-8 и под CheckJNI (debuggable-сборка) роняет процесс на
    // оборванной многобайтовой последовательности.
    jbyteArray arr = env->NewByteArray((jsize)s.size());
    if (arr && !s.empty()) env->SetByteArrayRegion(arr, 0, (jsize)s.size(), (const jbyte*)s.data());
    return arr;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath, jboolean useGpu, jstring nativeLibDir) {

    const char* libDir = env->GetStringUTFChars(nativeLibDir, nullptr);
    vm::load_backends_once(libDir);
    env->ReleaseStringUTFChars(nativeLibDir, libDir);

    std::lock_guard<std::mutex> lock(g_mutex);

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s (useGpu=%d)", path, (int)useGpu);

    if (g_ctx) { whisper_free(g_ctx); g_ctx = nullptr; }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu    = (useGpu == JNI_TRUE) && vm::has_gpu_device();
    cparams.flash_attn = true;

    auto safe_init = [&](whisper_context_params p) -> whisper_context* {
        try {
            return whisper_init_from_file_with_params(path, p);
        } catch (const std::exception& e) {
            LOGE("whisper init threw: %s", e.what());
        } catch (...) {
            LOGE("whisper init threw unknown exception");
        }
        return nullptr;
    };
    g_ctx = safe_init(cparams);

    // Fallback: GPU есть, но инициализация на нём упала (драйвер, нехватка
    // памяти, компиляция шейдеров) — пробуем CPU.
    if (!g_ctx && cparams.use_gpu) {
        LOGE("GPU init failed, retrying on CPU");
        cparams.use_gpu = false;
        g_ctx = safe_init(cparams);
    }
    g_model_path = g_ctx ? path : "";
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_ctx) {
        LOGE("Failed to load model");
        g_gpu_active = false;
        return JNI_FALSE;
    }
    g_gpu_active = cparams.use_gpu;
    LOGI("Model loaded OK (gpu=%d, threads=%d), system_info: %s",
         g_gpu_active ? 1 : 0, vm::pick_n_threads(), whisper_print_system_info());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeIsGpuActive(JNIEnv*, jobject) {
    return g_gpu_active ? JNI_TRUE : JNI_FALSE;
}

// Отменяет все вызовы с [token]: идущий прерывает, ещё не начатые не запустятся.
JNIEXPORT void JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeAbort(JNIEnv*, jobject, jlong token) {
    if (token == 0) return;
    std::lock_guard<std::mutex> lock(g_tok_mutex);
    g_aborted_tokens.insert(token);
    if (g_running_token == token) g_abort.store(true);
}


// Возвращает UTF-8 байты: фразы и слова (формат — см. ниже у result). Таймкоды —
// в шкале исходного pcm (VAD whisper.cpp пересчитывает их обратно сам).
//   vadModelPath  — пусто = без VAD (например, прогрев на тишине)
//   carryPrompt   — повторять initialPrompt в каждом 30-сек окне (глоссарий)
//   beamSize      — ≤1 = greedy, иначе beam search
//   audioCtx      — 0 = полное окно; >0 — укороченное (для замеров)
//   language      — "ru" / "en" (выбирается заранее, см. nativeDetectLanguage)
JNIEXPORT jbyteArray JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeTranscribe(
        JNIEnv* env, jobject /*thiz*/,
        jshortArray pcmData, jstring initialPrompt, jboolean carryPrompt,
        jstring vadModelPath, jfloat vadThreshold, jint beamSize, jint audioCtx, jstring language,
        jlong cancelToken) {

    jsize len = env->GetArrayLength(pcmData);
    if (len == 0) return to_jbytes(env, "");

    std::vector<float> pcmF(len);
    {
        jshort* raw = env->GetShortArrayElements(pcmData, nullptr);
        for (jsize i = 0; i < len; i++) pcmF[i] = (float)raw[i] / 32768.0f;
        env->ReleaseShortArrayElements(pcmData, raw, JNI_ABORT);
    }

    std::string prompt, vadPath, lang = "ru";
    if (language) {
        const char* p = env->GetStringUTFChars(language, nullptr);
        if (p && (strcmp(p, "ru") == 0 || strcmp(p, "en") == 0)) lang = p;
        env->ReleaseStringUTFChars(language, p);
    }
    if (initialPrompt) {
        const char* p = env->GetStringUTFChars(initialPrompt, nullptr);
        if (p) prompt = p;
        env->ReleaseStringUTFChars(initialPrompt, p);
    }
    if (vadModelPath) {
        const char* p = env->GetStringUTFChars(vadModelPath, nullptr);
        if (p) vadPath = p;
        env->ReleaseStringUTFChars(vadModelPath, p);
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx) {
        LOGE("nativeTranscribe: context is null");
        return to_jbytes(env, "!ERROR\tWhisper context is not loaded");
    }
    if (!begin_call(cancelToken)) {
        end_call();
        LOGI("nativeTranscribe: cancelled before start");
        return to_jbytes(env, "!CANCELLED");
    }
    struct CallGuard { ~CallGuard() { end_call(); } } callGuard;

    const bool beam = beamSize > 1;
    whisper_full_params params = whisper_full_default_params(
            beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam) params.beam_search.beam_size = beamSize;
    params.n_threads        = vm::pick_n_threads();
    // Язык задаётся явно: auto-detect whisper гонял бы лишний проход энкодера
    // на каждый чанк и выбирал из ~100 языков. С "ru" английская речь
    // переводится на русский — поэтому язык каждого чанка определяется заранее
    // (nativeDetectLanguage, ~5 с речи).
    params.language         = lang.c_str();
    params.translate        = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.single_segment   = false;
    params.max_tokens       = 0;
    // Anti-hallucination фильтры (дефолты whisper.cpp 1.8.4, ставим явно).
    params.suppress_blank   = true;
    params.suppress_nst     = true;
    params.no_speech_thold  = 0.6f;
    params.entropy_thold    = 2.4f;
    params.logprob_thold    = -1.0f;

    if (!prompt.empty()) {
        params.initial_prompt       = prompt.c_str();
        params.carry_initial_prompt = (carryPrompt == JNI_TRUE);
    }

    // VAD whisper.cpp (Silero): пропускает тишину — энкодер не тратит время на
    // пустые 30-сек окна, меньше галлюцинаций на паузах. Таймкоды сегментов
    // остаются в шкале исходного аудио.
    if (!vadPath.empty()) {
        params.vad            = true;
        params.vad_model_path = vadPath.c_str();
        params.vad_params     = whisper_vad_default_params();
        if (vadThreshold < 0) {
            float floorDb = 0, cover = 1;
            params.vad_params.threshold = autovad::chooseGuarded(pcmF, vadPath, &floorDb, &cover);
            if (params.vad_params.threshold == autovad::kNoVad) {
                // VAD отрезал слышимую речь (узкополосный звук) и понижение порога не помогло:
                // этот кусок идёт в Whisper целиком, как при выключенном VAD.
                params.vad = false;
                params.vad_params.threshold = autovad::kBase;
                LOGI("auto VAD: noise floor %.1f dBFS, coverage %.2f → без VAD", floorDb, cover);
            } else {
                LOGI("auto VAD: noise floor %.1f dBFS, coverage %.2f → threshold %.2f", floorDb, cover, params.vad_params.threshold);
            }
        } else {
            params.vad_params.threshold = vadThreshold;
        }
        // Запас вокруг речи: по умолчанию 30 мс — VAD срезал начала и концы слов.
        // Замер на стенде (tools/vad_bench, 32 сессии, 2299 слов): WER 44.7 → 39.0
        // [ДИ −8.6…−3.2 п.п.]; 500 мс не лучше, а аудио в Whisper больше.
        params.vad_params.speech_pad_ms           = 300;
        params.vad_params.min_silence_duration_ms = 300;
    }

    // audio_ctx > 0 — укороченное окно энкодера (только для замера скорости:
    // полное окно — 1500 фреймов = 30 с, на слабом CPU это минуты).
    if (audioCtx > 0) params.audio_ctx = audioCtx;

    // Таймкоды отдельных токенов → слова со временем и уверенностью: нужны,
    // чтобы резать текст по смене говорящего внутри фразы и подсвечивать
    // слова, в которых модель не уверена. Эвристика по timestamp-токенам
    // дешёвая (без DTW).
    params.token_timestamps = true;

    params.abort_callback           = abort_cb;
    params.abort_callback_user_data = nullptr;

    LOGI("Transcribing %d samples (%.1f sec, lang=%s, threads=%d, gpu=%d, vad=%d, beam=%d, carry=%d)...",
         len, len / 16000.0f, params.language, params.n_threads, g_gpu_active ? 1 : 0,
         params.vad ? 1 : 0, beam ? beamSize : 1, params.carry_initial_prompt ? 1 : 0);

    int rc = -1;
    try {
        rc = whisper_full(g_ctx, params, pcmF.data(), (int)pcmF.size());
    } catch (const std::exception& e) {
        LOGE("whisper_full threw: %s", e.what());
    } catch (...) {
        LOGE("whisper_full threw unknown exception");
    }
    if (rc != 0) {
        LOGE("whisper_full failed or aborted: %d", rc);
        if (g_abort.load() || g_abort_all.load()) return to_jbytes(env, "!CANCELLED");
        return to_jbytes(env, "!ERROR\twhisper_full returned " + std::to_string(rc));
    }

    // Формат (UTF-8), по строке на запись:
    //   S \t startMs \t endMs \t текст фразы
    //   W \t startMs \t endMs \t уверенность(0..1000) \t слово  — слова этой фразы
    std::string result;
    const whisper_token eot = whisper_token_eot(g_ctx);
    int nSeg = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < nSeg; i++) {
        const char* seg = whisper_full_get_segment_text(g_ctx, i);
        if (!seg) continue;
        std::string text(seg);
        for (auto& c : text) if (c == '\t' || c == '\n') c = ' ';
        result += "S\t" + std::to_string(whisper_full_get_segment_t0(g_ctx, i) * 10) + "\t"
               +  std::to_string(whisper_full_get_segment_t1(g_ctx, i) * 10) + "\t" + text + "\n";

        // Слова фразы. Таймкоды токенов whisper.cpp при включённом VAD остаются
        // во «сжатом» времени (без вырезанных пауз). Переводим каждое слово через
        // то же отображение, что и фразы (whisper_full_map_vad_time, наш патч):
        // фраза часто покрывает несколько кусков речи, и сдвиг «от начала фразы»
        // уводил слова после вырезанной паузы на секунды раньше (замер на телефоне:
        // до 5 с) — ломались переход по тапу и разделение по голосам.
        struct W { std::string text; int64_t t0, t1; float p; };
        std::vector<W> words;
        const int nTok = whisper_full_n_tokens(g_ctx, i);
        for (int t = 0; t < nTok; t++) {
            whisper_token_data td = whisper_full_get_token_data(g_ctx, i, t);
            if (td.id >= eot) continue;   // служебные токены ([_BEG_], таймкоды, …)
            const char* tt = whisper_full_get_token_text(g_ctx, i, t);
            std::string piece = tt ? tt : "";
            // Новое слово начинается с пробела; кусок без пробела — продолжение слова.
            const bool starts = !piece.empty() && piece[0] == ' ';
            if (starts) piece.erase(0, 1);
            if (starts || words.empty()) words.push_back({"", td.t0, td.t1, 1.0f});
            W& w = words.back();
            w.text += piece;
            w.t1 = std::max(w.t1, td.t1);
            w.p = std::min(w.p, td.p);
        }
        const int64_t s0 = whisper_full_get_segment_t0(g_ctx, i);
        const int64_t s1 = whisper_full_get_segment_t1(g_ctx, i);
        for (auto& w : words) {
            if (w.text.empty()) continue;
            for (auto& c : w.text) if (c == '\t' || c == '\n') c = ' ';
            int64_t a0 = std::clamp(whisper_full_map_vad_time(g_ctx, w.t0), s0, s1);
            int64_t a1 = std::clamp(whisper_full_map_vad_time(g_ctx, w.t1), a0, s1);
            // Уверенность — целым числом в тысячных: printf с %f зависит от локали
            // (на ru_RU даёт запятую, которую Kotlin не разберёт).
            const int permille = (int)std::lround(std::clamp(w.p, 0.0f, 1.0f) * 1000.0f);
            result += "W\t" + std::to_string(a0 * 10) + "\t" + std::to_string(a1 * 10) + "\t"
                   +  std::to_string(permille) + "\t" + w.text + "\n";
        }
    }

    LOGI("Done. Segments=%d, bytes=%zu", nSeg, result.size());
    return to_jbytes(env, result);
}

// Context lifetime is one boundary pass, not one clip. Main ASR keeps its
// fast context; this CPU/DTW context is always freed by the caller's finally.
static std::string refine_word_timing(whisper_context* ctx, const std::vector<float>& pcm,
                                      const std::string& lang) {
    const int len = static_cast<int>(pcm.size());
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = vm::pick_n_threads();
    params.language = lang.c_str();
    params.print_progress = params.print_realtime = params.print_timestamps = false;
    params.token_timestamps = true;
    params.no_context = true;
    params.audio_ctx = std::clamp((((len + 319) / 320 + 64 + 63) / 64) * 64, 256, 1500);
    params.abort_callback = abort_cb;
    const int rc = whisper_full(ctx, params, pcm.data(), len);
    if (g_abort.load() || g_abort_all.load()) return "!CANCELLED";
    if (rc != 0) return "!ERROR\tНе удалось уточнить границы слов";
    struct Word { std::string text; int64_t first = -1, last = -1; float p = 1.0f; };
    std::vector<Word> words;
    const auto eot = whisper_token_eot(ctx);
    for (int s = 0; s < whisper_full_n_segments(ctx); ++s) {
        for (int t = 0; t < whisper_full_n_tokens(ctx, s); ++t) {
            const auto td = whisper_full_get_token_data(ctx, s, t);
            if (td.id >= eot) continue;
            const char* token = whisper_full_get_token_text(ctx, s, t);
            std::string piece = token ? token : "";
            const bool starts = !piece.empty() && piece[0] == ' ';
            if (starts) piece.erase(0, 1);
            if (starts || words.empty()) words.emplace_back();
            auto& w = words.back(); w.text += piece; w.p = std::min(w.p, td.p);
            const bool punctuation = piece == "«" || piece == "»" || piece == "—" || piece == "…";
            const bool lexical = !punctuation && std::any_of(piece.begin(), piece.end(),
                [](unsigned char c) { return c >= 128 || std::isalnum(c); });
            if (lexical && td.t_dtw >= 0) {
                if (w.first < 0) w.first = td.t_dtw;
                w.last = td.t_dtw;
            }
        }
    }
    words.erase(std::remove_if(words.begin(), words.end(), [](const Word& w) {
        return w.text.empty() || w.first < 0 || w.last < w.first;
    }), words.end());
    std::string text;
    for (const auto& w : words) { if (!text.empty()) text += ' '; text += w.text; }
    for (auto& c : text) if (c == '\t' || c == '\n') c = ' ';
    const int64_t duration = len * 1000LL / 16000;
    std::string result = "S\t0\t" + std::to_string(duration) + "\t" + text + "\n";
    for (size_t i = 0; i < words.size(); ++i) {
        auto& w = words[i];
        const int64_t left = i ? (words[i - 1].last + w.first) * 5 : w.first * 10 - 100;
        const int64_t right = i + 1 < words.size() ? (w.last + words[i + 1].first) * 5 : w.last * 10 + 100;
        // Midpoints alone would stretch a word through the silence until the
        // next speaker. Bound each word by its own lexical DTW anchors too.
        const int64_t a = std::clamp<int64_t>(std::max(left, w.first * 10 - 100), 0, duration - 1);
        const int64_t b = std::clamp<int64_t>(std::min(right, w.last * 10 + 100), a + 1, duration);
        for (auto& c : w.text) if (c == '\t' || c == '\n') c = ' ';
        result += "W\t" + std::to_string(a) + "\t" + std::to_string(b) + "\t"
            + std::to_string((int)std::lround(std::clamp(w.p, 0.0f, 1.0f) * 1000)) + "\t" + w.text + "\n";
    }
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeOpenWordTimingSession(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || g_model_path.empty()) return 0;
    auto cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = false;
    cp.dtw_token_timestamps = true;
    const int layers = whisper_model_n_text_layer(g_ctx);
    const int width = whisper_model_n_audio_state(g_ctx);
    if (layers == 4 && width == 1280) cp.dtw_aheads_preset = WHISPER_AHEADS_LARGE_V3_TURBO;
    else if (layers == 12 && width == 768) cp.dtw_aheads_preset = WHISPER_AHEADS_SMALL;
    else if (layers == 4 && width == 384) cp.dtw_aheads_preset = WHISPER_AHEADS_TINY;
    else return 0;
    return reinterpret_cast<jlong>(whisper_init_from_file_with_params(g_model_path.c_str(), cp));
}

JNIEXPORT void JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeCloseWordTimingSession(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (handle) whisper_free(reinterpret_cast<whisper_context*>(handle));
}

JNIEXPORT jbyteArray JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeRefineWordTiming(
        JNIEnv* env, jobject, jlong handle, jshortArray pcmData, jstring language, jlong cancelToken) {
    const jsize len = env->GetArrayLength(pcmData);
    if (!handle || len <= 0 || len > 16 * 16000) return to_jbytes(env, "");
    const char* rawLanguage = env->GetStringUTFChars(language, nullptr);
    const std::string lang = rawLanguage;
    env->ReleaseStringUTFChars(language, rawLanguage);
    std::vector<float> pcm(len);
    jshort* raw = env->GetShortArrayElements(pcmData, nullptr);
    for (int i = 0; i < len; ++i) pcm[i] = raw[i] / 32768.0f;
    env->ReleaseShortArrayElements(pcmData, raw, JNI_ABORT);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!begin_call(cancelToken)) { end_call(); return to_jbytes(env, "!CANCELLED"); }
    struct CallGuard { ~CallGuard() { end_call(); } } callGuard;
    try {
        return to_jbytes(env, refine_word_timing(reinterpret_cast<whisper_context*>(handle), pcm, lang));
    } catch (const std::exception& e) {
        LOGE("Boundary DTW failed: %s", e.what());
        return to_jbytes(env, "!ERROR\tНе удалось уточнить границы слов");
    }
}

// Язык речи в pcm — СТРОГО "ru" или "en" (что-то одно из двух), "" если речи
// не нашлось. Обычный auto-detect whisper выбирает из ~100 языков и на коротком
// или шумном куске путает русский с украинским/болгарским — нам такое не нужно.
//
// Язык определяется по первым kDetectSec секундам речи (Silero VAD вырезает
// тишину) на отдельном whisper_state с окном энкодера ровно под эту речь —
// в разы дешевле полного 30-сек окна. Отдельный state ещё и не зависит от
// audio_ctx, оставшегося в общем от прошлого вызова (после прогрева — 5 с).
JNIEXPORT jstring JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeDetectLanguage(
        JNIEnv* env, jobject /*thiz*/, jshortArray pcmData, jstring vadModelPath, jfloat vadThreshold) {

    jsize len = env->GetArrayLength(pcmData);
    if (len == 0) return env->NewStringUTF("");
    std::vector<float> pcmF(len);
    {
        jshort* raw = env->GetShortArrayElements(pcmData, nullptr);
        for (jsize i = 0; i < len; i++) pcmF[i] = (float)raw[i] / 32768.0f;
        env->ReleaseShortArrayElements(pcmData, raw, JNI_ABORT);
    }
    std::string vadPath;
    if (vadModelPath) {
        const char* p = env->GetStringUTFChars(vadModelPath, nullptr);
        if (p) vadPath = p;
        env->ReleaseStringUTFChars(vadModelPath, p);
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx) return env->NewStringUTF("");
    const int threads = vm::pick_n_threads();

    // Речь без пауз. Замер на 10 записях (6 en, 4 ru): уже по 5 с все
    // определены верно с p ≥ 0.99, а 10 с на CPU стоят в 2-4 раза дороже.
    static constexpr int kDetectSec = 5;
    static constexpr int kMaxSpeech = kDetectSec * WHISPER_SAMPLE_RATE;
    static constexpr int kMinSpeech = 2 * WHISPER_SAMPLE_RATE;
    // Доля английского среди {ru, en}, с которой кусок считаем английским.
    static constexpr float kEnMinShare = 0.9f;
    std::vector<float> speech;
    bool vadRan = false;
    if (!vadPath.empty()) {
        whisper_vad_context_params vcp = whisper_vad_default_context_params();
        vcp.use_gpu   = false;   // потоки — по умолчанию (1, см. патч в whisper.cpp)
        whisper_vad_context* vctx = whisper_vad_init_from_file_with_params(vadPath.c_str(), vcp);
        if (vctx) {
            whisper_vad_params vp = whisper_vad_default_params();
            // Для определения языка автоподбор не нужен: берём первые секунды явной речи.
            vp.threshold = vadThreshold < 0 ? autovad::kBase : vadThreshold;
            whisper_vad_segments* segs = whisper_vad_segments_from_samples(vctx, vp, pcmF.data(), (int)pcmF.size());
            const int n = segs ? whisper_vad_segments_n_segments(segs) : 0;
            for (int i = 0; i < n && (int)speech.size() < kMaxSpeech; i++) {
                // t0/t1 — в сотых долях секунды.
                int s0 = std::clamp((int)(whisper_vad_segments_get_segment_t0(segs, i) * WHISPER_SAMPLE_RATE / 100), 0, (int)len);
                int s1 = std::clamp((int)(whisper_vad_segments_get_segment_t1(segs, i) * WHISPER_SAMPLE_RATE / 100), s0, (int)len);
                s1 = std::min(s1, s0 + kMaxSpeech - (int)speech.size());
                speech.insert(speech.end(), pcmF.begin() + s0, pcmF.begin() + s1);
            }
            vadRan = segs != nullptr;
            if (segs) whisper_vad_free_segments(segs);
            whisper_vad_free(vctx);
        } else {
            LOGE("detectLanguage: VAD init failed, using raw audio");
        }
    }
    // Без VAD (или он не поднялся) — начало аудио как есть.
    if (!vadRan) {
        speech.assign(pcmF.begin(), pcmF.begin() + std::min((int)len, kMaxSpeech));
    }
    if ((int)speech.size() < kMinSpeech) {
        LOGI("detectLanguage: only %.1f s of speech — undecided", speech.size() / (float)WHISPER_SAMPLE_RATE);
        return env->NewStringUTF("");
    }

    std::string lang;
    whisper_state* st = nullptr;
    try {
        st = whisper_init_state(g_ctx);
        std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
        // Окно энкодера: 1500 фреймов на 30 с → 50 на секунду речи.
        const int nCtx = std::min(whisper_n_audio_ctx(g_ctx),
                                  (int)(speech.size() * 50 / WHISPER_SAMPLE_RATE) + 1);
        if (st && whisper_set_audio_ctx_with_state(g_ctx, st, nCtx) == 0
               && whisper_pcm_to_mel_with_state(g_ctx, st, speech.data(), (int)speech.size(), threads) == 0
               && whisper_lang_auto_detect_with_state(g_ctx, st, 0, threads, probs.data()) >= 0) {
            const float pRu = probs[whisper_lang_id("ru")];
            const float pEn = probs[whisper_lang_id("en")];
            // Русский — основной язык приложения, а ошибка в сторону английского
            // дороже: Whisper с lang=en ПЕРЕВОДИТ русскую речь. Шумный кусок с
            // английскими названиями давал p(en)=0.79 — английский берём только
            // при явном перевесе.
            const float enShare = pEn / std::max(1e-6f, pEn + pRu);
            lang = enShare >= kEnMinShare ? "en" : "ru";
            LOGI("detectLanguage: %.1f s speech, p(ru)=%.3f p(en)=%.3f en_share=%.3f → %s",
                 speech.size() / (float)WHISPER_SAMPLE_RATE, pRu, pEn, enShare, lang.c_str());
        } else {
            LOGE("detectLanguage: whisper failed");
        }
    } catch (const std::exception& e) {
        LOGE("detectLanguage threw: %s", e.what());
    } catch (...) {
        LOGE("detectLanguage threw unknown exception");
    }
    if (st) whisper_free_state(st);
    return env->NewStringUTF(lang.c_str());
}

JNIEXPORT void JNICALL
Java_com_guzlik_voice_WhisperCppEngine_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_abort_all.store(true);   // если идёт расшифровка — прервём, чтобы не ждать минутами
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_model_path.clear();
        g_gpu_active = false;
        LOGI("Model freed");
    }
    // Контекста больше нет — вызовы, ждущие мьютекс, выйдут сами (g_ctx == nullptr).
    g_abort_all.store(false);
}

} // extern "C"
