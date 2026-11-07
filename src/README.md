# Исходники

Платформа: мобильная, Android (`android/`), только ARM64, Android 8+.

## Инструкция по запуску и сборке проекта

Требуется JDK 17, Android SDK 36, NDK 25.1.8937393, CMake 3.22.1 (`ninja` в `PATH`).

```bash
git clone --recurse-submodules git@github.com:FedorSolodkin/NuclearGuzlik-.git
cd NuclearGuzlik-/src/android
./scripts/fetch-vulkan-headers.sh
echo "sdk.dir=/путь/к/Android/sdk" > local.properties
```

### Сборки со встроенными моделями (для проверки без интернета)

Подготовьте папки `bundles/light/models` и `bundles/heavy/models` с файлами (имена обязательны; контрольные суммы в
`config/contest-models.json`, параметры сборки — в `config/example.env`):

| Файл | LIGHT | HEAVY |
|---|---|---|
| `ggml-whisper-podlodka-turbo-q4_0.bin` | да | да |
| `speaker_model.onnx` (WeSpeaker ResNet34) | да | да |
| `pyannote_segmentation.onnx` | да | да |
| `silero_vad.onnx` | да | да |
| `qwen3-0.6b-v2-Q4_K_M.gguf` | да | нет |
| `gemma-2-2b-it-Q4_K_M-ru90k.gguf` | нет | да |

```bash
# LIGHT (около 0,9 ГБ)
./gradlew assembleStoreHackathon --no-daemon -PguzlikvoiceAuthRequired=false \
  -PguzlikvoiceLlmModels=vm-qwen06-ft -PguzlikvoiceBundle=$HOME/bundles/light
# перед второй сборкой очистить упаковку, иначе в APK останется мёртвое место
./gradlew :app:cleanPackageStoreHackathon --no-daemon
# HEAVY (около 1,9 ГБ)
./gradlew assembleStoreHackathon --no-daemon -PguzlikvoiceAuthRequired=false \
  -PguzlikvoiceLlmModels=gemma2-2b-v90k -PguzlikvoiceBundle=$HOME/bundles/heavy
```

APK: `app/build/outputs/apk/store/hackathon/`. Пакет `ru.guzlik.voice.hackathon`, подпись debug.
При первом запуске модели копируются из APK в папку приложения (нужно свободного места примерно вдвое
больше размера APK), интернет не нужен.

### Сборка без встроенных моделей и тесты

```bash
./gradlew testStoreDebugUnitTest assembleStoreHackathon
```
Подробности установки для экспертов: ../docs/INSTALL.md; результаты проверок: ../docs/TEST_RESULTS.md.
