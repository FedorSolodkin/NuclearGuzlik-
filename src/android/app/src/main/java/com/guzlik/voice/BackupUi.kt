package com.guzlik.voice

import android.content.Context
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диалоги резервной копии: пароль → выбор файла → работа в фоне → итог.
 * Создавать как поле Fragment/Activity (регистрирует обработчики результата).
 * Сама копия/восстановление идёт в области процесса: закрытие настроек её не прерывает.
 */
class BackupUi(
    caller: ActivityResultCaller,
    private val context: () -> Context?,
    private val activity: () -> FragmentActivity?,
) {
    companion object {
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        const val FILE_EXT = "vmbackup"
    }

    private var pendingPassword: CharArray? = null
    private var includeProtected = false

    private val createDoc = caller.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(::runExport) ?: clearPassword() }

    private val openDoc = caller.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::askImportPassword) }

    // ── export ────────────────────────────────────────────────────────────────

    fun startExport() {
        val ctx = context() ?: return
        askPassword(ctx, "Пароль для копии",
            "Без пароля копию не открыть — запишите его. Восстановить забытый пароль нельзя.",
            confirm = true) { pwd ->
            pendingPassword = pwd
            val hasProtected = RecordStorage.hasProtectedEntries(ctx)
            if (hasProtected && !VaultSession.isUnlocked()) {
                AlertDialog.Builder(ctx)
                    .setTitle("Есть защищённые записи")
                    .setMessage("Чтобы они попали в копию, подтвердите отпечатком или PIN-кодом. " +
                        "Иначе копия будет без них.")
                    .setNegativeButton("Без них") { _, _ -> includeProtected = false; pickTarget() }
                    .setPositiveButton("Подтвердить") { _, _ ->
                        val act = activity() ?: return@setPositiveButton
                        BiometricUnlock.prompt(act,
                            onSuccess = { includeProtected = true; pickTarget() },
                            onFail = { msg -> toast(msg); includeProtected = false; pickTarget() })
                    }
                    .show()
            } else {
                includeProtected = hasProtected
                pickTarget()
            }
        }
    }

    private fun pickTarget() {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        createDoc.launch("GuzlikVoice-$day.$FILE_EXT")
    }

    private fun runExport(uri: Uri) {
        val ctx = context()?.applicationContext ?: return clearPassword()
        val pwd = pendingPassword ?: return
        pendingPassword = null
        toast("Сохраняю копию… Это может занять пару минут, если записей с аудио много.")
        processScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val r = BackupManager.export(ctx, uri, pwd, includeProtected)
                    buildString {
                        append("Копия сохранена: ${r.entries} записей, ${r.audioFiles} аудио.")
                        if (r.skippedProtected > 0) append(" Без ${r.skippedProtected} защищённых.")
                    }
                } catch (e: Exception) {
                    "Не удалось сохранить копию: ${ErrorMessages.humanize(e)}"
                } finally {
                    pwd.fill(' ')
                }
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ── import ────────────────────────────────────────────────────────────────

    fun startImport() = openDoc.launch(arrayOf("*/*"))

    private fun askImportPassword(uri: Uri) {
        val ctx = context() ?: return
        askPassword(ctx, "Пароль копии", null, confirm = false) { pwd -> runImport(uri, pwd) }
    }

    private fun runImport(uri: Uri, pwd: CharArray) {
        val ctx = context()?.applicationContext ?: return
        toast("Восстанавливаю записи…")
        processScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val r = BackupManager.import(ctx, uri, pwd)
                    buildString {
                        append("Восстановлено записей: ${r.added}")
                        if (r.audioFiles > 0) append(" (с аудио: ${r.audioFiles})")
                        if (r.skippedExisting > 0) append(", уже были: ${r.skippedExisting}")
                        append(".")
                        if (r.wasProtected > 0) append(" ${r.wasProtected} записей были защищены — защитите их снова в карточке записи.")
                    }
                } catch (e: BackupManager.WrongPasswordException) {
                    "Неверный пароль (или файл повреждён)"
                } catch (e: IllegalArgumentException) {
                    e.message ?: "Это не файл копии GuzlikVoice"
                } catch (e: Exception) {
                    "Не удалось восстановить: ${ErrorMessages.humanize(e)}"
                } finally {
                    pwd.fill(' ')
                }
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun askPassword(ctx: Context, title: String, message: String?, confirm: Boolean,
                            onOk: (CharArray) -> Unit) {
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        fun field(hint: String) = EditText(ctx).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val p1 = field("Пароль")
        val p2 = field("Повторите пароль")
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(p1)
            if (confirm) addView(p2)
        }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = p1.text.toString()
            when {
                confirm && a.length < BackupManager.MIN_PASSWORD ->
                    p1.error = "Не короче ${BackupManager.MIN_PASSWORD} символов"
                confirm && a != p2.text.toString() -> p2.error = "Пароли не совпадают"
                a.isEmpty() -> p1.error = "Введите пароль"
                else -> { dlg.dismiss(); onOk(a.toCharArray()) }
            }
        }
    }

    private fun clearPassword() { pendingPassword?.fill(' '); pendingPassword = null }

    private fun toast(msg: String) {
        context()?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
    }
}
