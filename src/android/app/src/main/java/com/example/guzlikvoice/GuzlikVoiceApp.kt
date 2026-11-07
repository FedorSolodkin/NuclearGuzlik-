package com.example.guzlikvoice

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.google.android.material.color.DynamicColors

/**
 * Точка входа приложения. Здесь только setup, никакой бизнес-логики.
 * DynamicColors подхватывает палитру из обоев пользователя на Android 12+ —
 * там где недоступно, действует статическая warm-dark + gold из themes.xml.
 */
class GuzlikVoiceApp : Application() {

    // Простейший process-lifecycle без lifecycle-process:
    // считаем количество запущенных активити, когда упадёт до нуля — процесс
    // ушёл в фон → блокируем vault, чтобы биометрию пришлось пройти повторно.
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        kotlin.concurrent.thread(name = "GuzlikVoice-wearable-recovery") {
            try { WearableRecovery.recover(this) }
            catch (e: Exception) { android.util.Log.w("WearableRecovery", "Recovery incomplete", e) }
        }
        DynamicColors.applyToActivitiesIfAvailable(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            // После onCreate активности (setContentView уже вызван).
            override fun onActivityPostCreated(a: Activity, b: Bundle?) { EdgeToEdge.apply(a) }
            override fun onActivityStarted(a: Activity) {
                startedActivities++
            }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    // Уход в фон = автолок vault. Даже 5-минутный TTL не поможет,
                    // если телефон подхватил кто-то другой — биометрия обязательна.
                    VaultSession.lock(this@GuzlikVoiceApp)
                }
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
