package com.example.voicemind

import android.content.Context

/**
 * Раньше здесь была отдельная Pro-подписка через Google Play Billing
 * (см. историю друга) — убрали её, весь доступ к приложению уже закрыт
 * нашей формой регистрации/триала (AuthActivity + SubscriptionApi).
 * Поэтому все фичи ниже доступны любому вошедшему пользователю,
 * без отдельного пейвола внутри приложения.
 */
object ProFeatures {

    enum class Feature {
        AI_TEMPLATES,
        ADVANCED_EXPORTS,
        SEMANTIC_SEARCH,
        VOICE_PROFILES,
        QA_CHAT,
        CLIENT_MANAGEMENT,
        CALENDAR_INTEGRATION,
        ENCRYPTED_VAULT,
        CLOUD_BACKUP
    }

    fun isProUser(ctx: Context): Boolean = true

    fun canUse(ctx: Context, feature: Feature): Boolean = true

    /** Всегда true — отдельного пейвола больше нет, доступ уже проверен на входе в приложение. */
    fun requirePro(ctx: Context, feature: Feature): Boolean = true
}
