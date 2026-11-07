package com.guzlik.voice

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Кнопки «Разблокировать», «Защитить запись» и «Снять защиту» в карточке записи.
 * Все действия требуют аутентификации: на Android 11+ ключ vault без неё не
 * работает ни на шифрование, ни на чтение (см. [VaultManager]).
 */
class EntryProtectionUi(
    private val fragment: Fragment,
    private val onUnlocked: () -> Unit,
    private val beforeChange: () -> Unit,
    private val onChanged: () -> Unit,
) {
    private fun toast(msg: String, long: Boolean = false) {
        val ctx = fragment.context ?: return
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun bindUnlock(view: View, isEncrypted: Boolean, timestamp: Long) {
        val btn = view.findViewById<MaterialButton>(R.id.btnUnlockVault)
        if (!isEncrypted || VaultSession.isUnlocked()) { btn.visibility = View.GONE; return }
        btn.visibility = View.VISIBLE
        btn.setOnClickListener {
            authThen {
                val plain = RecordStorage.readDecryptedPayload(fragment.requireContext(), timestamp)?.get("text")
                if (plain.isNullOrEmpty()) {
                    toast("Не удалось расшифровать: ключ защиты недоступен. " +
                        "Если вы снимали блокировку экрана, запись не восстановить.", long = true)
                } else {
                    onUnlocked()
                }
            }
        }
    }

    fun bindProtect(view: View, isEncrypted: Boolean, date: String, timestamp: Long) {
        val btn = view.findViewById<MaterialButton>(R.id.btnProtectEntry)
        btn.visibility = View.GONE; return // хакатон: защита записи не используется
        if (isEncrypted && !VaultSession.isUnlocked()) { btn.visibility = View.GONE; return }
        btn.visibility = View.VISIBLE
        btn.text = if (isEncrypted) "Снять защиту" else "Защитить запись"
        btn.setOnClickListener {
            val ctx = fragment.requireContext()
            if (!BiometricUnlock.isAvailable(ctx)) {
                toast("Включите блокировку экрана (PIN, пароль или отпечаток), чтобы защищать записи", long = true)
                return@setOnClickListener
            }
            if (isEncrypted) {
                authThen { change(date, timestamp, protect = false) }
            } else {
                AlertDialog.Builder(ctx)
                    .setTitle("Защитить запись?")
                    .setMessage("Текст и аудио будут зашифрованы. Открыть запись можно будет только " +
                        "после отпечатка или PIN-кода телефона.\n\nЕсли снять блокировку экрана " +
                        "телефона, Android удалит ключ — защищённые записи станет невозможно прочитать.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Защитить") { _, _ -> authThen { change(date, timestamp, protect = true) } }
                    .show()
            }
        }
    }

    private fun authThen(action: () -> Unit) {
        val act = fragment.activity ?: return
        BiometricUnlock.prompt(
            activity = act,
            onSuccess = { if (fragment.isAdded) action() },
            onFail = { msg -> toast(msg) }
        )
    }

    private fun change(date: String, timestamp: Long, protect: Boolean) {
        val appCtx = fragment.requireContext().applicationContext
        beforeChange()
        fragment.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (protect) RecordStorage.encryptEntry(appCtx, date, timestamp)
                    else RecordStorage.decryptEntry(appCtx, date, timestamp)
                } catch (e: Exception) {
                    VaultManager.logFailure("changeProtection", e)
                    false
                }
            }
            Toast.makeText(appCtx, when {
                !ok -> "Не получилось — попробуйте ещё раз"
                protect -> "Запись защищена"
                else -> "Защита снята"
            }, Toast.LENGTH_SHORT).show()
            if (ok) onChanged()
        }
    }
}
