#!/usr/bin/env bash
# Публикует пилотный APK GitHub-релизом в публичном репозитории (по умолчанию
# dext01/VoiceMind): скачать можно без входа в GitHub.
#
#   GITHUB_TOKEN=<классический токен с public_repo> \
#       ./scripts/publish-github-release.sh [путь/к/APK] [owner/repo]
#
# Без пути берётся последний APK из app/build/outputs/apk/store/release/.
# Заметки — docs/releases/v<версия>.md ({{SHA256}} подставляется сам).
# Релиз с таким тегом уже есть — файл в нём заменяется.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${GITHUB_TOKEN:?Задайте GITHUB_TOKEN}"
apk="${1:-$(ls -t app/build/outputs/apk/store/release/*.apk | head -1)}"
REPO="${2:-dext01/VoiceMind}"
[[ -f "$apk" ]] || { echo "Нет APK: $apk" >&2; exit 1; }

version=$(basename "$apk" | sed -E 's/^VoiceMind-v([^-]+(-pilot)?)-.*$/\1/')
tag="v${version}"
name="VoiceMind-${version}.apk"
notes_file="docs/releases/${tag}.md"
[[ -f "$notes_file" ]] || { echo "Нет заметок к релизу: $notes_file" >&2; exit 1; }
sha=$(shasum -a 256 "$apk" | cut -d' ' -f1)

api() { curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

body=$(python3 -I -c 'import json,sys; print(json.dumps({
    "tag_name": sys.argv[1], "name": "VoiceMind " + sys.argv[2],
    "body": open(sys.argv[3], encoding="utf-8").read().replace("{{SHA256}}", sys.argv[4]),
    "prerelease": "pilot" in sys.argv[2]}))' "$tag" "$version" "$notes_file" "$sha")

release_id=$(api "https://api.github.com/repos/${REPO}/releases/tags/${tag}" 2>/dev/null \
    | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["id"])' || true)
if [[ -z "$release_id" ]]; then
    echo "Создаю релиз ${tag} в ${REPO}…"
    release_id=$(api -X POST "https://api.github.com/repos/${REPO}/releases" -d "$body" \
        | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["id"])')
else
    echo "Релиз ${tag} уже есть — обновляю заметки и файл…"
    api -X PATCH "https://api.github.com/repos/${REPO}/releases/${release_id}" -d "$body" > /dev/null
    old=$(api "https://api.github.com/repos/${REPO}/releases/${release_id}/assets" \
        | python3 -I -c 'import json,sys; print(" ".join(str(a["id"]) for a in json.load(sys.stdin) if a["name"]==sys.argv[1]))' "$name")
    for id in $old; do api -X DELETE "https://api.github.com/repos/${REPO}/releases/assets/${id}"; done
fi

echo "Загружаю ${name} ($(( $(wc -c < "$apk") / 1000000 )) МБ)…"
curl -fS --progress-bar -X POST -T "$apk" \
    -H "Authorization: Bearer $GITHUB_TOKEN" -H "Content-Type: application/vnd.android.package-archive" \
    "https://uploads.github.com/repos/${REPO}/releases/${release_id}/assets?name=${name}" > /dev/null
echo "Готово: https://github.com/${REPO}/releases/tag/${tag}"
echo "Прямая ссылка: https://github.com/${REPO}/releases/download/${tag}/${name}"
