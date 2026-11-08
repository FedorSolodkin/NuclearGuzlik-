# Установка и автономная проверка

1. Скачайте `GuzlikVoice-4.0.9-contest-offline.apk` из [релиза](https://github.com/FedorSolodkin/NuclearGuzlik-/releases/tag/guzlikvoice-contest-v4.0.9)
   (около 1,69 ГБ, контрольная сумма: `artifacts/apk/manifest.json` и `SHA256SUMS`). Требуется Android 8+, ARM64.
2. Для чистой установки желательно 5 ГБ свободного места: APK, извлечённые модели и запас для обработки.
3. Откройте приложение и дождитесь экрана «Подготовка к работе»: модели извлекаются из APK, интернет не нужен.
4. Включите авиарежим и проверьте путь: «Расшифровать аудиофайл» → расшифровка → разделение по голосам → экспорт JSON
   (подробный сценарий: [../README.md](../README.md)).

Встроенные модели, их размеры и контрольные суммы: [CONTEST_APK.md](CONTEST_APK.md), [../config/contest-models.json](../config/contest-models.json).
Сборка из исходников: [../src/README.md](../src/README.md).
