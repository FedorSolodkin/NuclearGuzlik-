package com.guzlik.voice

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton

class OnboardingActivity : AppCompatActivity() {

    private data class Page(val iconRes: Int, val kicker: String, val title: String, val desc: String)

    // Копирайт намеренно суховатый и коротки: аудитория — терапевты, коучи,
    // врачи. Им нужно понять «зачем 500 МБ модели и что оно вообще делает»,
    // а не читать восторженный маркетинг.
    private val pages = listOf(
        Page(R.drawable.ic_mic,         "I  ⁄  III", "Говорите",              "Расшифровка речи идёт прямо на устройстве — без интернета и облака."),
        Page(R.drawable.ic_sparkle,     "II ⁄  III", "И получаете суть",      "ИИ на телефоне вытаскивает из потока слов задачи, тезисы и вопросы."),
        Page(R.drawable.ic_shield_lock, "III⁄  III", "Ничего не уходит",      "Аудио, транскрипты и аналитика — только на вашем телефоне. Ни серверов, ни трекеров.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show only once
        val prefs = getSharedPreferences("guzlikvoice_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_done", false)) {
            goNext(); return
        }

        setContentView(R.layout.activity_onboarding)
        val pager = findViewById<ViewPager2>(R.id.onboardingPager)
        val btnNext = findViewById<MaterialButton>(R.id.btnOnboardingNext)
        val btnSkip = findViewById<MaterialButton>(R.id.btnOnboardingSkip)

        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pages.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_page, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val p = pages[position]
                holder.itemView.findViewById<TextView>(R.id.tvOnboardingKicker).text = p.kicker
                holder.itemView.findViewById<ImageView>(R.id.ivOnboardingIcon).setImageResource(p.iconRes)
                holder.itemView.findViewById<TextView>(R.id.tvOnboardingTitle).text = p.title
                holder.itemView.findViewById<TextView>(R.id.tvOnboardingDesc).text = p.desc
            }
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                btnNext.text = if (position == pages.size - 1) "Начать" else "Далее"
                btnSkip.visibility = if (position == pages.size - 1) View.GONE else View.VISIBLE
            }
        })

        btnNext.setOnClickListener {
            if (pager.currentItem < pages.size - 1) {
                pager.currentItem++
            } else {
                finish()
            }
        }
        btnSkip.setOnClickListener { finish() }
    }

    override fun finish() {
        getSharedPreferences("guzlikvoice_prefs", MODE_PRIVATE).edit()
            .putBoolean("onboarding_done", true).apply()
        goNext()
        super.finish()
    }

    private fun goNext() {
        startActivity(Intent(this, SetupActivity::class.java))
    }
}
