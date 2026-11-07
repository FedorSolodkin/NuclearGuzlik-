package com.example.guzlikvoice

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

/**
 * "Инструменты" — отдельный экран для того, что раньше было вкладкой "Сегодня"
 * плюс строки календаря из Настроек: подключение Google/Яндекс календаря и сам
 * список встреч на сегодня с возможностью привязать запись к встрече.
 *
 * Вынесено из основной навигации (было 4 вкладки, стало 3), чтобы не занимать
 * место в ежедневном use-case "открыл — записал", но остаться легко доступным
 * через иконку в шапке главного экрана.
 */
class ToolsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccessGate.check(this)) return
        setContentView(R.layout.activity_tools)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.toolsContainer, TodayFragment())
                .commit()
        }
    }
}
