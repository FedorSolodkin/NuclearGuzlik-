cmake_minimum_required(VERSION 3.22.1)
project(voicemind)

# ── Vulkan-Headers + SPIR-V-Headers ──────────────────────────────────────────
# NDK 25 sysroot имеет только vulkan.h (C), а ggml-vulkan.cpp у whisper/llama
# требует vulkan/vulkan.hpp (C++) и spirv/unified1/spirv.hpp. NDK 25 несёт
# Vulkan-Hpp v158 (2020) — слишком старый: whisper.cpp 1.8.4 использует
# PipelineRobustnessCreateInfoEXT (Vulkan 1.3.234+). Кладём свежие headers
# (v1.3.290) в third_party проекта и подставляем их вместо NDK/системных.
set(_vk_hdrs_local     "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/third_party/vulkan-headers/include")
set(_spirv_hdrs_local  "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/third_party/spirv-headers/include")

if (EXISTS "${_vk_hdrs_local}/vulkan/vulkan.hpp")
    # -isystem с BEFORE — чтобы наши headers перекрыли sysroot'овский vulkan.h.
    include_directories(BEFORE SYSTEM "${_vk_hdrs_local}")
    message(STATUS "Using bundled Vulkan-Headers v1.3.290: ${_vk_hdrs_local}")
else()
    message(FATAL_ERROR "Vulkan-Headers not found at ${_vk_hdrs_local}. Run: ./scripts/fetch-vulkan-headers.sh")
endif()

if (EXISTS "${_spirv_hdrs_local}/spirv/unified1/spirv.hpp")
    include_directories(BEFORE SYSTEM "${_spirv_hdrs_local}")
    message(STATUS "Using bundled SPIR-V-Headers: ${_spirv_hdrs_local}")
else()
    message(FATAL_ERROR "SPIR-V-Headers not found at ${_spirv_hdrs_local}. Run: ./scripts/fetch-vulkan-headers.sh")
endif()

# ── Патчи к submodule'ам ─────────────────────────────────────────────────────
# whisper.cpp и llama.cpp — git submodule на зафиксированных коммитах upstream
# (.gitmodules). Наши правки лежат в patches/<имя>/*.patch и накладываются здесь
# при конфигурации: уже наложенный патч пропускается, конфликтующий — ошибка.
find_package(Git REQUIRED)
function(voicemind_apply_patches name)
    set(dir "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/${name}")
    if (NOT EXISTS "${dir}/CMakeLists.txt")
        message(FATAL_ERROR "${name} не скачан. Выполните: git submodule update --init --depth 1")
    endif()
    file(GLOB patches "${CMAKE_CURRENT_SOURCE_DIR}/../patches/${name}/*.patch")
    list(SORT patches)
    foreach(patch IN LISTS patches)
        execute_process(COMMAND "${GIT_EXECUTABLE}" apply --reverse --check "${patch}"
                        WORKING_DIRECTORY "${dir}" RESULT_VARIABLE already OUTPUT_QUIET ERROR_QUIET)
        if (already EQUAL 0)
            continue()
        endif()
        execute_process(COMMAND "${GIT_EXECUTABLE}" apply "${patch}"
                        WORKING_DIRECTORY "${dir}" RESULT_VARIABLE rc ERROR_VARIABLE err)
        if (NOT rc EQUAL 0)
            message(FATAL_ERROR "Не удалось наложить ${patch} на ${name}: ${err}")
        endif()
        message(STATUS "voicemind: applied ${patch}")
    endforeach()
endfunction()
voicemind_apply_patches(whisper.cpp)
voicemind_apply_patches(llama.cpp)

# ── Whisper ──────────────────────────────────────────────────────────────────
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            ON  CACHE BOOL "" FORCE)

add_subdirectory(src/main/cpp/whisper.cpp whisper_build)

# ggml-vulkan.cpp зовёт vkGetPhysicalDeviceFeatures2() как прямой C-символ
# (в обход vulkan.hpp DynamicLoader). NDK-стаб libvulkan.so для API 26 этот
# символ не экспортирует (появился в API 28), из-за чего линк libggml-vulkan.so
# падает с undefined reference. Разрешаем неразрешённые символы в shared libs —
# на устройстве dynamic linker резолвит их из реального libvulkan.so, а если
# устройство слишком старое для Vulkan 1.1 — dlopen(libggml-vulkan.so) не
# пройдёт, ggml пропустит этот бэкенд и оба движка работают на CPU.
if (TARGET ggml-vulkan)
    # --allow-shlib-undefined + --unresolved-symbols=ignore-all: разрешаем
    # неразрешённые символы (vkGetPhysicalDeviceFeatures2 и др. Vulkan 1.1
    # функции), которые NDK-стаб libvulkan.so для API 26 не экспортирует.
    # Плюс явно отменяем --no-undefined через --unresolved-symbols=ignore-all,
    # т.к. эти флаги передаются раньше по командной строке.
    target_link_options(ggml-vulkan PRIVATE
        "-Wl,--unresolved-symbols=ignore-all"
        "-Wl,--allow-shlib-undefined"
    )
    message(STATUS "voicemind: relaxed unresolved-symbol policy for ggml-vulkan target")
else()
    message(WARNING "voicemind: ggml-vulkan target not found after whisper.cpp add_subdirectory")
endif()

add_library(voicemind-jni SHARED src/main/cpp/whisper_jni.cpp)

target_include_directories(voicemind-jni PRIVATE
    src/main/cpp/whisper.cpp/include
    src/main/cpp/whisper.cpp
)

# ggml — ради ggml_backend_load_all_from_path (бэкенды грузятся через dlopen).
# vulkan здесь не линкуем: libggml-vulkan.so подгружается динамически, и если на
# устройстве Vulkan нет/слишком старый — просто не загрузится, JNI не упадёт.
target_link_libraries(voicemind-jni whisper ggml android log)

# ── llama.cpp ────────────────────────────────────────────────────────────────
# NB: llama.cpp видит уже созданную цель ggml (из whisper.cpp) и использует её —
# libggml*.so одна на оба движка (версии ggml у них совпадают: 0.10.2).
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          ON  CACHE BOOL "" FORCE)

add_subdirectory(src/main/cpp/llama.cpp llama_build)

add_library(voicemind-llm SHARED src/main/cpp/llama_jni.cpp)

target_include_directories(voicemind-llm PRIVATE
    src/main/cpp/llama.cpp/include
    src/main/cpp/llama.cpp
)

target_link_libraries(voicemind-llm llama ggml android log)
