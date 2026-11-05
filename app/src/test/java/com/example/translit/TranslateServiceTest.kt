package com.example.translit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Перевод в клавиатуре целиком: панель, набор с переводом, поле, выделенное, скопированное. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TranslateServiceTest {

    /** Поле приложения: BaseInputConnection, onUpdateSelection приходят позже, как от настоящего поля. */
    class AppField(view: View, private val svc: TranslitKeyboardService) : BaseInputConnection(view, true) {
        private var depth = 0
        private var rs = 0
        private var re = 0
        private var rcs = -1
        private var rce = -1
        private val handler = Handler(Looper.getMainLooper())
        val actions = ArrayList<Int>()

        private val ed: android.text.Editable get() = editable!!

        val text: String get() = ed.toString()
        val composing: String
            get() {
                val e = ed
                val a = getComposingSpanStart(e)
                val b = getComposingSpanEnd(e)
                return if (a in 0..b) e.substring(a, b) else ""
            }

        /** Текст и выделение, как будто их сделал пользователь или приложение. */
        fun set(t: String, s: Int = t.length, e: Int = s) {
            val ed = ed
            removeComposingSpans(ed)
            ed.replace(0, ed.length, t)
            Selection.setSelection(ed, s, e)
            report()
        }

        private fun report() {
            if (depth > 0) return
            val e = ed
            val s = Selection.getSelectionStart(e)
            val en = Selection.getSelectionEnd(e)
            val cs = getComposingSpanStart(e)
            val ce = getComposingSpanEnd(e)
            if (s == rs && en == re && cs == rcs && ce == rce) return
            val os = rs
            val oe = re
            rs = s; re = en; rcs = cs; rce = ce
            handler.post { svc.onUpdateSelection(os, oe, s, en, cs, ce) }
        }

        override fun beginBatchEdit(): Boolean {
            depth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (depth > 0) depth--
            report()
            return depth > 0
        }

        override fun commitText(t: CharSequence?, p: Int) = super.commitText(t, p).also { report() }
        override fun setComposingText(t: CharSequence?, p: Int) = super.setComposingText(t, p).also { report() }
        override fun finishComposingText() = super.finishComposingText().also { report() }
        override fun deleteSurroundingText(b: Int, a: Int) = super.deleteSurroundingText(b, a).also { report() }
        override fun setSelection(s: Int, e: Int) = super.setSelection(s, e).also { report() }
        override fun performEditorAction(a: Int): Boolean {
            actions.add(a)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean = true
    }

    private lateinit var svc: TranslitKeyboardService
    private lateinit var root: View
    private lateinit var app: AppField
    private val offline = FakeOffline()
    private val online = FakeOnline()
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private val saved = listOf(TranslationManager.offline, TranslationManager.online, TranslationManager.runner, TranslationManager.slowRunner)

    @Before
    fun setUp() {
        TranslationManager.offline = offline
        TranslationManager.online = online::translate
        TranslationManager.runner = { it.run() }
        TranslationManager.slowRunner = { it.run() }
        TranslationManager.clearMemoryCache()
    }

    @After
    @Suppress("UNCHECKED_CAST")
    fun tearDown() {
        TranslationManager.offline = saved[0] as OfflineEngine
        TranslationManager.online = saved[1] as (String, String?, String) -> TranslationManager.Result?
        TranslationManager.runner = saved[2] as (Runnable) -> Unit
        TranslationManager.slowRunner = saved[3] as (Runnable) -> Unit
        TranslationManager.clearMemoryCache()
    }

    private fun idle(ms: Long = 0) {
        val l = shadowOf(Looper.getMainLooper())
        if (ms > 0) l.idleFor(Duration.ofMillis(ms)) else l.idle()
    }

    private fun textField() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
    }

    private fun start(prefs: (KbSettings.State) -> KbSettings.State = { it }) {
        KbSettings.update(ctx, prefs)
        svc = Robolectric.buildService(TranslitKeyboardService::class.java).create().get()
        val t0 = System.currentTimeMillis()
        while (!(SuggestionEngine.isLoaded("ru") && SuggestionEngine.isLoaded("en")) &&
            System.currentTimeMillis() - t0 < 60_000
        ) Thread.sleep(50)
        Thread.sleep(300)
        idle()
        app = AppField(View(svc), svc)
        svc.testInputConnection = app
        root = svc.onCreateInputView()
        svc.onStartInput(textField(), false)
        svc.onStartInputView(textField(), false)
        idle()
    }

    private fun all(v: View, out: MutableList<View> = ArrayList()): List<View> {
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), out)
        return out
    }

    private fun visible(v: View): Boolean {
        var x: View? = v
        while (x != null) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }

    private fun texts(): List<String> = all(root).filter { it is TextView && visible(it) }.map { (it as TextView).text.toString() }

    private fun find(label: String): View? =
        all(root).firstOrNull { visible(it) && ((it is TextView && it.text.toString() == label) || it.contentDescription == label) }

    private fun click(label: String) {
        val v = find(label) ?: throw AssertionError("нет «$label» среди ${texts()}")
        v.performClick()
        idle()
    }

    private fun panel(): TranslatePanel? = all(root).firstOrNull { it is TranslatePanel } as TranslatePanel?

    private fun key(type: KeyboardView.Type, label: String = "") {
        svc.onKey(KeyboardView.Key(type, label))
        idle()
    }

    private fun type(s: String) {
        for (c in s) {
            if (c == ' ') key(KeyboardView.Type.SPACE) else key(KeyboardView.Type.CHAR, c.lowercase())
            idle(60)
        }
    }

    private fun pause() = idle(LiveTranslation.DEBOUNCE_MS + 100)

    @Test
    fun translateButtonEvenInTranslitLayout() {
        start()
        assertNotNull("значок перевода и в транслите", find("Перевод"))
    }

    @Test
    fun liveTypingPutsTranslationIntoField() {
        start()
        online.phrases["en:Привет"] = "Hello"
        online.phrases["en:Как дела"] = "How are you"
        click("Перевод")
        assertEquals(TranslatePanel.Mode.LIVE, panel()!!.mode)
        type("привет")
        assertEquals("в строке набора — кириллица", "Привет|", panel()!!.liveTextForTest.toString())
        assertEquals("пока печатают — в поле пусто", "", app.text)
        pause()
        assertEquals("Hello", app.text)
        assertEquals("Hello", app.composing)
        assertTrue(panel()!!.liveStatusForTest.toString(), panel()!!.liveStatusForTest.contains("Google"))

        key(KeyboardView.Type.ENTER)
        assertEquals("Hello", app.text)
        assertEquals("перевод вставлен", "", app.composing)
        assertEquals("", panel()!!.liveTextForTest.toString())
        assertTrue(app.actions.isEmpty())
        key(KeyboardView.Type.ENTER)
        assertEquals("второй ⏎ — отправить", listOf(EditorInfo.IME_ACTION_SEND), app.actions)

        // приложение отправило сообщение и очистило поле — следующее сообщение с нуля
        app.set("")
        idle()
        type("как дела")
        pause()
        assertEquals("How are you", app.text)
        assertEquals(listOf("ru>en:Привет", "ru>en:Как дела"), online.calls)
    }

    @Test
    fun sendingFromAppStartsNewMessage() {
        start()
        click("Перевод")
        type("привет")
        pause()
        assertEquals("[en] Привет", app.text)
        app.set("") // кнопка «отправить» в самом приложении
        idle()
        assertEquals("", panel()!!.liveTextForTest.toString())
        type("мир")
        pause()
        assertEquals("[en] Мир", app.text)
    }

    @Test
    fun closingLeavesTranslation() {
        start()
        click("Перевод")
        type("мир")
        pause()
        click("✕")
        assertNull(panel())
        assertEquals("[en] Мир", app.text)
        assertEquals("", app.composing)
        type("ок")
        assertTrue("клавиши снова пишут в поле транслитом: ${app.text}", app.text.startsWith("[en] Мир") && app.text.endsWith("ok"))
    }

    @Test
    fun translatesFieldTextWrittenInTranslit() {
        start()
        online.phrases["en:привет мир"] = "hello world"
        app.set("privet mir")
        idle()
        click("Перевод")
        click("Поле")
        assertEquals(TranslatePanel.Mode.RESULT, panel()!!.mode)
        assertEquals("hello world", panel()!!.resultForTest.toString())
        assertEquals(listOf("ru>en:привет мир"), online.calls)
        click("Заменить всё")
        assertEquals("hello world", app.text)
        assertNull(panel())
    }

    @Test
    fun englishSelectionInTranslitLayoutStaysEnglish() {
        start()
        online.phrases["ru:See you tomorrow at the station!"] = "Увидимся завтра на вокзале!"
        app.set("See you tomorrow at the station!", 0, 32)
        idle()
        click(InputEngine.LABEL_TRANSLATE)
        assertEquals("английское — на русский, а не «Сее ёю…»", "Увидимся завтра на вокзале!", panel()!!.resultForTest.toString())
    }

    @Test
    fun selectionChipTranslatesSelection() {
        start { it.copy(layoutName = "CYRILLIC") }
        online.phrases["en:Привет"] = "Hello"
        app.set("Как дела? Привет", 10, 16)
        idle()
        click(InputEngine.LABEL_TRANSLATE)
        assertEquals("Hello", panel()!!.resultForTest.toString())
        click("Заменить")
        assertEquals("Как дела? Hello", app.text)
    }

    @Test
    fun englishFieldTextGoesToRussian() {
        start { it.copy(layoutName = "LATIN") }
        online.phrases["ru:Good morning"] = "Доброе утро"
        app.set("Good morning")
        idle()
        click("Перевод")
        click("Поле")
        assertEquals("своё на языке перевода — переводим на русский", "Доброе утро", panel()!!.resultForTest.toString())
    }

    @Test
    fun copiedTextIsOfferedAndTranslatedToRussian() {
        start()
        online.phrases["ru:Guten Morgen"] = "Доброе утро"
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("msg", "Guten Morgen"))
        svc.onStartInputView(textField(), false)
        idle()
        click(InputEngine.LABEL_CLIPBOARD)
        assertEquals("Доброе утро", panel()!!.resultForTest.toString())
        click("Вставить")
        assertEquals("Доброе утро", app.text)
        // то же скопированное второй раз не предлагаем
        svc.onStartInputView(textField(), false)
        idle()
        assertNull(find(InputEngine.LABEL_CLIPBOARD))
    }

    @Test
    fun offlineModeAsksToDownloadAndNeverGoesOnline() {
        start { it.copy(translateMode = "OFFLINE") }
        click("Перевод")
        type("привет")
        pause()
        assertEquals("", app.text)
        assertTrue(panel()!!.liveStatusForTest.toString(), panel()!!.liveStatusForTest.contains("Русский"))
        click("Скачать")
        assertEquals(listOf(listOf("ru")), offline.downloads)
        idle()
        assertEquals("[en offline] Привет", app.text)
        assertTrue("текст не уходил в сеть", online.calls.isEmpty())
    }

    @Test
    fun pickingLanguageRetranslates() {
        start()
        click("Перевод")
        type("мир")
        pause()
        assertEquals("[en] Мир", app.text)
        click("Русский → English ▾")
        assertEquals(TranslatePanel.Mode.PICKER, panel()!!.mode)
        click("Deutsch")
        assertEquals(TranslatePanel.Mode.LIVE, panel()!!.mode)
        assertEquals("de", KbSettings.get(ctx).translateTarget)
        assertNotNull(find("Русский → Deutsch ▾"))
        idle()
        assertEquals("[de] Мир", app.text)
    }
}
