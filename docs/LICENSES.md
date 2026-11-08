# Лицензии моделей и библиотек

Таблица составлена по страницам и репозиториям авторов (проверено 8 ноября 2026). Условия лицензий действуют независимо от приватности репозитория.

## Модели внутри APK

| Компонент | Файл в APK | Лицензия | Источник |
|---|---|---|---|
| GigaAM v3 (распознавание речи, RNNT с пунктуацией, int8), конвертация sherpa-onnx | `gigaam_v3_encoder.int8.onnx`, `gigaam_v3_decoder.onnx`, `gigaam_v3_joiner.onnx`, `gigaam_v3_rnnt_tokens.txt` | MIT (GigaChat Team) | https://huggingface.co/ai-sage/GigaAM-v3, https://github.com/salute-developers/GigaAM |
| Gemma 2 2B Instruct (локальная LLM; в APK облегчённый словарь «ru90k», Q4_K_M) | `gemma-2-2b-it-Q4_K_M-ru90k.gguf` | Gemma Terms of Use | https://ai.google.dev/gemma/terms; уведомление: [GEMMA-NOTICE.txt](GEMMA-NOTICE.txt) |
| pyannote segmentation 3.0 (сегментация говорящих, ONNX) | `pyannote_segmentation.onnx` | MIT | https://huggingface.co/pyannote/segmentation-3.0 |
| WeSpeaker ResNet34-LM (голосовые отпечатки, ONNX) | `speaker_model.onnx` | CC BY 4.0 (требуется указание авторства) | https://huggingface.co/Wespeaker/wespeaker-voxceleb-resnet34-LM |
| Silero VAD (определение речи, ONNX) | `silero_vad.onnx` | MIT | https://github.com/snakers4/silero-vad |

Атрибуция по CC BY 4.0: модель голоса WeSpeaker ResNet34-LM (команда WeSpeaker, https://github.com/wenet-e2e/wespeaker) используется без изменений, кроме формата ONNX.

## Библиотеки, вошедшие в сборку

| Библиотека | Лицензия | Где |
|---|---|---|
| sherpa-onnx (запуск GigaAM, нативная библиотека `libsherpa-onnx-jni.so`, ONNX Runtime вшит статически) | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| llama.cpp (запуск Gemma) | MIT | https://github.com/ggml-org/llama.cpp |
| whisper.cpp (код остался в сборке, модель Whisper в конкурсную APK не входит) | MIT | https://github.com/ggml-org/whisper.cpp |
| ONNX Runtime (диаризация) | MIT | https://github.com/microsoft/onnxruntime |
| Библиотеки Android/Kotlin (AndroidX, Material, Room, Gson и др.) | Apache-2.0 | см. зависимости Gradle в `src/android/app/build.gradle.kts` |

Пакет конвертации GigaAM для sherpa-onnx содержит указание: лицензия моделей https://github.com/salute-developers/GigaAM/blob/main/LICENSE. Контрольные суммы моделей: [config/contest-models.json](../config/contest-models.json).
