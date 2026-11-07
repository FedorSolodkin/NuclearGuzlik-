package com.example.voicemind

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Подключение YouGile диалогами: адрес/логин/пароль → компания → проект → доска → колонка
 * (куда по умолчанию создаются задачи с протоколами).
 */
class YouGileUi(private val fragment: Fragment, private val onChanged: () -> Unit) {
    private val ctx get() = fragment.requireContext()

    fun connect() {
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val server = EditText(ctx).apply { hint = "Сервер"; setText(YouGile.server(ctx)); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val login = EditText(ctx).apply { hint = "Email в YouGile"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val pass = EditText(ctx).apply { hint = "Пароль"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(login); box.addView(pass); box.addView(server)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Подключить YouGile")
            .setMessage("Пароль не сохраняется — приложение получит ключ компании. Для коробочной версии укажите адрес своего сервера.")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Далее") { _, _ ->
                val srv = try { YouGile.normalizeServer(server.text.toString()) } catch (e: Exception) { toast(e.message); return@setPositiveButton }
                val l = login.text.toString().trim(); val p = pass.text.toString()
                if (l.isEmpty() || p.isEmpty()) { toast("Введите email и пароль"); return@setPositiveButton }
                io({ YouGile.companies(srv, l, p) }) { companies ->
                    when {
                        companies.isEmpty() -> toast("У этого аккаунта нет компаний в YouGile")
                        companies.size == 1 -> finishConnect(srv, l, p, companies[0])
                        else -> pick("Компания", companies) { finishConnect(srv, l, p, it) }
                    }
                }
            }
            .show()
    }

    private fun finishConnect(server: String, login: String, password: String, company: YouGile.Item) {
        io({ YouGile.connect(ctx.applicationContext, server, login, password, company) }) {
            toast("YouGile подключён: ${company.title}")
            onChanged()
            chooseColumn()
        }
    }

    /** Проект → доска → колонка для новых задач. */
    fun chooseColumn() {
        val app = ctx.applicationContext
        io({ YouGile.projects(app) }) { projects ->
            pick("Проект", projects) { project ->
                io({ YouGile.boards(app, project.id) }) { boards ->
                    pick("Доска", boards) { board ->
                        io({ YouGile.columns(app, board.id) }) { columns ->
                            pick("Колонка для протоколов", columns) { col ->
                                YouGile.setDefaultColumn(app, col, "${project.title} › ${board.title} › ${col.title}")
                                onChanged()
                            }
                        }
                    }
                }
            }
        }
    }

    fun disconnect() {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Отключить YouGile?")
            .setMessage("Ключ будет удалён с телефона.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Отключить") { _, _ -> YouGile.disconnect(ctx.applicationContext); onChanged() }
            .show()
    }

    private fun pick(title: String, items: List<YouGile.Item>, onPick: (YouGile.Item) -> Unit) {
        if (items.isEmpty()) { toast("$title: список пуст"); return }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setItems(items.map { it.title.ifBlank { "Без названия" } }.toTypedArray()) { _, i -> onPick(items[i]) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun <T> io(work: () -> T, ok: (T) -> Unit) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching(work) }
            if (!fragment.isAdded) return@launch
            res.onSuccess(ok).onFailure { toast(it.message ?: "Ошибка сети") }
        }
    }

    private fun toast(msg: String?) {
        if (fragment.isAdded) Toast.makeText(ctx, msg ?: "Ошибка", Toast.LENGTH_LONG).show()
    }
}
