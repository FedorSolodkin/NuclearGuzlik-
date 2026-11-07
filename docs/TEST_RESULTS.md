# Проверки конкурсного снимка

## Текущий прогон

- JDK 17, Android SDK 36, NDK 25.1.8937393, CMake 3.22.1, macOS ARM64.
- `./gradlew --offline testStoreDebugUnitTest assembleStoreHackathon` — BUILD SUCCESSFUL.
- JUnit: 228 тестов, 0 failures, 0 errors, 3 skipped.
  Сводка из XML: artifacts/test-results/unit-summary.json.
- 9 новых тестов JSON: точный контракт, стабильность ID, короткие повторяющиеся
  ответы, ручные правки/экранирование, unknown/overlap, один голос, отсутствие
  времени/диаризации, удалённые метки, некорректные времена.
- Python evaluator: 4 теста проходят; WER, нормализация, строгие типы и overlap.
- APK: `apksigner verify --verbose` подтверждает v2-подпись; `aapt dump badging`
  подтверждает пакет `ru.guzlikvoice.app.hackathon`, minSdk 26.
- Манифест, размер и SHA-256 APK: artifacts/apk/manifest.json.
- Android по ADB не подключён. Сквозной импорт/ASR/диаризация/LLM/экспорт,
  восстановление после выгрузки, RAM, нагрев и RTF **не проверены на новой APK**.
- Видео новой версии ещё не записано.

## Ранее проведённые акустические испытания основы

В репозитории сохранён исходный аудит 6 ноября: 35 сценариев / около 72 минут,
среди них 30 человеческих VoxConverse, AMI с дальним микрофоном и 4 стресс-сценария.
На окончательной независимой десятке: DER 16,58%, верное число людей 6/10.
AMI: DER 31,11%, число людей ошибочно. Детали протокола, collar=0,
наложения и разделение диагностических/независимых выборок находятся в
artifacts/test-results/diarization-audit-2026-11-06.md.

Эти результаты перенесены как свидетельство предыдущей работы. Новый конкурсный
снимок не прогонялся повторно на этих данных. Нельзя выдавать DER или условную
принадлежность слов за WER, а компьютерное время — за Android RTF.

## Воспроизведение

```bash
cd src/android
./gradlew testStoreDebugUnitTest assembleStoreHackathon
cd ../..
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/evaluate.py exported.json --reference reference.json --elapsed-seconds 42 --audio-seconds 60
```

Числа 42/60 в примере команды — только пример аргументов, не результат измерения.
Скорер оценивает лексический WER без пунктуации, с casefold и ё→е. Он не считает DER
и не маскирует ошибки личности под высокий WER-балл. Для DER используйте инструменты
src/android/tools/device_test и соответствующую независимую разметку.

На данном Mac host-компилятор Vulkan потребовал `SDKROOT=.../MacOSX15.4.sdk`,
`MACOSX_DEPLOYMENT_TARGET=15.0` и SDK/CMake/bin в PATH из-за несовместимости
установленного по умолчанию MacOSX27 SDK с linker. Это настройка компьютера сборки,
не требование Android-пользователя.
