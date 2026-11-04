package com.example.translit

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/** Панель навигации (◁ ○ □) — в цвет темы клавиатуры, значки на ней — контрастные. */
object SystemBars {

    fun isLight(color: Int): Boolean = ColorUtils.calculateLuminance(color) > 0.5

    @Suppress("DEPRECATION")
    fun style(w: Window, bg: Int) {
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        // Android 14 и старше красят панель этим цветом
        w.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 28) w.navigationBarDividerColor = bg
        // Android 15+: панель прозрачная, без светлой подложки — под ней фон клавиатуры
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = isLight(bg)
    }
}
