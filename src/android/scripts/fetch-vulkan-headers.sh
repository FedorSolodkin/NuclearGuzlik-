#!/usr/bin/env bash
# Vulkan-Headers + SPIR-V-Headers для сборки whisper.cpp/llama.cpp с GGML_VULKAN=ON.
#
# Причина: NDK 25 sysroot несёт vulkan.h C-API, но vulkan.hpp v158 (2020) —
# ggml-vulkan.cpp требует v1.3.234+ (PipelineRobustnessCreateInfoEXT).
# CMake ищет их в app/src/main/cpp/third_party/{vulkan,spirv}-headers/include/,
# и падает если не найдёт (см. app/CMakeLists.txt:13-26).
#
# Скрипт не коммитим в репу (см. .gitignore) — headers регенерируются с pinned
# версии. Так и с CI будет: этот скрипт вызывается перед gradle build.

set -euo pipefail

VULKAN_TAG="v1.3.290"
SPIRV_TAG="vulkan-sdk-1.3.290.0"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
THIRD_PARTY="$REPO_ROOT/app/src/main/cpp/third_party"

mkdir -p "$THIRD_PARTY"
cd "$THIRD_PARTY"

fetch_headers() {
    local name="$1"
    local url="$2"
    local tag="$3"
    local dest="$name-headers"

    if [ -f "$dest/include/.stamp-$tag" ]; then
        echo "  ✓ $dest @ $tag (cached)"
        return
    fi

    echo "  ↓ $dest @ $tag"
    rm -rf "$dest.tmp" "$dest"
    git clone --depth 1 --branch "$tag" --quiet "$url" "$dest.tmp"
    # Оставляем ТОЛЬКО include/ + LICENSE — экономит ~40% места (нам не нужны
    # docs/tests/BUILD.bazel/registry XML для inference-only сборки).
    mkdir -p "$dest/include"
    cp -r "$dest.tmp/include/." "$dest/include/"
    cp "$dest.tmp/"LICENSE* "$dest/" 2>/dev/null || true
    touch "$dest/include/.stamp-$tag"
    rm -rf "$dest.tmp"
    echo "  ✓ $dest ready ($(du -sh "$dest" | cut -f1))"
}

echo "Fetching Vulkan headers into $THIRD_PARTY:"
fetch_headers vulkan \
    "https://github.com/KhronosGroup/Vulkan-Headers.git" \
    "$VULKAN_TAG"
fetch_headers spirv \
    "https://github.com/KhronosGroup/SPIRV-Headers.git" \
    "$SPIRV_TAG"

echo "Done. Sanity check:"
test -f "$THIRD_PARTY/vulkan-headers/include/vulkan/vulkan.hpp" \
    && echo "  ✓ vulkan.hpp present"
test -f "$THIRD_PARTY/spirv-headers/include/spirv/unified1/spirv.hpp" \
    && echo "  ✓ spirv.hpp present"
