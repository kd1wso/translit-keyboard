package com.example.translit

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Сервис клавиатуры под Robolectric: создаёт окно ввода и меряет его, как окно IME на телефоне. */
class ImeFixture(info: EditorInfo) {
    val svc: TranslitKeyboardService = Robolectric.buildService(TranslitKeyboardService::class.java).create().get()
    val root: View
    val kv: KeyboardView

    init {
        val t0 = System.currentTimeMillis()
        while (!(SuggestionEngine.isLoaded("ru") && SuggestionEngine.isLoaded("en")) &&
            System.currentTimeMillis() - t0 < 60_000
        ) Thread.sleep(50)
        Thread.sleep(300)
        idle()
        root = svc.onCreateInputView()
        svc.onStartInput(info, false)
        svc.onStartInputView(info, false)
        kv = find(root)!!
        measure()
        idle()
    }

    val screenW get() = svc.resources.displayMetrics.widthPixels
    val screenH get() = svc.resources.displayMetrics.heightPixels

    fun idle(ms: Long = 0) {
        val l = shadowOf(Looper.getMainLooper())
        if (ms > 0) l.idleFor(Duration.ofMillis(ms)) else l.idle()
    }

    /** Окно IME даёт виду «не больше экрана» (AT_MOST), а не «сколько хочешь». */
    fun measure() {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, screenW, root.measuredHeight)
    }

    private fun find(v: View): KeyboardView? {
        if (v is KeyboardView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
}
