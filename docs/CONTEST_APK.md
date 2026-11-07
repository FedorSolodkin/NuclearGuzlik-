# Автономная конкурсная APK 4.0.8

Единственная APK для установки: **GuzlikVoice-4.0.8-contest-offline.apk** в
[релизе contest-offline-v4.0.8](https://github.com/FedorSolodkin/NuclearGuzlik-/releases/tag/guzlikvoice-contest-v4.0.8).
Размер около 1,92 ГБ, Android 8+, ARM64, пакет `ru.guzlik.voice.hackathon`.
Подпись совместима с предыдущей конкурсной APK: обновление сохраняет записи.

## Установка

1. Скачайте APK, установите её и откройте GuzlikVoice.
2. Дождитесь завершения экрана «Подготовка к работе»: файлы извлекаются из APK.
   Интернет для этого не нужен. Для чистой установки желательно иметь 5 ГБ свободного места
   до установки: APK, извлечённые модели и запас для Android/обработки.
3. В истории импортируйте аудиофайл и дождитесь расшифровки: разделение по голосам запускается
   автоматически. Затем нажмите «Экспорт JSON» в карточке записи. «Выделить главное» и вопросы используют Gemma.

В APK встроены точный Whisper podlodka turbo q4_0 (473 992 235 байт), Gemma 2B ru90k
(1 391 349 280 байт), WeSpeaker, pyannote и Silero VAD. Выбор быстрой ASR отключён,
старое предпочтение быстрой модели игнорируется. Единственная доступная LLM — Gemma ru90k.
Выбор/импорт альтернативных моделей и лаборатория сравнения моделей скрыты.
Модели диаризации/VAD необходимы для основной задачи; они остаются в APK.

Небольшой нативный Silero VAD для Whisper также автоматически включён из assets приложения.
Он не является альтернативной моделью транскрибации.

Полный список и SHA-256: [config/contest-models.json](../config/contest-models.json).
Gemma notice встроен в APK; копия: [GEMMA-NOTICE.txt](GEMMA-NOTICE.txt).

## Воспроизведение сборки

Создайте каталог `bundle/models/` и положите ровно пять файлов из списка моделей.
Скопируйте `docs/GEMMA-NOTICE.txt` в `bundle/GEMMA-NOTICE.txt`.

```bash
python3 scripts/verify_contest_bundle.py --bundle /absolute/path/to/bundle
cd src/android
# JDK 17, SDK 36, NDK 25.1.8937393, CMake 3.22.1; Ninja в PATH
./gradlew -PguzlikvoiceContest=true -PguzlikvoiceBundle=/absolute/path/to/bundle \
  -PguzlikvoiceTestBuildType=hackathon :app:cleanPackageStoreHackathon \
  :app:testStoreHackathonUnitTest :app:assembleStoreHackathon
cd ../..
python3 scripts/verify_contest_bundle.py --apk /absolute/path/to/GuzlikVoice-4.0.8-contest-offline.apk
```

Флаг `guzlikvoiceContest=true` обязателен: он закрепляет модели и запрещает сборку
без полного встроенного набора. Обычная сборка без этого флага предназначена для разработки.

## Границы проверки

Контроль APK проверяет состав, SHA-256 моделей и отсутствие сжатия весов; подпись
проверяется `apksigner`. Проверка на телефоне использует отдельную пустую папку моделей
и реальные вычисления ASR/диаризации/JSON/Gemma в авиарежиме, сохраняя пользовательские записи.
Результаты: `artifacts/test-results/contest-bundled-apk.json`.
Это проверка работоспособности на одной записи; она не заменяет WER/DER/RTF на независимой выборке.
