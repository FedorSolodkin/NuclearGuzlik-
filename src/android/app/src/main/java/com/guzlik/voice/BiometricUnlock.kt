package com.guzlik.voice

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Обёртка над BiometricPrompt. Разрешает: биометрия, устройство-credential (PIN/пароль/паттерн)
 * — что доступно на устройстве. Если ничего нет — колбэк onUnavailable.
 */
object BiometricUnlock {

    // Android 11+: ключ vault привязан к аутентификации, а Keystore засчитывает
    // только STRONG-биометрию или PIN/пароль. На 8–10 STRONG|DEVICE_CREDENTIAL
    // BiometricPrompt не поддерживает — там ключ и не привязан (см. VaultManager).
    private val AUTHENTICATORS =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(ctx: Context): Boolean {
        val bm = BiometricManager.from(ctx)
        return bm.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun prompt(
        activity: FragmentActivity,
        title: String = "Защищённые записи",
        subtitle: String = "Приложите палец или введите код",
        onSuccess: () -> Unit,
        onFail: (String) -> Unit
    ) {
        if (!isAvailable(activity)) {
            onFail("Включите блокировку экрана (PIN, пароль или отпечаток), чтобы защищать записи")
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                VaultSession.unlock()
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFail(errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
