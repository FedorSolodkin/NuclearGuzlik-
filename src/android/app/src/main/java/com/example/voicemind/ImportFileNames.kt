package com.example.voicemind

/** Имена файлов, пришедших извне (импорт аудио). */
object ImportFileNames {
    private val SAFE_EXT = Regex("^[a-z0-9]{2,4}$")

    /** Расширение из чужого имени файла — только [a-z0-9]{2,4}, иначе m4a (никаких «/..»). */
    fun safeExtension(name: String?): String =
        name?.substringAfterLast('.', "")?.lowercase()?.takeIf { SAFE_EXT.matches(it) } ?: "m4a"
}
