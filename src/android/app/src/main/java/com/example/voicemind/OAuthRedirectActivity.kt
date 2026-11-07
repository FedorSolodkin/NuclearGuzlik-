package com.example.voicemind

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ловит редирект из Custom Tab после OAuth-consent. Обменивает code на токены
 * и возвращает в CalendarConnectActivity с результатом.
 *
 * Google для iOS-типа OAuth клиента возвращает opaque URI:
 *   com.googleusercontent.apps.{ID}:/oauthredirect?code=...&state=...
 * Intent-filter в манифесте фильтрует по scheme, path проверяем здесь.
 */
class OAuthRedirectActivity : AppCompatActivity() {

    companion object { private const val TAG = "OAuthRedirect" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        Log.i(TAG, "OAuth redirect received")
        if (data == null) {
            finish()
            return
        }
        // Для opaque URI (scheme:/oauthredirect) path хранится в schemeSpecificPart.
        val ssp  = data.schemeSpecificPart ?: ""
        val path = data.path
        val looksLikeRedirect = ssp.startsWith("/oauthredirect") ||
                                path == "/oauthredirect"
        if (!looksLikeRedirect) {
            Log.w(TAG, "URI не похож на oauthredirect, игнорирую")
            finish()
            return
        }
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = GoogleCalendarOAuth.handleRedirect(appCtx, data)
            Log.i(TAG, "handleRedirect returned $ok")
            withContext(Dispatchers.Main) {
                // Возвращаемся в CalendarConnectActivity — она проверит статус подключения в onResume.
                val intent = Intent(appCtx, CalendarConnectActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(CalendarConnectActivity.EXTRA_JUST_CONNECTED, ok)
                startActivity(intent)
                finish()
            }
        }
    }
}
