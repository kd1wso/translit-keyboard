package com.example.translit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * Рисует клавиатуру в PNG (app/build/screenshots) и проверяет основные переходы.
 * Запуск: ./gradlew testDebugUnitTest -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenshotTest {

    private lateinit var svc: TranslitKeyboardService
    private lateinit var root: View
    private lateinit var kv: KeyboardView

    private fun idle(ms: Long = 0) {
        val l = shadowOf(Looper.getMainLooper())
        if (ms > 0) l.idleFor(Duration.ofMillis(ms)) else l.idle()
    }

    private fun start(info: EditorInfo) {
        svc = Robolectric.buildService(TranslitKeyboardService::class.java).create().get()
        val t0 = System.currentTimeMillis()
        while (!(SuggestionEngine.isLoaded("ru") && SuggestionEngine.isLoaded("en")) &&
            System.currentTimeMillis() - t0 < 60_000
        ) Thread.sleep(50)
        Thread.sleep(300)
        idle()
        root = svc.onCreateInputView()
        svc.onStartInput(info, false)
        svc.onStartInputView(info, false)
        kv = findKeyboard(root)!!
        measure()
        idle()
    }

    private fun findKeyboard(v: View): KeyboardView? {
        if (v is KeyboardView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findKeyboard(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun texts(v: View, out: MutableList<String> = ArrayList()): List<String> {
        if (v is TextView && v.visibility == View.VISIBLE) out.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private val width get() = svc.resources.displayMetrics.widthPixels

    private fun measure() {
        // как окно IME на телефоне: «не выше экрана»
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(svc.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, width, root.measuredHeight)
    }

    private fun shot(name: String) {
        measure()
        val bmp = Bitmap.createBitmap(width, root.measuredHeight, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun key(pred: (KeyboardView.Key) -> Boolean): KeyboardView.Key =
        kv.rows.flatMap { it.keys }.first(pred)

    private fun down(k: KeyboardView.Key): Long {
        val t = SystemClock.uptimeMillis()
        kv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, k.x + k.w / 2, k.y + k.h / 2, 0))
        return t
    }

    private fun up(k: KeyboardView.Key, t: Long) {
        kv.dispatchTouchEvent(
            MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, k.x + k.w / 2, k.y + k.h / 2, 0)
        )
        idle()
        measure()
    }

    private fun tap(pred: (KeyboardView.Key) -> Boolean) {
        val k = key(pred)
        val t = down(k)
        idle(60)
        up(k, t)
        idle(100)
    }

    private fun tapChar(c: String) = tap { it.type == KeyboardView.Type.CHAR && it.text == c }

    private fun textField() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        initialSelStart = 0
        initialSelEnd = 0
    }

    @Test
    fun translitLetters() {
        start(textField())
        shot("01_translit")
        for (c in listOf("п", "р", "и", "в")) tapChar(c)
        val strip = texts(root)
        assertTrue(strip.toString(), strip.any { it.contains("privet", ignoreCase = true) })
        shot("02_translit_typing")
    }

    @Test
    fun longPressPanel() {
        start(textField())
        val k = key { it.type == KeyboardView.Type.CHAR && it.text == "е" }
        val t = down(k)
        idle(500)
        shot("03_longpress_e")
        up(k, t)
    }

    @Test
    fun symbolsAndEmoji() {
        start(textField())
        tap { it.type == KeyboardView.Type.MODE && it.label == "?123" }
        assertTrue(kv.rows.flatMap { it.keys }.any { it.text == "@" })
        shot("04_symbols")
        tap { it.type == KeyboardView.Type.MODE && it.label == "=\\<" }
        assertTrue(kv.rows.flatMap { it.keys }.any { it.text == "€" })
        shot("05_symbols2")
        tap { it.type == KeyboardView.Type.EMOJI }
        shot("06_emoji")
    }

    @Test
    fun cyrillicCapsAndLatinEmail() {
        start(textField())
        tap { it.type == KeyboardView.Type.GLOBE }
        tap { it.type == KeyboardView.Type.SHIFT }
        tap { it.type == KeyboardView.Type.SHIFT }
        shot("07_cyrillic_capslock")

        start(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            imeOptions = EditorInfo.IME_ACTION_NEXT
        })
        tap { it.type == KeyboardView.Type.GLOBE }
        tap { it.type == KeyboardView.Type.GLOBE }
        shot("08_latin_email")
    }

    @Test
    fun numberPad() {
        start(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
        })
        shot("09_numpad")
        start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE })
        shot("10_phone")
    }

    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun reverseTranslit() {
        KbSettings.update(app) { it.copy(layoutName = "REVERSE") }
        start(textField())
        for (c in "privet") tapChar(c.toString())
        val strip = texts(root)
        assertTrue(strip.toString(), strip.any { it.equals("привет", ignoreCase = true) })
        shot("11_reverse_typing")
    }

    @Test
    fun layoutFixChip() {
        KbSettings.update(app) { it.copy(layoutName = "LATIN") }
        start(textField())
        for (c in "ghbdtn") tapChar(c.toString())
        val strip = texts(root)
        assertTrue(strip.toString(), strip.any { it.equals("привет", ignoreCase = true) })
        shot("12_layout_fix")
    }

    @Test
    fun yaverty() {
        KbSettings.update(app) { it.copy(layoutName = "CYRILLIC", ruLayout = KbSettings.RU_YAVERTY) }
        start(textField())
        shot("13_yaverty")
    }

    @Test
    fun settingsScreen() {
        KbSettings.update(app) { it.copy(translitMode = TranslitMode.CUSTOM) }
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        var v: View = content
        while (v is ViewGroup && v !is android.widget.ScrollView && v.childCount > 0) v = v.getChildAt(0)
        val body = if (v is android.widget.ScrollView) v.getChildAt(0) else content
        val w = act.resources.displayMetrics.widthPixels
        body.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        body.layout(0, 0, w, body.measuredHeight)
        val bmp = Bitmap.createBitmap(w, body.measuredHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(KbSettings.palette(KbSettings.get(app)).bg)
        body.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "14_settings.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
