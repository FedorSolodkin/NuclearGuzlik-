package com.guzlik.voice

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * С targetSdk 35+ Android 15+ рисует приложение под статус-баром и панелью
 * навигации принудительно (отключить нельзя с targetSdk 36). Чтобы экраны
 * выглядели одинаково на всех версиях, включаем edge-to-edge везде и отдаём
 * корневой вёрстке отступы системных панелей и клавиатуры (adjustResize в
 * режиме edge-to-edge сам больше не сжимает окно).
 */
object EdgeToEdge {
    fun apply(activity: Activity) {
        if (activity !is ComponentActivity) return
        // Тема тёмная: светлые значки на прозрачных панелях.
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root: View = content.getChildAt(0) ?: return
        val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                base[0] + bars.left,
                base[1] + bars.top,
                base[2] + bars.right,
                base[3] + maxOf(bars.bottom, ime.bottom),
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }
}
