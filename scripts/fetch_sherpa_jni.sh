#!/usr/bin/env bash
# Скачивает libsherpa-onnx-jni.so (onnxruntime вшит статически) для движка GigaAM.
# Модель (model.int8.onnx + tokens.txt) в APK не входит: кладётся в filesDir/gigaam/ приложения.
set -euo pipefail
V=v2.0.1
DEST="$(cd "$(dirname "$0")/.." && pwd)/src/android/app/src/main/jniLibs"
TMP="$(mktemp -d)"
gh release download "$V" -R k2-fsa/sherpa-onnx -p "sherpa-onnx-$V-android-static-link-onnxruntime.tar.bz2" -D "$TMP"
tar xjf "$TMP"/*.tar.bz2 -C "$TMP"
for abi in arm64-v8a x86_64; do mkdir -p "$DEST/$abi"; cp "$TMP/jniLibs/$abi/libsherpa-onnx-jni.so" "$DEST/$abi/"; done
rm -rf "$TMP"
