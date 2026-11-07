package com.guzlik.voice

import android.content.Context

/** Optional assistant refusal survives restarts; explicit download still bypasses it. */
object SetupChoices {
    enum class Route { TINY, LLM, APP, INTRO }
    fun route(autoTiny: Boolean, autoLlm: Boolean, requiredReady: Boolean, completed: Boolean, allReady: Boolean): Route = when {
        autoTiny -> Route.TINY
        autoLlm -> Route.LLM
        requiredReady && (completed || allReady) -> Route.APP
        else -> Route.INTRO
    }
    fun completed(ctx: Context) = ctx.getSharedPreferences("setup_choices", 0).getBoolean("completed", false)
    fun finish(ctx: Context) { ctx.getSharedPreferences("setup_choices", 0).edit().putBoolean("completed", true).apply() }
    fun llmSkipped(ctx: Context) = ctx.getSharedPreferences("setup_choices", 0).getBoolean("llm_skipped", false)
    fun skipLlm(ctx: Context) { ctx.getSharedPreferences("setup_choices", 0).edit().putBoolean("llm_skipped", true).apply() }
}
