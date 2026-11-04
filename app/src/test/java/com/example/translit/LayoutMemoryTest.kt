package com.example.translit

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Раскладка запоминается для каждого приложения; 🌐 перебирает раскладки из настроек. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LayoutMemoryTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun field(pkg: String) = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = pkg
    }

    private fun globe(f: ImeFixture) {
        f.svc.onKey(KeyboardView.Key(KeyboardView.Type.GLOBE))
        f.idle()
    }

    /** Пользователь перешёл в другое приложение. */
    private fun open(f: ImeFixture, pkg: String) {
        f.svc.onFinishInputView(true)
        val info = field(pkg)
        f.svc.onStartInput(info, false)
        f.svc.onStartInputView(info, false)
        f.idle()
    }

    private fun firstRow(f: ImeFixture) =
        f.kv.rows[0].keys.filter { it.type == KeyboardView.Type.CHAR }.joinToString("") { it.label.lowercase() }

    @Test
    fun eachAppKeepsItsLayout() {
        KbSettings.update(app) { it.copy(layoutName = "TRANSLIT", perAppLayout = true) }
        val f = ImeFixture(field("com.chat"))
        assertEquals("транслит", f.kv.spaceLabel)
        globe(f)
        assertEquals("русский", f.kv.spaceLabel)
        open(f, "com.mail")
        assertEquals("транслит", f.kv.spaceLabel)
        globe(f); globe(f)
        assertEquals("english", f.kv.spaceLabel)
        open(f, "com.chat")
        assertEquals("русский", f.kv.spaceLabel)
        open(f, "com.mail")
        assertEquals("english", f.kv.spaceLabel)
    }

    @Test
    fun withoutPerAppLayoutStaysAsIs() {
        KbSettings.update(app) { it.copy(layoutName = "TRANSLIT", perAppLayout = false) }
        val f = ImeFixture(field("com.chat"))
        globe(f)
        open(f, "com.mail")
        assertEquals("русский", f.kv.spaceLabel)
    }

    @Test
    fun globeCycleFollowsSettings() {
        KbSettings.update(app) {
            it.copy(layoutName = "TRANSLIT", perAppLayout = false, cycleCyrillic = false, cycleReverse = true)
        }
        val f = ImeFixture(field("com.chat"))
        val seen = ArrayList<String>()
        repeat(4) {
            seen.add(f.kv.spaceLabel)
            if (f.kv.spaceLabel == "lat → рус") assertTrue(firstRow(f), firstRow(f).startsWith("qwerty"))
            globe(f)
        }
        assertEquals(listOf("транслит", "lat → рус", "english", "транслит"), seen)
    }

    @Test
    fun translitSystemShownOnSpace() {
        KbSettings.update(app) { it.copy(layoutName = "TRANSLIT", translitMode = TranslitMode.GOST) }
        val f = ImeFixture(field("com.chat"))
        assertEquals("транслит ГОСТ", f.kv.spaceLabel)
    }

    @Test
    fun yavertyLetters() {
        KbSettings.update(app) { it.copy(layoutName = "CYRILLIC", ruLayout = KbSettings.RU_YAVERTY) }
        val f = ImeFixture(field("com.chat"))
        assertTrue(firstRow(f), firstRow(f).startsWith("явертыуиоп"))
        KbSettings.update(app) { it.copy(ruLayout = KbSettings.RU_JCUKEN) }
        open(f, "com.chat")
        assertTrue(firstRow(f), firstRow(f).startsWith("йцукен"))
    }
}
