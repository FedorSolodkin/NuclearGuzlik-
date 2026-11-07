#!/usr/bin/env bash
# Кладёт Gemma 2 2B Q4_K_M в зеркало моделей (GitHub Release models-v1 репозитория
# dext01/guzlikvoice-models). Без неё приложение качает 1,7 ГБ с Hugging Face,
# а из России это бывает медленно или недоступно.
#
#   GITHUB_TOKEN=<токен с правом contents:write на dext01/guzlikvoice-models> \
#       ./scripts/upload-gemma-to-mirror.sh [путь/к/gemma-2-2b-it-Q4_K_M.gguf]
#
# Облегчённая Gemma (словарь 90 тыс. токенов, LlmModels.GEMMA_TRIM): передайте путь к
# gemma-2-2b-it-Q4_K_M-ru90k.gguf — скрипт узнает её по имени и положит рядом
# GEMMA-NOTICE.txt (условия Gemma требуют помечать изменённые файлы).
#
# Без пути файл сначала скачивается с Hugging Face. Перед загрузкой сверяется
# SHA-256 — та же сумма, что проверяет приложение (LlmModels.GEMMA).
set -euo pipefail

REPO=dext01/guzlikvoice-models
TAG=models-v1
NAME=gemma-2-2b-it-Q4_K_M.gguf
SHA=e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787
SIZE=1708582752
HF=https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/$NAME

: "${GITHUB_TOKEN:?Задайте GITHUB_TOKEN}"
file="${1:-}"
NOTICE=""
if [[ -n "$file" && "$(basename "$file")" == "gemma-2-2b-it-Q4_K_M-ru90k.gguf" ]]; then
    NAME=gemma-2-2b-it-Q4_K_M-ru90k.gguf
    SHA=96c22f048c16b1cf258cfa3bf8a22cb87f41a9be33eaaced4436d130a359f5f2
    SIZE=1391349280
    NOTICE="$(cd "$(dirname "$0")" && pwd)/mirror/GEMMA-NOTICE.txt"
fi
[[ -n "$file" && ! -f "$file" ]] && { echo "Нет файла $file" >&2; exit 1; }
if [[ -z "$file" ]]; then
    # Постоянное место: прерванное скачивание (Ctrl+C, обрыв сети) при
    # следующем запуске продолжится с того же байта, а не начнётся заново.
    dir="$HOME/Downloads/guzlikvoice-gemma"
    mkdir -p "$dir"
    file="$dir/$NAME"
    echo "Скачиваю $NAME с Hugging Face (1,7 ГБ) в $dir — можно прервать и запустить снова…"
    # Сервер HF на медленной сети рвёт HTTP/2-поток (curl: 92), и --retry такое
    # не повторяет — поэтому HTTP/1.1 и свой цикл докачки до полного размера.
    for attempt in $(seq 1 200); do
        have=$( [[ -f "$file" ]] && wc -c < "$file" | tr -d ' ' || echo 0 )
        (( have >= SIZE )) && break
        (( attempt > 1 )) && { echo "Обрыв на $((have / 1000000)) МБ — докачиваю (попытка $attempt)…"; sleep 5; }
        curl -fL --http1.1 --retry 10 --retry-delay 5 -C - -o "$file" "$HF" || true
    done
    have=$(wc -c < "$file" | tr -d ' ')
    (( have == SIZE )) || { echo "Скачано $have байт из $SIZE — запустите скрипт ещё раз." >&2; exit 1; }
fi

echo "Проверяю SHA-256…"
actual=$(shasum -a 256 "$file" | cut -d' ' -f1)
[[ "$actual" == "$SHA" ]] || { echo "Сумма не совпала: $actual" >&2; exit 1; }

api() { curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

release_id=$(api "https://api.github.com/repos/$REPO/releases/tags/$TAG" \
    | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["id"])')
# Файл с таким именем: "uploaded" — уже готово; другое состояние — обрубок
# прерванной загрузки, он мешает загрузить заново, удаляем.
read -r asset_id asset_state < <(api "https://api.github.com/repos/$REPO/releases/$release_id/assets?per_page=100" \
    | python3 -I -c 'import json,sys
for a in json.load(sys.stdin):
    if a["name"] == sys.argv[1]: print(a["id"], a["state"]); break
else: print("- -")' "$NAME")
if [[ "$asset_state" == "uploaded" ]]; then
    echo "${NAME} уже есть в ${TAG} — ничего не делаю."; exit 0
elif [[ "$asset_id" != "-" ]]; then
    echo "Удаляю недогруженный ${NAME} (состояние ${asset_state})…"
    api -X DELETE "https://api.github.com/repos/$REPO/releases/assets/$asset_id"
fi

echo "Загружаю в ${REPO}@${TAG}…"
# 403 здесь — у токена нет права Contents: Read and write на этот репозиторий.
curl -fS --progress-bar -X POST -T "$file" \
    -H "Authorization: Bearer $GITHUB_TOKEN" -H "Content-Type: application/octet-stream" \
    "https://uploads.github.com/repos/$REPO/releases/$release_id/assets?name=$NAME" > /dev/null
echo "Готово: https://github.com/$REPO/releases/download/$TAG/$NAME"

if [[ -n "$NOTICE" ]]; then
    nn=GEMMA-NOTICE.txt
    if ! api "https://api.github.com/repos/$REPO/releases/$release_id/assets?per_page=100" \
        | python3 -I -c 'import json,sys; sys.exit(0 if sys.argv[1] in [a["name"] for a in json.load(sys.stdin)] else 1)' "$nn"; then
        curl -fsS -X POST -T "$NOTICE" -H "Authorization: Bearer $GITHUB_TOKEN" -H "Content-Type: text/plain; charset=utf-8" \
            "https://uploads.github.com/repos/$REPO/releases/$release_id/assets?name=$nn" > /dev/null
        echo "Рядом положено уведомление: https://github.com/$REPO/releases/download/$TAG/$nn"
    fi
fi
