package com.guzlik.voice

/**
 * Список адресов для скачивания модели: свой сервер (если задан при сборке), затем оригинальные
 * источники. В конкурсной APK все модели уже внутри, скачивание не используется.
 */
object ModelMirror {
    // У onnx-моделей в приложении общие имена файлов, в источнике — по исходнику.
    const val SPEAKER_ASSET  = "wespeaker-voxceleb-resnet34-LM.onnx"
    const val PYANNOTE_ASSET = "pyannote-segmentation-3.0.onnx"
    const val VAD_ASSET      = "silero_vad.onnx"

    fun urls(asset: String, vararg originals: String): List<String> =
        listOfNotNull(BuildConfig.MODELS_BASE_URL.takeIf { it.isNotBlank() }?.let { it + asset }) +
            originals.filter { it.isNotEmpty() }
}
