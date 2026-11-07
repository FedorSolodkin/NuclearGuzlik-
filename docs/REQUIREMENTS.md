# Соответствие заданию

Источник: предоставленный PDF «Хахатон Ноябрь 2026», слайды 8–22.
Структура папок повторяет grigorevmp/hacathon_template: src, config, docs, artifacts.

| Требование | Реализация | Граница проверки |
|---|---|---|
| Android APK | assembleStoreHackathon | новая сборка проверяется отдельно |
| Локальная LLM | Gemma / llama.cpp | модели должны быть установлены, нужен прогон на телефоне |
| Импорт | SAF / AudioImporter | WAV, MP3, M4A и другие форматы через Android decoder |
| ASR | локальный Whisper podlodka turbo | WER новой APK ещё не измерен |
| Автоматическая диаризация | pyannote + WeSpeaker + Kotlin clustering | ошибки подсчёта сохраняются |
| Начало и конец реплик | ASR word/segment timestamps | не точный forced alignment |
| Просмотр | карточка, редактирование, прослушивание | сквозная проверка новой APK требуется |
| Экспорт JSON | JsonTranscriptExporter / Downloads | контракт покрыт unit-тестами |
| Без удалённых вычислений | все модели в Android JNI/ONNX | проверка в авиарежиме по INSTALL.md |
| Исходники / инструкции | src/android и docs | есть |
| Ограничения и собственные тесты | LIMITATIONS и TEST_RESULTS | замеры компьютера отделены от Android |
| Демонстрация | artifacts/demo | видео ещё нужно записать |

Оценка: ASR 40, диаризация 25, времена 10, скорость 10, UX 10, защита 5.
Дополнительные пункты Ultra Pro Max не означают, что качество можно заявить без измерений.
