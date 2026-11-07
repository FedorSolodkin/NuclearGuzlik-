package com.guzlik.voice

/**
 * Собственное зеркало моделей: GitHub Release публичного репозитория
 * dext01/guzlikvoice-models. Исходные репозитории на HuggingFace/GitHub могут
 * удалить или переименовать файл — тогда новые пользователи не смогут
 * поставить приложение. Поэтому сначала качаем с зеркала, оригинал — запасной.
 *
 * Файлы в зеркале побайтно совпадают с оригиналами (суммы — в [ModelIntegrity]),
 * так что недокачанный с одного источника .tmp можно докачивать с другого.
 */
object ModelMirror {
    const val BASE = "https://github.com/dext01/guzlikvoice-models/releases/download/models-v1/"

    // У onnx-моделей в приложении общие имена файлов, в зеркале — по исходнику.
    const val SPEAKER_ASSET  = "wespeaker-voxceleb-resnet34-LM.onnx"
    const val PYANNOTE_ASSET = "pyannote-segmentation-3.0.onnx"
    const val VAD_ASSET      = "silero_vad.onnx"

    /** Свой сервер (если задан при сборке), затем зеркало на GitHub, затем оригинальные источники. */
    fun urls(asset: String, vararg originals: String): List<String> =
        listOfNotNull(BuildConfig.MODELS_BASE_URL.takeIf { it.isNotBlank() }?.let { it + asset }) +
            listOf(BASE + asset) + originals.filter { it.isNotEmpty() }
}
