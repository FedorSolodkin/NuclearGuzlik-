// vmbench — сервер для python-стенда (tools/vad_bench/bench.py).
// Модель Whisper грузится один раз, команды идут по строке в stdin, ответы — JSON в stdout.
//
// Команды (поля через TAB, значения key=value):
//   probs   pcm=<путь s16le 16k mono>
//       → {"frame_ms":32,"probs":[...]}  вероятности речи Silero по кадрам
//   segs    pcm=<путь> thr= min_speech= min_silence= pad=
//       → {"segs":[[t0,t1],...]}  отрезки речи в сотых секунды (сегментация whisper.cpp)
//   asr     pcm=<путь> lang=ru prompt=<текст> carry=0|1 beam=1 threads=N
//           vad=0|1 thr=0.5 min_speech=250 min_silence=100 pad=30 gain=1.0
//       → {"segments":[{"t0":ms,"t1":ms,"text":...}],"ms":время,"speech_s":секунд речи после VAD}
//
// Параметры распознавания — как в app/src/main/cpp/whisper_jni.cpp (nativeTranscribe):
// при изменении там — менять и здесь, иначе стенд перестанет отражать приложение.
#include "whisper.h"
#include "auto_vad.h"   // app/src/main/cpp — тот же код, что в приложении
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

static std::string vad_model;

static std::map<std::string, std::string> parse(const std::string& line, std::string& cmd) {
    std::map<std::string, std::string> kv;
    std::stringstream ss(line);
    std::string tok;
    bool first = true;
    while (std::getline(ss, tok, '\t')) {
        if (first) { cmd = tok; first = false; continue; }
        auto eq = tok.find('=');
        if (eq != std::string::npos) kv[tok.substr(0, eq)] = tok.substr(eq + 1);
    }
    return kv;
}

static std::vector<float> load_pcm(const std::string& path, float gain) {
    std::ifstream f(path, std::ios::binary);
    std::vector<char> raw((std::istreambuf_iterator<char>(f)), {});
    std::vector<float> out(raw.size() / 2);
    const int16_t* s = reinterpret_cast<const int16_t*>(raw.data());
    for (size_t i = 0; i < out.size(); i++) {
        float v = s[i] / 32768.0f * gain;
        out[i] = v > 1.f ? 1.f : (v < -1.f ? -1.f : v);
    }
    return out;
}

static std::string esc(const std::string& s) {
    std::string o;
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if (c == '\n') o += "\\n";
        else if (c == '\t') o += "\\t";
        else if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
        else o += c;
    }
    return o;
}

static float fget(std::map<std::string, std::string>& kv, const char* k, float d) {
    return kv.count(k) ? std::strtof(kv[k].c_str(), nullptr) : d;
}

static void cmd_probs(std::map<std::string, std::string>& kv) {
    auto pcm = load_pcm(kv["pcm"], fget(kv, "gain", 1.f));
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    cp.n_threads = 1;  // как в приложении (patch: VAD в 1 поток)
    whisper_vad_context* v = whisper_vad_init_from_file_with_params(vad_model.c_str(), cp);
    if (!v || !whisper_vad_detect_speech(v, pcm.data(), (int)pcm.size())) {
        std::cout << "{\"error\":\"vad failed\"}" << std::endl;
        if (v) whisper_vad_free(v);
        return;
    }
    int n = whisper_vad_n_probs(v);
    float* p = whisper_vad_probs(v);
    std::string out = "{\"frame_ms\":32,\"probs\":[";
    char b[16];
    for (int i = 0; i < n; i++) { snprintf(b, sizeof b, i ? ",%.4f" : "%.4f", p[i]); out += b; }
    out += "]}";
    std::cout << out << std::endl;
    whisper_vad_free(v);
}

// segs pcm=… thr= min_speech= min_silence= pad= → отрезки речи (сотые секунды) из самого whisper.cpp.
// Нужна для проверки, что python-порт сегментации (bench.py) совпадает с C.
static void cmd_segs(std::map<std::string, std::string>& kv) {
    auto pcm = load_pcm(kv["pcm"], fget(kv, "gain", 1.f));
    whisper_vad_params vp = whisper_vad_default_params();
    vp.threshold = fget(kv, "thr", 0.5f);
    vp.min_speech_duration_ms = (int)fget(kv, "min_speech", 250);
    vp.min_silence_duration_ms = (int)fget(kv, "min_silence", 100);
    vp.speech_pad_ms = (int)fget(kv, "pad", 30);
    whisper_vad_context_params cp = whisper_vad_default_context_params();
    cp.n_threads = 1;
    whisper_vad_context* v = whisper_vad_init_from_file_with_params(vad_model.c_str(), cp);
    whisper_vad_segments* segs = v ? whisper_vad_segments_from_samples(v, vp, pcm.data(), (int)pcm.size()) : nullptr;
    std::string out = "{\"segs\":[";
    for (int i = 0; segs && i < whisper_vad_segments_n_segments(segs); i++) {
        char b[64];
        snprintf(b, sizeof b, "%s[%.0f,%.0f]", i ? "," : "", whisper_vad_segments_get_segment_t0(segs, i),
                 whisper_vad_segments_get_segment_t1(segs, i));
        out += b;
    }
    out += "]}";
    std::cout << out << std::endl;
    if (segs) whisper_vad_free_segments(segs);
    if (v) whisper_vad_free(v);
}

// autothr pcm=… → {"thr":…,"floor":…} — автопорог кода приложения (auto_vad.h).
static void cmd_autothr(std::map<std::string, std::string>& kv) {
    auto pcm = load_pcm(kv["pcm"], 1.f);
    float floorDb = 0;
    float thr = autovad::choose(pcm, vad_model, &floorDb);
    char b[96];
    snprintf(b, sizeof b, "{\"thr\":%.4f,\"floor\":%.2f}", thr, floorDb);
    std::cout << b << std::endl;
}

static void cmd_asr(whisper_context* ctx, std::map<std::string, std::string>& kv) {
    auto pcm = load_pcm(kv["pcm"], fget(kv, "gain", 1.f));
    const int beam = (int)fget(kv, "beam", 1);
    whisper_full_params p = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam > 1) p.beam_search.beam_size = beam;
    std::string lang = kv.count("lang") ? kv["lang"] : "ru";
    std::string prompt = kv["prompt"];
    p.n_threads = (int)fget(kv, "threads", 6);
    p.audio_ctx = (int)fget(kv, "actx", 0);   // >0 — укороченное окно энкодера
    p.language = lang.c_str();
    p.translate = false;
    p.print_progress = p.print_realtime = p.print_timestamps = false;
    p.single_segment = false;
    p.max_tokens = 0;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.no_speech_thold = 0.6f;
    p.entropy_thold = 2.4f;
    p.logprob_thold = -1.0f;
    if (!prompt.empty()) {
        p.initial_prompt = prompt.c_str();
        p.carry_initial_prompt = fget(kv, "carry", 0) > 0.5f;
    }
    if (fget(kv, "vad", 1) > 0.5f) {
        p.vad = true;
        p.vad_model_path = vad_model.c_str();
        p.vad_params = whisper_vad_default_params();
        p.vad_params.threshold = fget(kv, "thr", 0.5f);
        p.vad_params.min_speech_duration_ms = (int)fget(kv, "min_speech", 250);
        p.vad_params.min_silence_duration_ms = (int)fget(kv, "min_silence", 100);
        p.vad_params.speech_pad_ms = (int)fget(kv, "pad", 30);
    }
    p.token_timestamps = true;

    // Сколько секунд речи VAD отдаст Whisper — та же функция сегментации, что внутри whisper_full.
    double speech_s = pcm.size() / 16000.0;
    if (p.vad) {
        whisper_vad_context_params cp = whisper_vad_default_context_params();
        cp.n_threads = 1;
        if (auto* v = whisper_vad_init_from_file_with_params(vad_model.c_str(), cp)) {
            if (auto* segs = whisper_vad_segments_from_samples(v, p.vad_params, pcm.data(), (int)pcm.size())) {
                speech_s = 0;
                for (int i = 0; i < whisper_vad_segments_n_segments(segs); i++)
                    speech_s += (whisper_vad_segments_get_segment_t1(segs, i) - whisper_vad_segments_get_segment_t0(segs, i)) / 100.0;
                whisper_vad_free_segments(segs);
            }
            whisper_vad_free(v);
        }
    }

    auto t0 = std::chrono::steady_clock::now();
    int rc = whisper_full(ctx, p, pcm.data(), (int)pcm.size());
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (rc != 0) { std::cout << "{\"error\":\"whisper_full " << rc << "\"}" << std::endl; return; }
    std::string out = "{\"segments\":[";
    int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; i++) {
        if (i) out += ",";
        out += "{\"t0\":" + std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) +
               ",\"t1\":" + std::to_string(whisper_full_get_segment_t1(ctx, i) * 10) +
               ",\"text\":\"" + esc(whisper_full_get_segment_text(ctx, i)) + "\"}";
    }
    char b[96];
    snprintf(b, sizeof b, "],\"ms\":%.0f,\"speech_s\":%.2f}", ms, speech_s);
    out += b;
    std::cout << out << std::endl;
}

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: vmbench <whisper.bin> <silero.bin>\n"); return 1; }
    vad_model = argv[2];
    whisper_log_set([](ggml_log_level, const char*, void*) {}, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(argv[1], cp);
    if (!ctx) { fprintf(stderr, "cannot load %s\n", argv[1]); return 1; }
    std::cout << "{\"ready\":true}" << std::endl;
    std::string line;
    while (std::getline(std::cin, line)) {
        std::string cmd;
        auto kv = parse(line, cmd);
        if (cmd == "probs") cmd_probs(kv);
        else if (cmd == "asr") cmd_asr(ctx, kv);
        else if (cmd == "segs") cmd_segs(kv);
        else if (cmd == "autothr") cmd_autothr(kv);
        else if (cmd == "quit") break;
        else std::cout << "{\"error\":\"unknown command\"}" << std::endl;
    }
    whisper_free(ctx);
    return 0;
}
