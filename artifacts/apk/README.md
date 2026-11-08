# APK для экспертов

**Статус: финальная сборка ещё не опубликована** (`status: "pending final build"` в [manifest.json](manifest.json)). Размер, SHA-256 и ссылка на файл будут добавлены
после сборки и проверки; до этого хэш не указан.

Устанавливать нужно будет **GuzlikVoice-4.0.8-contest-offline.apk из [релиза](https://github.com/FedorSolodkin/NuclearGuzlik-/releases/tag/guzlikvoice-contest-v4.0.8)**.
Это единственная конкурсная APK: GigaAM v3 RNNT (единственный ASR), Gemma 2 2B ru90k, WeSpeaker, pyannote и Silero VAD уже внутри, Whisper не входит.
Дополнительные скачивания не нужны. Список моделей и контрольных сумм: [../../config/contest-models.json](../../config/contest-models.json).

Более ранняя APK с Whisper (SHA-256 в поле `previous_whisper_build` манифеста) заменена; результаты `results.csv` измерены на ней.

[Установка и проверка](../../docs/CONTEST_APK.md) · [Лицензии](../../docs/LICENSES.md)
