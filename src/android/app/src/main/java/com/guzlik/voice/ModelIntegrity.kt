package com.guzlik.voice

import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Контрольные суммы моделей. SHA256 взяты из заголовка `X-Linked-ETag`
 * HuggingFace (для LFS-файлов это SHA256 содержимого; сверено вручную на
 * ggml-tiny.bin и ggml-silero-v6.2.0.bin). Наше зеркало ([ModelMirror]) отдаёт
 * те же файлы, суммы совпадают. Для чужих GitHub-зеркал суммы нет — там остаётся
 * только проверка размера.
 *
 * Проверка по размеру пропускала обрезанные/битые файлы: whisper/llama.cpp
 * потом падали при загрузке или молча выдавали мусор.
 */
object ModelIntegrity {
    private const val TAG = "ModelIntegrity"

    /** Имя файла модели → ожидаемый SHA256. */
    private val SHA256_BY_FILE = mapOf(
        WhisperCppEngine.PODLODKA_TURBO_FILE      to "172d761c921345082986689cb622f891184da29476b85e873bb272687a3c73a7",
        WhisperCppEngine.PODLODKA_TURBO_Q5_0_FILE to "7e58e89ea5ff49edf330649caa4d7a0895a3d3254fecd9c1cf63b27dd8fcf53d",
        WhisperCppEngine.SMALL_FILE               to "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        WhisperCppEngine.TINY_FILE                to "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
    ) + LlmModels.ALL.associate { it.fileName to it.sha256 }

    /** URL → SHA256 для моделей, у которых есть несколько зеркал с разными файлами. */
    private const val SPEAKER_SHA  = "3955447b0499dc9e0a4541a895df08b03c69098eba4e56c02b5603e9f7f4fcbb"
    private const val PYANNOTE_SHA = "057ee564753071c0b09b5b611648b50ac188d50846bff5f01e9f7bbf1591ea25"
    private val SHA256_BY_URL = mapOf(
        "https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM/resolve/main/onnx/model.onnx" to SPEAKER_SHA,
        ModelMirror.BASE + ModelMirror.SPEAKER_ASSET to SPEAKER_SHA,
        "https://huggingface.co/onnx-community/pyannote-segmentation-3.0/resolve/main/onnx/model.onnx" to PYANNOTE_SHA,
        ModelMirror.BASE + ModelMirror.PYANNOTE_ASSET to PYANNOTE_SHA,
        ModelMirror.BASE + ModelMirror.VAD_ASSET to VAD_SHA,
    ) + BuildConfig.MODELS_BASE_URL.takeIf { it.isNotBlank() }?.let { server ->
        // Свой сервер моделей (-PguzlikvoiceModelsUrl): те же файлы, что в зеркале.
        mapOf(
            server + ModelMirror.SPEAKER_ASSET  to SPEAKER_SHA,
            server + ModelMirror.PYANNOTE_ASSET to PYANNOTE_SHA,
            server + ModelMirror.VAD_ASSET      to VAD_SHA,
        )
    }.orEmpty()

    fun expectedFor(fileName: String, url: String? = null): String? =
        url?.let { SHA256_BY_URL[it] } ?: SHA256_BY_FILE[fileName]

    /**
     * Модели голосов и детектор речи в приложении лежат под общими именами, а
     * в зеркале и на сайте — под именами исходников. Импорт с диска узнаёт их
     * по сумме (как в зеркале models-v1), чтобы телефон без интернета мог получить
     * и разделение по голосам.
     */
    private const val VAD_SHA = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
    private val IMPORT_EXTRA = mapOf(
        SPEAKER_SHA  to SpeakerEmbedder.MODEL_NAME,
        PYANNOTE_SHA to PyannoteSegmenter.MODEL_NAME,
        VAD_SHA      to SileroVAD.MODEL_NAME,
    )

    /** Определяет, какая из известных моделей лежит в файле (для импорта с диска). */
    fun identify(sha256: String): String? =
        SHA256_BY_FILE.entries.firstOrNull { it.value.equals(sha256, ignoreCase = true) }?.key
            ?: IMPORT_EXTRA[sha256.lowercase()]

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * true, если сумма совпала или для файла она неизвестна.
     * Хэширование 1.7 ГБ на телефоне — ~5-15 с, делаем один раз после загрузки.
     */
    fun verify(file: File, fileName: String, url: String? = null): Boolean {
        val expected = expectedFor(fileName, url) ?: return true
        val actual = try { sha256(file) } catch (e: Exception) {
            Log.e(TAG, "hash failed for $fileName", e); return false
        }
        val ok = actual.equals(expected, ignoreCase = true)
        if (!ok) Log.e(TAG, "SHA256 mismatch for $fileName: got $actual, want $expected")
        return ok
    }
}
