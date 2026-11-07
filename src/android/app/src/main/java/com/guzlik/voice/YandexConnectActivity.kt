package com.guzlik.voice

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Экран подключения Яндекс Календаря по CalDAV: email + пароль приложения.
 * После проверки — сохраняем креды и прогреваем кэш событий.
 */
class YandexConnectActivity : AppCompatActivity() {

    private lateinit var tvStatus:      TextView
    private lateinit var etEmail:       TextInputEditText
    private lateinit var etPassword:    TextInputEditText
    private lateinit var btnConnect:    MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var progress:      ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccessGate.check(this)) return
        setContentView(R.layout.activity_yandex_connect)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        tvStatus      = findViewById(R.id.tvYandexStatus)
        etEmail       = findViewById(R.id.etYandexEmail)
        etPassword    = findViewById(R.id.etYandexPassword)
        btnConnect    = findViewById(R.id.btnYandexTestConnect)
        btnDisconnect = findViewById(R.id.btnYandexDisconnect)
        progress      = findViewById(R.id.progressYandex)

        btnConnect.setOnClickListener { onConnectPressed() }
        btnDisconnect.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Отключить Яндекс Календарь?")
                .setMessage("Учётка будет удалена. События из Яндекса пропадут с вкладки «Сегодня».")
                .setPositiveButton("Отключить") { _, _ ->
                    YandexCaldavStore.clear(this)
                    refreshUi()
                    Toast.makeText(this, "Яндекс отключён", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val creds = YandexCaldavStore.get(this)
        if (creds != null) {
            tvStatus.text = "Подключён · ${creds.email}"
            btnConnect.visibility    = View.GONE
            btnDisconnect.visibility = View.VISIBLE
            etEmail.setText(creds.email)
            etEmail.isEnabled = false
            etPassword.isEnabled = false
        } else {
            tvStatus.text = "Не подключён"
            btnConnect.visibility    = View.VISIBLE
            btnDisconnect.visibility = View.GONE
            etEmail.isEnabled = true
            etPassword.isEnabled = true
        }
    }

    private fun onConnectPressed() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val pass  = etPassword.text?.toString().orEmpty()
        if (email.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Заполните email и пароль приложения", Toast.LENGTH_SHORT).show()
            return
        }

        progress.visibility = View.VISIBLE
        btnConnect.isEnabled = false
        val appCtx = applicationContext

        lifecycleScope.launch(Dispatchers.IO) {
            val calendarUrl = YandexCaldavClient.discoverCalendarUrl(email, pass)
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                btnConnect.isEnabled = true
                if (calendarUrl == null) {
                    AlertDialog.Builder(this@YandexConnectActivity)
                        .setTitle("Не удалось подключиться")
                        .setMessage("Проверьте email и пароль приложения. " +
                                "Пароль обычной почты не подойдёт — нужен именно пароль приложения из id.yandex.ru.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@withContext
                }
                YandexCaldavStore.save(
                    appCtx,
                    YandexCaldavStore.Creds(email, pass, calendarUrl)
                )
                refreshUi()
                Toast.makeText(appCtx, "Яндекс подключён", Toast.LENGTH_SHORT).show()
                warmUpCache()
            }
        }
    }

    private fun warmUpCache() {
        progress.visibility = View.VISIBLE
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val from = cal.timeInMillis
            val to   = from + 24L * 60 * 60 * 1000
            val events = CalendarProvider.fetchEvents(appCtx, from, to)
            CalendarEventCache.save(appCtx, events)
            MeetingNotificationScheduler.rescheduleAll(appCtx, events)
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
            }
        }
    }
}
