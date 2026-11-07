// Общие хелперы для whisper_jni.cpp и llama_jni.cpp.
#pragma once

#include <algorithm>
#include <cstdio>
#include <exception>
#include <stdexcept>
#include <mutex>
#include <string>
#include <vector>
#include <cstdlib>
#include <unistd.h>
#include <android/log.h>
#include "ggml-backend.h"

namespace vm {

// GPU-устройство, которое реально можно использовать (nullptr — только CPU).
inline ggml_backend_dev_t& usable_gpu() {
    static ggml_backend_dev_t dev = nullptr;
    return dev;
}

// Вердикт о GPU общий на процесс. static-переменные inline-функций из этого
// заголовка у libguzlikvoice-jni.so и libguzlikvoice-llm.so СВОИ (каждая .so — своя
// копия), а ggml-vulkan проверяемо бросает "Unsupported device" только на ПЕРВОМ
// обращении к устройству: второй JNI-модуль получал уже закэшированное
// полуинициализированное устройство, считал GPU рабочим, и llama.cpp падал в
// SIGSEGV при загрузке модели (Adreno 618, Redmi Note 10 Pro). Поэтому итог
// первой проверки кладём в переменную окружения процесса — её видят оба модуля.
inline constexpr const char* kGpuVerdictEnv = "GUZLIKVOICE_GPU_VERDICT";

// Бэкенды ggml собраны как отдельные .so (GGML_BACKEND_DL) и грузятся из
// nativeLibraryDir. Реестр бэкендов один на процесс (libggml общая для whisper
// и llama), поэтому грузим его один раз — кто первый из двух JNI пришёл.
//
// ggml-vulkan регистрирует устройство, даже если не сможет с ним работать, и
// бросает исключение только при первом использовании (например, Adreno 618 без
// 16-битных storage-буферов → "Unsupported device"). Поэтому сразу после
// загрузки пробуем инициализировать GPU под try/catch и запоминаем результат.
inline void load_backends_once(const char* libDir) {
    static std::once_flag flag;
    std::call_once(flag, [libDir]() {
        if (ggml_backend_reg_count() == 0) {
            ggml_backend_load_all_from_path(libDir);
        }
        const char* prior = getenv(kGpuVerdictEnv);
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            __android_log_print(ANDROID_LOG_INFO, "GgmlBackends", "device %zu: %s (%s)",
                                i, ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
            auto type = ggml_backend_dev_type(dev);
            if (usable_gpu() || (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU)) continue;
            if (prior != nullptr) {
                // Другой модуль уже проверил это устройство — повторная проверка недостоверна.
                if (prior[0] == '1') usable_gpu() = dev;
                else __android_log_print(ANDROID_LOG_INFO, "GgmlBackends",
                                         "GPU %s marked unusable earlier — using CPU", ggml_backend_dev_name(dev));
                continue;
            }
            try {
                if (ggml_backend_dev_buffer_type(dev) != nullptr) usable_gpu() = dev;
            } catch (const std::exception& e) {
                __android_log_print(ANDROID_LOG_ERROR, "GgmlBackends",
                                    "GPU %s unusable: %s — using CPU", ggml_backend_dev_name(dev), e.what());
            } catch (...) {
                __android_log_print(ANDROID_LOG_ERROR, "GgmlBackends",
                                    "GPU %s unusable (unknown error) — using CPU", ggml_backend_dev_name(dev));
            }
        }
        if (prior == nullptr) setenv(kGpuVerdictEnv, usable_gpu() ? "1" : "0", 1);
    });
}

inline bool has_gpu_device() { return usable_gpu() != nullptr; }

// Число потоков. Замер на Snapdragon 732G (2×A76 + 6×A55), turbo q4_0,
// энкодер 30-сек окна: 6 потоков — 35.7 с, 8 — 45.9 с (декодер 30 → 288 мс/токен),
// 2 «больших» ядра (q5_0) — на 24% медленнее 6 потоков. Матричные ядра ggml
// раздают работу кусками, поэтому маленькие ядра помогают, но занимать ВСЕ
// ядра нельзя: система и UI вытесняют потоки, и барьеры ggml простаивают.
inline int pick_n_threads() {
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 0) n = 4;
    int threads = n <= 4 ? (int)n : (int)std::min<long>(6, n - 2);
    return std::max(2, threads);
}

} // namespace vm
