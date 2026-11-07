package com.guzlik.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    var llmHelper:     LlmHelper?     = null
        private set
    var whisperHelper: WhisperHelper? = null
        internal set  // set by RecordFragment after model loads

    private val llmLoadLock = Any()
    private var llmLoadInProgress = false
    private val llmPendingCallbacks = mutableListOf<() -> Unit>()

    private val backupUi = BackupUi(this, { this }, { this })

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Молча игнорируем отказ — уведомления не критичны для работы, просто UX хуже. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccessGate.check(this)) return
        setContentView(R.layout.activity_main)
        // Reuse a model kept alive by a background question/protocol after rotation.
        val wantedAssistant=LlmModels.selected(this)
        llmHelper=LlmHelper.active?.takeIf { it.spec.id==wantedAssistant.id && it.attachOwner() }

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = MainPagerAdapter(this)

        // Fade + subtle scale — заменяет резкий "паровозик" на плавный переход.
        // На соседней странице страница слегка отдалена (0.92) и полупрозрачна.
        viewPager.setPageTransformer { page, position ->
            when {
                position <= -1f || position >= 1f -> {
                    page.alpha = 0f
                }
                else -> {
                    val absPos = abs(position)
                    page.alpha = 1f - absPos * 0.6f
                    val scale = 0.92f + (1f - absPos) * 0.08f
                    page.scaleX = scale
                    page.scaleY = scale
                }
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0    -> "Запись"
                else -> "История"
            }
        }.attach()

        // Deep-link из уведомления / ToolsActivity (бывшая вкладка "Сегодня"): сразу на «Запись».
        if (intent?.getStringExtra("open_tab") == "record") {
            viewPager.setCurrentItem(0, false)
        }

        // Deep-link из ProcessingNotifier: открыть запись по timestamp.
        handleOpenEntryIntent(intent)

        // POST_NOTIFICATIONS на Android 13+ нужен для уведомлений о завершении
        // обработки. Спрашиваем один раз, тихо игнорируем отказ.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Осиротевшие vault-файлы (записи удалили, но зашифрованный текст остался
        // — например, после падения между delete записи и delete файла в старых версиях).
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try { RecordStorage.pruneOrphanVaultFiles(appCtx); EntryExporter.pruneOldExports(appCtx) }
            catch (e: Exception) { Log.w("MainActivity", "vault prune failed: ${e.message}") }
        }

        // Упали в прошлый раз — предложить отправить отчёт (только при первом создании).
        if (savedInstanceState == null) {
            CrashReporter.offerAfterCrash(this)
        }

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            val sheet = SettingsSheet()
            sheet.onSettingsChanged = {
                // ViewPager2 + FragmentStateAdapter хранит фрагменты под тегом "f{position}".
                // Record — позиция 0 (вкладка "Сегодня" убрана, теперь Запись/История/Клиенты).
                val frag = supportFragmentManager.findFragmentByTag("f0") as? RecordFragment
                frag?.reloadWhisper()
            }
            sheet.show(supportFragmentManager, "settings")
        }

    }

    override fun onPostResume() {
        super.onPostResume()
        if (!isFinishing) lifecycleScope.launch {
            DiarizationJobRunner.resumeInterrupted(applicationContext) {
                !isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
        }
    }

    fun ensureLlmLoaded(onReady: () -> Unit) {
        val wanted = LlmModels.selected(this)
        var stale: LlmHelper? = null
        synchronized(llmLoadLock) {
            val current = llmHelper
            if (current?.isReady == true && current.ownsNativeModel() && current.spec.id == wanted.id) { onReady(); return }
            val shared=if(!llmLoadInProgress)LlmHelper.active?.takeIf { it.spec.id==wanted.id && it.attachOwner() }else null
            if(shared!=null) {
                current?.takeIf{it!==shared}?.release()
                llmHelper=shared;onReady();return
            }
            // В настройках выбрали другую модель — старую выгружаем (две в память не влезут).
            if (current != null && !llmLoadInProgress) { stale = current; llmHelper = null }
            llmPendingCallbacks += onReady
            if (llmLoadInProgress) return
            llmLoadInProgress = true
        }
        stale?.let { old -> try { old.cancel(); old.release() } catch (_: Exception) {} }
        // Загрузка модели блокирующая и не отменяется. Если активность умрёт раньше,
        // модель (1.7 ГБ в памяти) освобождаем сами — иначе она оставалась без владельца.
        val llm = LlmHelper(applicationContext, wanted)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { llm.load() } catch (e: Exception) {
                    Log.e("MainActivity", "LLM load crashed: ${e.message}", e); false
                }
            }
            finishLlmLoad(llm, ok)
        }.invokeOnCompletion { cause ->
            // Активность уничтожена во время загрузки: withContext дождался конца
            // блокирующей load(), но результат никому не нужен.
            if (cause != null) {
                synchronized(llmLoadLock) { llmLoadInProgress = false; llmPendingCallbacks.clear() }
                if (llm.isReady) llm.release()
            }
        }
    }

    private fun finishLlmLoad(llm: LlmHelper, ok: Boolean) {
        val callbacks: List<() -> Unit>
        synchronized(llmLoadLock) {
            if (ok && !isDestroyed) {
                llmHelper = llm
                Log.i("MainActivity", "LLM ready: ${llm.loadedModelName}")
            } else {
                try { llm.release() } catch (_: Exception) {}
            }
            llmLoadInProgress = false
            callbacks = llmPendingCallbacks.toList()
            llmPendingCallbacks.clear()
        }
        if (!isDestroyed) callbacks.forEach { it() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("open_tab") == "record") {
            findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(0, false)
        }
        handleOpenEntryIntent(intent)
    }

    /** Открыть запись по timestamp — из notification-клика. Ищем во всех датах,
     *  показываем bottom-sheet поверх текущей вкладки. Sheet сам подтянет audio,
     *  структурированный текст и т.д. Если запись не найдена (была удалена
     *  вручную) — молча ничего не делаем. */
    private fun handleOpenEntryIntent(intent: Intent?) {
        if (intent == null) return
        val ts = intent.getLongExtra(ProcessingNotifier.EXTRA_OPEN_ENTRY_TS, 0L)
        if (ts == 0L) return
        // Одноразово — иначе при повороте экрана / onNewIntent ещё раз откроется.
        intent.removeExtra(ProcessingNotifier.EXTRA_OPEN_ENTRY_TS)

        val appCtx = applicationContext
        lifecycleScope.launch {
            val (date, entry) = withContext(Dispatchers.IO) { RecordStorage.findEntryByTimestamp(appCtx, ts) }
                ?: return@launch
            run {
                if (isFinishing || isDestroyed) return@launch
                val log = LogEntry(
                    timestamp   = entry.timestamp,
                    rawText     = entry.text,
                    structured  = entry.structured,
                    keyThoughts = entry.keyThoughts,
                    tags        = entry.tags,
                    source      = entry.source,
                    audioPath   = entry.audioPath,
                    isEncrypted = entry.isEncrypted,
                    segments    = entry.segments
                )
                TranscriptionDetailSheet.newInstance(log, date)
                    .show(supportFragmentManager, "detail_from_notif")
            }
        }
    }

    /** Для «Теста скорости»: нативный контекст один на процесс, поэтому рабочую модель выгружаем. */
    fun releaseLlmForLab() {
        synchronized(llmLoadLock) { llmHelper?.release(); llmHelper = null }
    }

    override fun onDestroy() {
        super.onDestroy()
        llmHelper?.release()
        llmHelper = null
    }
}
