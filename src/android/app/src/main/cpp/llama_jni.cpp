#include <jni.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.cpp/include/llama.h"
#include "ggml_android.h"
#include "loop_guard.h"
#include "lazy_grammar.h"
#include <memory>

#define TAG  "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Gemma-2 обучена на 8192 токенах. Контекст создаём по размеру задачи: 4096
// хватает на напоминания (чанк ≤5000 симв + промпт + ответ) и экономит ~0.4 ГБ
// KV-кэша; под длинные шаблоны/Q&A контекст пересоздаётся на 8192 (веса не
// перезагружаются, это дёшево).
static constexpr int kCtxSmall = 4096;
static constexpr int kCtxMax   = 8192;
static constexpr int kBatch    = 2048;

// Один model/context на процесс; все обращения под g_mutex — анализ могут
// запросить одновременно запись, история, Q&A и шаблоны.
static std::mutex          g_mutex;
static llama_model*        g_model = nullptr;
static llama_context*      g_ctx   = nullptr;
static const llama_vocab*  g_vocab = nullptr;
static int                 g_n_ctx = 0;
// Потоки, заданные пользователем/тестом скорости (0 = автоматически по числу ядер).
// Новый выбор слова для JSON-ответа (lazy_grammar.h). false — прежний способ, нужен только для сравнения в «Тесте скорости».
static bool                g_lazy_grammar = true;
static int                 g_threads_gen = 0;
static int                 g_threads_batch = 0;
static bool                g_gpu   = false;
static std::string         g_tmpl;              // chat template из GGUF
static std::vector<llama_token> g_cached;       // токены, реально лежащие в KV-кэше
static std::atomic<bool>   g_cancel{false};
// Причина остановки последней генерации (см. LlmHelper.STOP_*).
enum StopReason { STOP_EOG = 0, STOP_LIMIT = 1, STOP_LOOP = 2, STOP_ERROR = 3 };
static int                 g_last_stop = STOP_ERROR;
// Счётчики последнего вызова модели (для «Теста скорости»): промпт, взято из кэша, секунд на промпт, токенов ответа, секунд на ответ.
static double              g_stat_prompt = 0, g_stat_reused = 0, g_stat_prefill_sec = 0, g_stat_gen = 0, g_stat_gen_sec = 0;

static bool abort_cb(void* /*data*/) { return g_cancel.load(); }

static jbyteArray to_jbytes(JNIEnv* env, const std::string& s) {
    jbyteArray arr = env->NewByteArray((jsize)s.size());
    if (arr && !s.empty()) env->SetByteArrayRegion(arr, 0, (jsize)s.size(), (const jbyte*)s.data());
    return arr;
}

static void free_ctx() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    g_n_ctx = 0;
    g_cached.clear();
}

static bool ensure_ctx(int n_ctx) {
    if (g_ctx && g_n_ctx >= n_ctx) return true;
    free_ctx();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t)n_ctx;
    cp.n_batch         = (uint32_t)kBatch;
    cp.n_ubatch        = 512;
    cp.n_threads       = g_threads_gen > 0 ? g_threads_gen : vm::pick_n_threads();
    cp.n_threads_batch = g_threads_batch > 0 ? g_threads_batch : cp.n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    // Gemma-2 чередует слои с окном 4096 и глобальные. swa_full=true (дефолт)
    // держит полный кэш и для оконных слоёв — лишние сотни МБ на телефоне.
    cp.swa_full        = false;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("Failed to create context n_ctx=%d", n_ctx); return false; }
    llama_set_abort_callback(g_ctx, abort_cb, nullptr);
    g_n_ctx = n_ctx;
    LOGI("Context ready: n_ctx=%d threads=%d/%d", n_ctx, cp.n_threads, cp.n_threads_batch);
    return true;
}

static void unload_model() {
    free_ctx();
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_tmpl.clear();
}

static bool load_model(const char* path, bool useGpu) {
    unload_model();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = useGpu ? 99 : 0;
    // Без GPU явно отдаём пустой список устройств: иначе llama.cpp всё равно
    // опрашивает все зарегистрированные (в т.ч. непригодный Vulkan).
    static ggml_backend_dev_t no_devices[] = { nullptr };
    static ggml_backend_dev_t gpu_devices[] = { nullptr, nullptr };
    if (useGpu) { gpu_devices[0] = vm::usable_gpu(); mp.devices = gpu_devices; }
    else        { mp.devices = no_devices; }
    g_model = llama_model_load_from_file(path, mp);
    if (!g_model) return false;
    g_vocab = llama_model_get_vocab(g_model);
    const char* t = llama_model_chat_template(g_model, nullptr);
    // Без template из метаданных llama_chat_apply_template(nullptr) подставил бы
    // ChatML, которого Gemma не знает. У gemma-2 GGUF шаблон всегда есть.
    g_tmpl = t ? t : "gemma";
    g_gpu = useGpu;
    return ensure_ctx(kCtxSmall);
}

// Прогоняет prompt-токены через модель, переиспользуя общий префикс с тем, что
// уже лежит в KV-кэше (системный промпт + few-shot одинаковы у всех чанков;
// в Q&A — одинаковая запись у всех вопросов). Возвращает false при ошибке.
static bool eval_prompt(const std::vector<llama_token>& tokens) {
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t p = 0;
    while (p < g_cached.size() && p < tokens.size() && g_cached[p] == tokens[p]) p++;
    if (p == tokens.size()) p--;   // последний токен надо прогнать заново ради логитов
    if (p == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos)p, -1)) {
        llama_memory_clear(mem, false);
        p = 0;
    }
    g_cached.resize(p);
    LOGI("Prompt: %zu tokens, reused from cache: %zu", tokens.size(), p);
    g_stat_prompt = (double)tokens.size(); g_stat_reused = (double)p; g_stat_prefill_sec = 0;
    const auto t_prefill0 = std::chrono::steady_clock::now();

    for (size_t i = p; i < tokens.size(); i += kBatch) {
        int n = (int)std::min<size_t>(kBatch, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(tokens.data() + i), n);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("prompt decode failed at %zu", i);
            llama_memory_clear(mem, false);
            g_cached.clear();
            return false;
        }
        g_cached.insert(g_cached.end(), tokens.begin() + i, tokens.begin() + i + n);
    }
    g_stat_prefill_sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t_prefill0).count();
    return true;
}

// Длина корректного UTF-8 префикса (не режем многобайтовый символ пополам).
static size_t utf8_complete_len(const std::string& s) {
    const size_t n = s.size();
    size_t i = n;
    while (i > 0 && n - i < 3 && ((unsigned char)s[i - 1] & 0xC0) == 0x80) i--;
    if (i == 0) return n;
    const unsigned char lead = (unsigned char)s[i - 1];
    const size_t need = lead < 0x80 ? 1 : (lead >> 5) == 0x6 ? 2 : (lead >> 4) == 0xE ? 3 : (lead >> 3) == 0x1E ? 4 : 1;
    return n - (i - 1) >= need ? n : i - 1;
}

// Сэмплер «как при обучении» для дообученной модели: top-p/температура без
// greedy, штраф за повторы — по запросу (repeatPenalty <= 1 — без штрафа), и
// детектор зацикливания (без штрафа маленькая модель может уйти в петлю).
struct RawSampling {
    bool  enabled = false;
    float repeatPenalty = 1.0f;
};

static std::string generate(const std::string& prompt, int maxNewTokens, const char* grammar,
                            JNIEnv* env, jobject listener, jmethodID onPiece,
                            RawSampling raw = {}) {
    g_last_stop = STOP_ERROR;
    std::vector<llama_token> tokens(prompt.size() + 16);
    int n = llama_tokenize(g_vocab, prompt.c_str(), (int)prompt.size(),
                           tokens.data(), (int)tokens.size(), /*add_special=*/true, /*parse_special=*/true);
    if (n < 0) { LOGE("Tokenize failed"); return ""; }
    tokens.resize(n);

    // Контекст по размеру задачи. Если даже 8192 не хватает — урезаем ответ,
    // а если не влезает сам prompt — отказываемся (Kotlin режет текст заранее).
    int need = n + maxNewTokens + 8;
    int want = need <= kCtxSmall ? kCtxSmall : kCtxMax;
    if (!ensure_ctx(want)) return "";
    if (n + 64 > g_n_ctx) { LOGE("Prompt too long: %d tokens (n_ctx=%d)", n, g_n_ctx); return ""; }
    if (n + maxNewTokens > g_n_ctx) {
        maxNewTokens = g_n_ctx - n - 1;
        LOGI("maxNewTokens clamped to %d", maxNewTokens);
    }

    if (!eval_prompt(tokens)) return "";

    // Семплер. Структурированный вывод (есть grammar) — детерминированный greedy
    // с лёгким штрафом за повторы: извлечение фактов не должно «творить», а
    // сильный штраф бьёт по кавычкам/запятым JSON и словам из исходника.
    // Свободный текст (шаблоны, Q&A) — мягкое семплирование с фиксированным seed.
    const bool structured = !raw.enabled && grammar && grammar[0];
    std::unique_ptr<lazygrammar::Sampler> lg;     // JSON-ответ: см. lazy_grammar.h
    llama_sampler* smpl = nullptr;                // остальные режимы
    if (structured) {
        lg.reset(new lazygrammar::Sampler(g_vocab, grammar, 64, 1.05f, g_lazy_grammar));
        if (!lg->ok) { LOGE("GBNF parse failed"); return ""; }
    } else {
        smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (!raw.enabled) {
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(256, 1.1f, 0.0f, 0.0f));
        } else if (raw.repeatPenalty > 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(256, raw.repeatPenalty, 0.0f, 0.0f));
        }
        if (raw.enabled) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.3f));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
        } else {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.3f));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
        }
    }

    std::string result;
    std::vector<llama_token> generated;
    size_t emitted = 0;
    int nGen = 0;
    int stop = STOP_LIMIT;
    auto t0 = std::chrono::steady_clock::now();
    while (nGen < maxNewTokens && !g_cancel.load()) {
        // llama_sampler_sample сам вызывает accept — повторный accept
        // продвинул бы грамматику дважды и уронил процесс исключением.
        llama_token tok = lg ? lg->sample(g_ctx, g_vocab) : llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, tok)) { stop = STOP_EOG; break; }
        if (raw.enabled) {
            generated.push_back(tok);
            if (loopguard::tail_is_loop(generated)) {
                LOGE("Loop detected after %d tokens — aborting generation", nGen + 1);
                stop = STOP_LOOP;
                break;
            }
        }

        char piece[256];
        int pl = llama_token_to_piece(g_vocab, tok, piece, sizeof(piece), 0, false);
        if (pl > 0) result.append(piece, pl);
        nGen++;

        if (listener && onPiece) {
            size_t ok = utf8_complete_len(result);
            if (ok > emitted) {
                jbyteArray b = to_jbytes(env, result.substr(emitted, ok - emitted));
                env->CallVoidMethod(listener, onPiece, b);
                env->DeleteLocalRef(b);
                if (env->ExceptionCheck()) { env->ExceptionClear(); }
                emitted = ok;
            }
        }

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, batch) != 0) { LOGE("decode failed at token %d", nGen); stop = STOP_ERROR; break; }
        g_cached.push_back(tok);
    }
    if (smpl) llama_sampler_free(smpl);
    if (lg) LOGI("Sampler: %ld слов, запасной путь %ld раз (lazy=%d)", lg->steps, lg->fallbacks, g_lazy_grammar ? 1 : 0);
    g_last_stop = g_cancel.load() ? STOP_ERROR : stop;

    double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    g_stat_gen = nGen; g_stat_gen_sec = sec;
    LOGI("Generated %d tokens in %.1fs (%.1f tok/s)%s", nGen, sec, sec > 0 ? nGen / sec : 0.0,
         g_cancel.load() ? " [cancelled]" : "");
    if (g_cancel.load()) {
        // KV мог остаться в произвольном состоянии после прерванного decode.
        llama_memory_clear(llama_get_memory(g_ctx), false);
        g_cached.clear();
    }
    result.resize(utf8_complete_len(result));
    return result;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_guzlik_voice_LlmHelper_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath, jboolean useGpu, jstring nativeLibDir) {
    const char* libDir = env->GetStringUTFChars(nativeLibDir, nullptr);
    vm::load_backends_once(libDir);
    env->ReleaseStringUTFChars(nativeLibDir, libDir);

    std::lock_guard<std::mutex> lock(g_mutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    bool gpu = (useGpu == JNI_TRUE) && vm::has_gpu_device();
    LOGI("Loading model: %s (gpu=%d)", path, (int)gpu);
    bool ok = false;
    try {
        ok = load_model(path, gpu);
        if (!ok && gpu) {
            LOGE("GPU load failed, retrying on CPU");
            ok = load_model(path, false);
        }
    } catch (const std::exception& e) {
        LOGE("load threw: %s", e.what());
        ok = false;
    } catch (...) {
        LOGE("load threw unknown exception");
        ok = false;
    }
    env->ReleaseStringUTFChars(modelPath, path);
    if (!ok) { unload_model(); return JNI_FALSE; }
    LOGI("Model ready. vocab=%d gpu=%d template=%.40s", llama_vocab_n_tokens(g_vocab), g_gpu, g_tmpl.c_str());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_guzlik_voice_LlmHelper_nativeIsGpuActive(JNIEnv*, jobject) {
    return g_gpu ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_guzlik_voice_LlmHelper_nativeCancel(JNIEnv*, jobject) {
    g_cancel.store(true);
}

// Chat inference. roles[]/contents[] — параллельные массивы ("user"/"assistant").
// listener (nullable) получает кусочки ответа (UTF-8 байты) по мере генерации.
JNIEXPORT jbyteArray JNICALL
Java_com_guzlik_voice_LlmHelper_nativeInferChat(
        JNIEnv* env, jobject /*thiz*/,
        jobjectArray rolesArr, jobjectArray contentsArr,
        jint maxNewTokens, jstring grammarStr, jobject listener) {

    jsize nMsg = env->GetArrayLength(rolesArr);
    if (nMsg == 0 || nMsg != env->GetArrayLength(contentsArr)) {
        LOGE("nativeInferChat: bad message arrays");
        return to_jbytes(env, "");
    }

    std::vector<std::string> roles(nMsg), contents(nMsg);
    for (jsize i = 0; i < nMsg; ++i) {
        auto jr = (jstring)env->GetObjectArrayElement(rolesArr, i);
        auto jc = (jbyteArray)env->GetObjectArrayElement(contentsArr, i);
        const char* r = env->GetStringUTFChars(jr, nullptr);
        roles[i] = r ? r : "";
        env->ReleaseStringUTFChars(jr, r);
        // content приходит как UTF-8 байты: GetStringUTFChars отдал бы Modified
        // UTF-8, где эмодзи — суррогатные пары, которые токенизатор не поймёт.
        jsize bl = env->GetArrayLength(jc);
        contents[i].resize(bl);
        if (bl > 0) env->GetByteArrayRegion(jc, 0, bl, (jbyte*)&contents[i][0]);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    std::string grammar;
    if (grammarStr) {
        const char* g = env->GetStringUTFChars(grammarStr, nullptr);
        if (g) grammar = g;
        env->ReleaseStringUTFChars(grammarStr, g);
    }

    jmethodID onPiece = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        onPiece = env->GetMethodID(cls, "onPiece", "([B)V");
        env->DeleteLocalRef(cls);
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    // Сброс под блокировкой: отмена, пришедшая для предыдущей задачи, пока эта
    // ждала мьютекс, не должна её оборвать.
    g_cancel.store(false);
    if (!g_model || !g_vocab) { LOGE("nativeInferChat: not initialized"); return to_jbytes(env, ""); }

    std::string result;
    try {
        std::vector<llama_chat_message> msgs(nMsg);
        size_t total = 0;
        for (jsize i = 0; i < nMsg; ++i) {
            msgs[i].role = roles[i].c_str();
            msgs[i].content = contents[i].c_str();
            total += roles[i].size() + contents[i].size();
        }
        std::vector<char> buf(std::max<size_t>(1024, total * 2));
        int32_t w = llama_chat_apply_template(g_tmpl.c_str(), msgs.data(), msgs.size(), true,
                                              buf.data(), (int32_t)buf.size());
        if (w > (int32_t)buf.size()) {
            buf.resize((size_t)w + 1);
            w = llama_chat_apply_template(g_tmpl.c_str(), msgs.data(), msgs.size(), true,
                                          buf.data(), (int32_t)buf.size());
        }
        if (w < 0) { LOGE("llama_chat_apply_template failed: %d", w); return to_jbytes(env, ""); }
        std::string prompt(buf.data(), (size_t)w);
        result = generate(prompt, maxNewTokens, grammar.c_str(), env, listener, onPiece);
    } catch (const std::exception& e) {
        // Любое исключение из llama.cpp (грамматика, аллокация) не должно ронять
        // процесс через std::terminate — отдаём пустой результат.
        LOGE("inference threw: %s", e.what());
        if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), false);
        g_cached.clear();
        result.clear();
    } catch (...) {
        LOGE("inference threw unknown exception");
        if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), false);
        g_cached.clear();
        result.clear();
    }
    return to_jbytes(env, result);
}

// Генерация по уже размеченному промпту (chat template собирает Kotlin) — для
// дообученной модели, которой нужен промпт ровно в том виде, в каком она училась.
// Первый байт ответа — причина остановки (StopReason), дальше — текст UTF-8:
// так причина не перепутается с соседней генерацией из другого потока.
JNIEXPORT jbyteArray JNICALL
Java_com_guzlik_voice_LlmHelper_nativeInferRaw(
        JNIEnv* env, jobject /*thiz*/, jbyteArray promptBytes, jint maxNewTokens, jfloat repeatPenalty) {
    std::string prompt;
    jsize bl = env->GetArrayLength(promptBytes);
    prompt.resize(bl);
    if (bl > 0) env->GetByteArrayRegion(promptBytes, 0, bl, (jbyte*)&prompt[0]);

    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel.store(false);
    g_last_stop = STOP_ERROR;
    if (!g_model || !g_vocab) { LOGE("nativeInferRaw: not initialized"); return to_jbytes(env, ""); }

    std::string result;
    try {
        RawSampling raw;
        raw.enabled = true;
        raw.repeatPenalty = repeatPenalty;
        result = generate(prompt, maxNewTokens, "", env, nullptr, nullptr, raw);
    } catch (const std::exception& e) {
        LOGE("raw inference threw: %s", e.what());
        if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), false);
        g_cached.clear();
        g_last_stop = STOP_ERROR;
        result.clear();
    } catch (...) {
        LOGE("raw inference threw unknown exception");
        if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), false);
        g_cached.clear();
        g_last_stop = STOP_ERROR;
        result.clear();
    }
    LOGI("raw: stop=%d rp=%.2f", g_last_stop, repeatPenalty);
    return to_jbytes(env, std::string(1, (char)g_last_stop) + result);
}

// Число токенов текста (без спец-токенов) — для подгонки ввода под контекст.
JNIEXPORT jint JNICALL
Java_com_guzlik_voice_LlmHelper_nativeTokenCount(JNIEnv* env, jobject, jbyteArray text) {
    jsize bl = env->GetArrayLength(text);
    std::string s(bl, '\0');
    if (bl > 0) env->GetByteArrayRegion(text, 0, bl, (jbyte*)&s[0]);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_vocab) return (jint)(bl / 2);
    // При нехватке буфера llama_tokenize возвращает -(нужное число токенов).
    int n = llama_tokenize(g_vocab, s.c_str(), (int)s.size(), nullptr, 0, false, false);
    return (jint)(n < 0 ? -n : n);
}

// Замер скорости: prefill nPrompt токенов + генерация nGen. Возвращает
// {prefill tok/s, generation tok/s}. Используется, чтобы один раз на устройстве
// выбрать быстрее GPU (Vulkan) или CPU, а также в «Тесте скорости» (nThreads > 0
// задаёт потоки: n_threads — для генерации, n_threads_batch — для промпта).
// Вызывающий держит g_mutex.
static void bench_locked(JNIEnv* env, jint nPrompt, jint nGen, jint nThreads, jint nThreadsBatch, float res[2]) {
    res[0] = 0; res[1] = 0;
    g_cancel.store(false);   // отмена от прошлой генерации не должна обрывать замер
    if (!g_model || !ensure_ctx(kCtxSmall)) return;
    (void)env;
    try {
        if (nThreads > 0) llama_set_n_threads(g_ctx, nThreads, nThreadsBatch > 0 ? nThreadsBatch : nThreads);
        llama_memory_clear(llama_get_memory(g_ctx), false);
        g_cached.clear();
        std::string text;
        for (int i = 0; i < nPrompt; i++) text += "слово ";
        std::vector<llama_token> tok(text.size() + 16);
        int n = llama_tokenize(g_vocab, text.c_str(), (int)text.size(), tok.data(), (int)tok.size(), true, false);
        if (n > nPrompt) n = nPrompt;
        tok.resize(std::max(n, 1));
        auto t0 = std::chrono::steady_clock::now();
        llama_batch b = llama_batch_get_one(tok.data(), (int)tok.size());
        bool ok = llama_decode(g_ctx, b) == 0;
        auto t1 = std::chrono::steady_clock::now();
        llama_token t = tok.back();
        int gen = 0;
        for (; ok && gen < nGen; gen++) {
            llama_batch one = llama_batch_get_one(&t, 1);
            if (llama_decode(g_ctx, one) != 0) break;
        }
        auto t2 = std::chrono::steady_clock::now();
        double pf = std::chrono::duration<double>(t1 - t0).count();
        double tg = std::chrono::duration<double>(t2 - t1).count();
        res[0] = ok && pf > 0 ? (float)(tok.size() / pf) : 0;
        res[1] = gen > 0 && tg > 0 ? (float)(gen / tg) : 0;
        llama_memory_clear(llama_get_memory(g_ctx), false);
        LOGI("Bench (gpu=%d threads=%d/%d): prefill %.1f tok/s (%zu tok), gen %.1f tok/s (%d tok)",
             g_gpu, nThreads, nThreadsBatch, res[0], tok.size(), res[1], gen);
    } catch (const std::exception& e) {
        LOGE("bench threw: %s", e.what());
    }
    if (nThreads > 0) {   // вернуть заданное (или автоматическое) число потоков
        const int gen = g_threads_gen > 0 ? g_threads_gen : vm::pick_n_threads();
        llama_set_n_threads(g_ctx, gen, g_threads_batch > 0 ? g_threads_batch : gen);
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_guzlik_voice_LlmHelper_nativeBench(JNIEnv* env, jobject, jint nPrompt, jint nGen) {
    std::lock_guard<std::mutex> lock(g_mutex);
    float res[2];
    bench_locked(env, nPrompt, nGen, 0, 0, res);
    jfloatArray out = env->NewFloatArray(2);
    env->SetFloatArrayRegion(out, 0, 2, res);
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_guzlik_voice_LlmHelper_nativeBenchThreads(JNIEnv* env, jobject, jint nPrompt, jint nGen,
                                                        jint nThreads, jint nThreadsBatch) {
    std::lock_guard<std::mutex> lock(g_mutex);
    float res[2];
    bench_locked(env, nPrompt, nGen, nThreads, nThreadsBatch, res);
    jfloatArray out = env->NewFloatArray(2);
    env->SetFloatArrayRegion(out, 0, 2, res);
    return out;
}

// Статистика последнего вызова: {токенов в промпте, взято из кэша, секунд на промпт, токенов ответа, секунд на ответ}.
JNIEXPORT jfloatArray JNICALL
Java_com_guzlik_voice_LlmHelper_nativeLastStats(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    float res[5] = {(float)g_stat_prompt, (float)g_stat_reused, (float)g_stat_prefill_sec, (float)g_stat_gen, (float)g_stat_gen_sec};
    jfloatArray out = env->NewFloatArray(5);
    env->SetFloatArrayRegion(out, 0, 5, res);
    return out;
}

// Сбросить кэш запроса, чтобы замер не пользовался результатами прошлого вызова.
JNIEXPORT void JNICALL
Java_com_guzlik_voice_LlmHelper_nativeResetCache(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), false);
    g_cached.clear();
}

// Включить/выключить новый выбор слова для JSON-ответа (по умолчанию включён).
JNIEXPORT void JNICALL
Java_com_guzlik_voice_LlmHelper_nativeSetLazyGrammar(JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_lazy_grammar = enabled == JNI_TRUE;
    LOGI("Lazy grammar: %d", g_lazy_grammar ? 1 : 0);
}

// Задать потоки: gen — для генерации, batch — для разбора промпта; 0 — автоматически.
JNIEXPORT void JNICALL
Java_com_guzlik_voice_LlmHelper_nativeSetThreads(JNIEnv*, jobject, jint gen, jint batch) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_threads_gen = gen > 0 ? gen : 0;
    g_threads_batch = batch > 0 ? batch : 0;
    if (g_ctx) {
        const int g = g_threads_gen > 0 ? g_threads_gen : vm::pick_n_threads();
        llama_set_n_threads(g_ctx, g, g_threads_batch > 0 ? g_threads_batch : g);
    }
    LOGI("Threads set: gen=%d batch=%d (0 = auto)", g_threads_gen, g_threads_batch);
}

// Какие ARM-расширения видит движок (DOTPROD, MATMUL_INT8, FP16 ...) и какие бэкенды загружены.
JNIEXPORT jstring JNICALL
Java_com_guzlik_voice_LlmHelper_nativeSystemInfo(JNIEnv* env, jobject) {
    const char* info = llama_print_system_info();
    return env->NewStringUTF(info ? info : "");
}

JNIEXPORT void JNICALL
Java_com_guzlik_voice_LlmHelper_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_cancel.store(true);
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_model();
    LOGI("LLM freed");
}

} // extern "C"
