package com.example.voicemind

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Языковые модели для ИИ-анализа. Выбор хранится в [AppPrefs.getLlmModelId].
 *
 * - Gemma 2 2B — универсальная: «Выделить главное», вопросы к записи.
 * - VoiceMind Qwen 0.6B — Qwen3-0.6B, дообученная (LoRA) на «напоминаниях из
 *   голосовых заметок». Умеет только «Выделить главное», зато в 4 раза меньше
 *   и заметно быстрее. Нужен свой промпт и сэмплер (см. LlmHelper.structureSpecialist).
 */
object LlmModels {

    data class Spec(
        val id: String,
        val title: String,
        val note: String,
        val fileName: String,
        val urls: List<String>,
        val sizeBytes: Long,
        val sha256: String,
        /** Дообучена только на «Выделить главное»: свой промпт, без вопросов к записи. */
        val reminderSpecialist: Boolean = false,
    ) {
        val supportsQa: Boolean get() = !reminderSpecialist

        /** Файл меньше этого порога считается недокачанным. */
        val minSize: Long get() = (sizeBytes * 0.9).toLong()

        val sizeLabel: String
            get() = if (sizeBytes >= 1_000_000_000L)
                String.format(Locale.US, "%.1f ГБ", sizeBytes / 1e9).replace('.', ',')
            else "${sizeBytes / 1_000_000} МБ"
    }

    val GEMMA = Spec(
        id = "gemma2-2b",
        title = "Gemma 2 · 2B",
        note = "Выделяет главное и отвечает на вопросы к записи. Тяжелее и медленнее",
        fileName = "gemma-2-2b-it-Q4_K_M.gguf",
        urls = ModelMirror.urls("gemma-2-2b-it-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"),
        sizeBytes = 1_708_582_752L,
        sha256 = "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787",
    )

    /** Та же Gemma 2 2B, но формат сжатия Q4_0: у llama.cpp для него быстрые ядра под ARM. Для сравнения скорости. */
    val GEMMA_Q4_0 = Spec(
        id = "gemma2-2b-q4_0",
        title = "Gemma 2 · 2B · Q4_0 (тест скорости)",
        note = "Та же Gemma, другой формат сжатия: может считать быстрее. Для сравнения со стандартной",
        fileName = "gemma-2-2b-it-Q4_0.gguf",
        urls = listOf("https://huggingface.co/second-state/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_0.gguf"),
        sizeBytes = 1_629_509_216L,
        sha256 = "2c1a93b3dfaaff7345ef184956f3adc78e37e6a3a4fd7bddb25bff01607ef12c",
    )

    /**
     * Та же Gemma 2 2B Q4_K_M, но из таблицы слов (256 000) оставлены только русские, английские, цифры, знаки и
     * служебные токены (90 234). Веса остальных слоёв не тронуты, на русских текстах токенизация идентична, ответы
     * совпали на 116 из 116 текстов. Файл меньше на 320 МБ, генерация быстрее. Лежит только в нашем
     * зеркале (изменённая Gemma: рядом GEMMA-NOTICE.txt с условиями Gemma) — запасного источника нет.
     */
    val GEMMA_TRIM = Spec(
        id = "gemma2-2b-v90k",
        title = "Gemma 2 · 2B · облегчённая",
        note = "Та же Gemma без лишних языков: на 320 МБ меньше и отвечает быстрее. Выделяет главное и отвечает на вопросы к записи",
        fileName = "gemma-2-2b-it-Q4_K_M-ru90k.gguf",
        urls = ModelMirror.urls("gemma-2-2b-it-Q4_K_M-ru90k.gguf"),
        sizeBytes = 1391349280L,
        sha256 = "96c22f048c16b1cf258cfa3bf8a22cb87f41a9be33eaaced4436d130a359f5f2",
    )

    val QWEN_FT = Spec(
        id = "vm-qwen06-ft",
        title = "VoiceMind · Qwen 0.6B (эксперимент)",
        note = "Наша дообученная модель: в 4 раза меньше и быстрее, но чаще ошибается в деталях. Только «Выделить главное»",
        fileName = "qwen3-0.6b-v2-Q4_K_M.gguf",
        urls = ModelMirror.urls("qwen3-0.6b-v2-Q4_K_M.gguf"),
        sizeBytes = 396_704_576L,
        sha256 = "e3759f9ba22dae229274c0c7325562ac0692c2a2b7ed99620cea5adb5b7f1776",
        reminderSpecialist = true,
    )

    /** Все известные модели: проверка файлов, установка из файла, удаление скачанных.
     *  Новую модель добавить и в knownLlmIds в app/build.gradle.kts. */
    val ALL: List<Spec> = listOf(GEMMA, GEMMA_TRIM, GEMMA_Q4_0, QWEN_FT)

    /**
     * Модели, которые можно выбрать в этой сборке (BuildConfig.LLM_MODELS, см.
     * -PvoicemindLlmModels), в порядке из флага: первая — по умолчанию для тех,
     * кто ещё не выбирал. Пусто или ни одного известного id — все.
     */
    val SELECTABLE: List<Spec> = BuildConfig.LLM_MODELS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { id -> ALL.firstOrNull { it.id == id } }.distinct()
        .ifEmpty { ALL }

    fun byId(id: String?): Spec? = SELECTABLE.firstOrNull { it.id == id }

    /** Выбранная модель; если её в этой сборке выбрать нельзя — первая доступная. */
    fun selected(ctx: Context): Spec = byId(AppPrefs.getLlmModelId(ctx)) ?: SELECTABLE.first()

    /** Файл модели, если она уже лежит на устройстве (внутреннее или app-external хранилище). */
    fun findFile(ctx: Context, spec: Spec): File? {
        File(ctx.filesDir, spec.fileName).takeIf { it.exists() && it.length() > spec.minSize }?.let { return it }
        return ctx.getExternalFilesDir(null)
            ?.let { File(it, spec.fileName) }
            ?.takeIf { it.exists() && it.length() > spec.minSize }
    }

    fun isDownloaded(ctx: Context, spec: Spec): Boolean = findFile(ctx, spec) != null
}
