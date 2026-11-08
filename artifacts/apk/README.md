# APK для экспертов

Устанавливать **GuzlikVoice-4.0.9-contest-offline.apk из [релиза](https://github.com/FedorSolodkin/NuclearGuzlik-/releases/tag/guzlikvoice-contest-v4.0.9)**
(около 1,69 ГБ, SHA-256 `2eed2a75…c9ef`, полный хэш в [manifest.json](manifest.json) и `SHA256SUMS`).
**Состояние релиза на 8 ноября:** релиз `guzlikvoice-contest-v4.0.9` ещё черновик без вложенного файла, ссылка на APK заработает после загрузки файла и публикации релиза.

Это единственная конкурсная APK: GigaAM v3 RNNT (единственный ASR), Gemma 2 2B ru90k, WeSpeaker, pyannote и Silero VAD уже внутри, Whisper не входит.
Дополнительные скачивания не нужны. Список моделей и контрольных сумм: [../../config/contest-models.json](../../config/contest-models.json).

Более ранняя APK 4.0.8 с Whisper (SHA-256 в поле `previous_whisper_build` манифеста) заменена; результаты `results.csv` измерены на ней.

[Установка и проверка](../../docs/CONTEST_APK.md) · [Лицензии](../../docs/LICENSES.md)
