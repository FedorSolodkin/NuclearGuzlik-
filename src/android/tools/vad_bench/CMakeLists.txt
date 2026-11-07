# Стенд для исследования VAD/чувствительности на компьютере (не входит в APK).
# Собирает тот же whisper.cpp (submodule + patches/), что и приложение, но под CPU хоста.
cmake_minimum_required(VERSION 3.22)
project(vad_bench CXX C)
set(CMAKE_CXX_STANDARD 17)

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/whisper.cpp")
add_subdirectory(${WHISPER_DIR} whisper_build)

add_executable(vmbench vmbench.cpp)
target_link_libraries(vmbench PRIVATE whisper)
target_include_directories(vmbench PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
