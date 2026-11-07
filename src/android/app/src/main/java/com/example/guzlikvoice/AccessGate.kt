package com.example.guzlikvoice

import android.app.Activity
import android.content.Intent

/**
 * Единая проверка доступа для всех экранов, куда можно попасть снаружи или
 * из уведомления (MainActivity, импорт файла, календарь, клиенты...).
 * Раньше проверка была только в AuthActivity, и «Поделиться → GuzlikVoice»
 * открывал приложение в обход подписки.
 */
object AccessGate {
    /** true — можно продолжать; false — активити уже закрыта и отправлена на экран входа. */
    fun check(activity: Activity): Boolean {
        val access = LicenseManager.access(activity)
        if (access == LicenseManager.Access.GRANTED) {
            LicenseManager.refreshInBackgroundIfDue(activity)
            return true
        }
        activity.startActivity(Intent(activity, AuthActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finish()
        return false
    }
}
