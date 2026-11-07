#!/usr/bin/env bash
# Пилотная сборка VoiceMind 4.0: release, подписана ключом из keystore.properties,
# без входа (сервер не нужен), только Gemma: облегчённая (по умолчанию) и обычная.
#
#   ./scripts/build-pilot.sh                 # APK в app/build/outputs/apk/store/release/
#   ./scripts/build-pilot.sh ../site         # + положить на сайт: site/download/
#
# Во втором случае в site/download/ появятся VoiceMind-<версия>.apk и latest.json
# (версия, размер, SHA-256) — страница загрузок сайта читает его сама. Дальше:
# закоммитить и запушить сайт (файл < 100 МБ, GitHub Pages его принимает).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f keystore.properties ]]; then
    echo "Нет keystore.properties — без ключа release не подписать (см. app/build.gradle.kts)." >&2
    exit 1
fi

./gradlew --console=plain assembleStoreRelease \
    -PvoicemindAuthRequired=false \
    -PvoicemindLlmModels=gemma2-2b-v90k,gemma2-2b

apk=$(ls -t app/build/outputs/apk/store/release/*.apk | head -1)
echo "APK: $apk"

site="${1:-}"
[[ -z "$site" ]] && exit 0

version=$(basename "$apk" | sed -E 's/^VoiceMind-v([^-]+(-pilot)?)-.*$/\1/')
name="VoiceMind-$version.apk"
mkdir -p "$site/download"
rm -f "$site"/download/VoiceMind-*.apk
cp "$apk" "$site/download/$name"
size=$(wc -c < "$site/download/$name" | tr -d ' ')
sha=$(shasum -a 256 "$site/download/$name" | cut -d' ' -f1)
cat > "$site/download/latest.json" <<EOF
{
  "version": "$version",
  "file": "/download/$name",
  "size": $size,
  "sha256": "$sha",
  "date": "$(date +%Y-%m-%d)"
}
EOF
echo "Положено на сайт: $site/download/$name ($((size / 1000000)) МБ, sha256 $sha)"
