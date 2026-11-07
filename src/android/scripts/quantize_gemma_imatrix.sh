#!/usr/bin/env bash
# imatrix-квантизация Gemma-2-2B под наш домен транскриптов.
#
# Скрипт-заготовка для Спринта 4.5 (см. ANALYSIS.md). Запускается на десктопе
# (не на Android'е) — требует собранного llama.cpp с CUDA/Vulkan.
#
# Что делает:
#   1. Из f16 GGUF модели + текстового корпуса транскриптов считает importance
#      matrix (какие веса важны для нашего распределения токенов).
#   2. Использует эту матрицу при квантизации в Q4_K_M — важные веса
#      квантуются точнее, менее важные сильнее ужимаются.
#
# Ожидаемый результат: 1.5 ГБ → ~1.2 ГБ + меньше галлюцинаций на наших
# доменах (медицина/психология/IT), т.к. imatrix «знает», что мы генерим
# JSON reminders на русском, а не Python-код.
#
# Как готовить корпус (docs/gemma-imatrix-corpus.md):
#   - 500-1000 транскриптов из RecordStorage бета-тестеров (с согласия).
#   - Формат — plain text, один транскрипт = один параграф, разделитель — \n\n.
#   - Размер ~5-10 МБ текста нормально; больше — дольше imatrix, но точнее.

set -euo pipefail

LLAMA_CPP="${LLAMA_CPP:-$HOME/tools/llama.cpp}"     # где собран llama.cpp
INPUT_F16="${1:-}"                                  # ggml-org/gemma-2-2b-it-GGUF/gemma-2-2b-it-f16.gguf
CORPUS="${2:-}"                                     # transcripts.txt
OUTPUT_DIR="${3:-./out}"

if [ -z "$INPUT_F16" ] || [ -z "$CORPUS" ]; then
    cat <<EOF
Usage: $0 <input-f16.gguf> <corpus.txt> [output-dir]

Требования:
  - llama.cpp собран с llama-imatrix и llama-quantize (по умолчанию есть в build/).
  - INPUT_F16 — F16 или BF16 версия Gemma-2-2B (не квантованная).
  - CORPUS — plain-text файл с транскриптами, ~5-10 МБ.

Переменные:
  LLAMA_CPP — путь к собранному llama.cpp (default: \$HOME/tools/llama.cpp)
EOF
    exit 1
fi

if [ ! -x "$LLAMA_CPP/build/bin/llama-imatrix" ]; then
    echo "❌ llama-imatrix не найден в $LLAMA_CPP/build/bin/"
    echo "   Собери llama.cpp: cd \$LLAMA_CPP && cmake -B build && cmake --build build -j"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
IMATRIX="$OUTPUT_DIR/imatrix.dat"
OUTPUT_Q4="$OUTPUT_DIR/gemma-2-2b-q4_k_m-voicemind.gguf"

echo "▶ Step 1/2: computing importance matrix from $CORPUS"
# -c 2048 — окно контекста, совпадает с нашим llama_jni.cpp
# --chunks 100 — сколько чанков корпуса пропустить (100 обычно достаточно)
# -ngl 99 — offload всех слоёв на GPU для скорости (CPU-only ~10× медленнее)
"$LLAMA_CPP/build/bin/llama-imatrix" \
    -m "$INPUT_F16" \
    -f "$CORPUS" \
    -o "$IMATRIX" \
    -c 2048 \
    --chunks 100 \
    -ngl 99

echo "▶ Step 2/2: quantizing to Q4_K_M with imatrix"
"$LLAMA_CPP/build/bin/llama-quantize" \
    --imatrix "$IMATRIX" \
    "$INPUT_F16" \
    "$OUTPUT_Q4" \
    Q4_K_M

echo "✓ Готово."
echo "  Imatrix:      $IMATRIX"
echo "  Quantized:    $OUTPUT_Q4 ($(du -h "$OUTPUT_Q4" | cut -f1))"
echo ""
echo "Дальше: замерить hallucination rate до/после на 50 out-of-sample транскриптах."
echo "Апнуть URL в llm-loader (см. LlmHelper / SetupActivity), если результаты лучше baseline."
