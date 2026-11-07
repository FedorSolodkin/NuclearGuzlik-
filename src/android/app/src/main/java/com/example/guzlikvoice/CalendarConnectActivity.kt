package com.example.guzlikvoice

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Экран подключения календаря. Показывает состояние (подключён / нет),
 * позволяет запустить OAuth и отключить аккаунт.
 *
 * После первого подключения — сразу подтягивает события на сегодня как sanity check.
 */
class CalendarConnectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_JUST_CONNECTED = "just_connected"
    }

    private lateinit var tvStatus:   TextView
    private lateinit var tvHint:     TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var progress:   ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccessGate.check(this)) return
        setContentView(R.layout.activity_calendar_connect)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        tvStatus       = findViewById(R.id.tvCalendarStatus)
        tvHint         = findViewById(R.id.tvCalendarHint)
        btnConnect     = findViewById(R.id.btnCalendarConnect)
        btnDisconnect  = findViewById(R.id.btnCalendarDisconnect)
        progress       = findViewById(R.id.progressCalendar)

        btnConnect.setOnClickListener {
            if (!GoogleCalendarOAuth.isConfigured()) {
                AlertDialog.Builder(this)
                    .setTitle("Google Календарь скоро появится")
                    .setMessage(
                        "В этой сборке подключение к Google Календарю пока недоступно. " +
                        "Пока можно использовать Яндекс Календарь (CalDAV) — работает точно так же."
                    )
                    .setPositiveButton("Ок", null)
                    .show()
                return@setOnClickListener
            }
            GoogleCalendarOAuth.startAuthFlow(this)
        }

        btnDisconnect.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Отключить календарь?")
                .setMessage("Токены будут удалены. Кэш событий тоже.")
                .setPositiveButton("Отключить") { _, _ ->
                    CalendarTokenStore.clear(this)
                    CalendarEventCache.clear(this)
                    refreshUi()
                    Toast.makeText(this, "Календарь отключён", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        if (intent.getBooleanExtra(EXTRA_JUST_CONNECTED, false)) {
            Toast.makeText(this, "Календарь подключён", Toast.LENGTH_SHORT).show()
            // Прогреваем кэш немедленно
            warmUpCache()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val tokens = CalendarTokenStore.get(this)
        if (tokens != null) {
            tvStatus.text = "Подключён" + (tokens.email?.let { " · $it" } ?: "")
            tvHint.text   = "События на сегодня видны на вкладке «Сегодня»."
            btnConnect.visibility    = View.GONE
            btnDisconnect.visibility = View.VISIBLE
        } else {
            tvStatus.text = "Не подключён"
            tvHint.text   = "Разрешим только чтение primary календаря. " +
                            "Данные записей никогда не отправляются в календарь."
            btnConnect.visibility    = View.VISIBLE
            btnDisconnect.visibility = View.GONE
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
