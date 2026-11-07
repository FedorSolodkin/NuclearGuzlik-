package com.example.guzlikvoice

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вход/регистрация и экраны «подписка закончилась» / «нужна проверка через интернет».
 * Решение о доступе принимает [LicenseManager]; сюда же отправляет [AccessGate].
 */
class AuthActivity : AppCompatActivity() {

    companion object {
        private const val MIN_PASSWORD = 8
    }

    private lateinit var loadingContainer: View
    private lateinit var formContainer: View
    private lateinit var blockedContainer: View
    private lateinit var tvAuthSubtitle: TextView
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var tvError: TextView
    private lateinit var btnSubmit: MaterialButton
    private lateinit var tvSwitchMode: TextView

    private var isRegisterMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        loadingContainer = findViewById(R.id.loadingContainer)
        formContainer = findViewById(R.id.formContainer)
        blockedContainer = findViewById(R.id.expiredContainer)
        tvAuthSubtitle = findViewById(R.id.tvAuthSubtitle)
        etEmail = findViewById(R.id.etAuthEmail)
        etPassword = findViewById(R.id.etAuthPassword)
        tvError = findViewById(R.id.tvAuthError)
        btnSubmit = findViewById(R.id.btnAuthSubmit)
        tvSwitchMode = findViewById(R.id.tvAuthSwitchMode)

        btnSubmit.setOnClickListener { submit() }
        tvSwitchMode.setOnClickListener { toggleMode() }
        findViewById<TextView>(R.id.tvExpiredLogout).setOnClickListener { logout() }
        findViewById<MaterialButton>(R.id.btnBlockedRetry).setOnClickListener { recheck() }

        route(LicenseManager.access(this), allowRecheck = true)
    }

    private fun route(access: LicenseManager.Access, allowRecheck: Boolean) {
        when (access) {
            LicenseManager.Access.GRANTED -> goToMain()
            LicenseManager.Access.NEED_LOGIN -> showForm()
            LicenseManager.Access.EXPIRED -> {
                showBlocked(expired = true)
                // Вдруг уже оплатили — перепроверим молча.
                if (allowRecheck) recheck(silent = true)
            }
            LicenseManager.Access.NEED_ONLINE -> if (allowRecheck) recheck() else showBlocked(expired = false)
        }
    }

    private fun recheck(silent: Boolean = false) {
        if (!silent) showLoading()
        val appCtx = applicationContext
        lifecycleScope.launch {
            val access = withContext(Dispatchers.IO) { LicenseManager.refreshBlocking(appCtx) }
            if (silent && access == LicenseManager.Access.EXPIRED) return@launch
            route(access, allowRecheck = false)
        }
    }

    private fun toggleMode() {
        isRegisterMode = !isRegisterMode
        tvError.visibility = View.GONE
        if (isRegisterMode) {
            tvAuthSubtitle.text = "Создайте аккаунт — 30 дней бесплатно"
            btnSubmit.text = "Зарегистрироваться"
            tvSwitchMode.text = "Уже есть аккаунт? Войти"
        } else {
            tvAuthSubtitle.text = "Вход в аккаунт"
            btnSubmit.text = "Войти"
            tvSwitchMode.text = "Нет аккаунта? Зарегистрироваться"
        }
    }

    private fun submit() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (email.isBlank() || password.isBlank()) {
            showError("Заполните email и пароль")
            return
        }
        if (isRegisterMode && password.length < MIN_PASSWORD) {
            showError("Пароль должен быть не короче $MIN_PASSWORD символов")
            return
        }

        btnSubmit.isEnabled = false
        tvError.visibility = View.GONE
        val appCtx = applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (isRegisterMode) SubscriptionApi.register(email, password)
                else SubscriptionApi.login(email, password)
            }
            if (!result.success || result.data == null) {
                btnSubmit.isEnabled = true
                showError(result.error ?: "Что-то пошло не так")
                return@launch
            }
            LicenseManager.onLoggedIn(appCtx, result.data.token)
            // Вход сам по себе не говорит о подписке — сразу берём статус/лицензию,
            // иначе истёкший аккаунт перелогинился бы в обход экрана «закончилось».
            val access = withContext(Dispatchers.IO) { LicenseManager.refreshBlocking(appCtx) }
            btnSubmit.isEnabled = true
            etPassword.setText("")
            route(access, allowRecheck = false)
        }
    }

    private fun logout() {
        LicenseManager.logout(this)
        etEmail.setText("")
        etPassword.setText("")
        showForm()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showLoading() {
        loadingContainer.visibility = View.VISIBLE
        formContainer.visibility = View.GONE
        blockedContainer.visibility = View.GONE
    }

    private fun showForm() {
        loadingContainer.visibility = View.GONE
        formContainer.visibility = View.VISIBLE
        blockedContainer.visibility = View.GONE
    }

    private fun showBlocked(expired: Boolean) {
        loadingContainer.visibility = View.GONE
        formContainer.visibility = View.GONE
        blockedContainer.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvBlockedIcon).text = if (expired) "⏳" else "📶"
        findViewById<TextView>(R.id.tvBlockedTitle).text =
            if (expired) "Пробный период закончился" else "Нужна проверка подписки"
        findViewById<TextView>(R.id.tvBlockedMessage).text =
            if (expired) "Оплата подписки скоро будет доступна прямо в приложении"
            else "Приложение давно не выходило в интернет. Подключитесь к сети на минуту — " +
                 "дальше снова можно работать офлайн."
        findViewById<MaterialButton>(R.id.btnBlockedRetry).visibility = View.VISIBLE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }
}
