package com.example.translit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Набор с переводом: строка набора → перевод в поле приложения. */
class LiveTranslationTest {

    /** Таймер с ручным временем. */
    private class ManualScheduler : LiveTranslation.Scheduler {
        var now = 0L
        private val tasks = ArrayList<Pair<Long, Runnable>>()
        override fun post(delayMs: Long, r: Runnable) {
            tasks.add(now + delayMs to r)
        }

        override fun cancel(r: Runnable) {
            tasks.removeAll { it.second === r }
        }

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { it.first <= until }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                now = maxOf(now, next.first)
                next.second.run()
            }
            now = until
        }
    }

    /** Переводчик, который отвечает, когда скажут. */
    private class PendingTranslator : LiveTranslation.Translator {
        val requests = ArrayList<Pair<String, (TranslationManager.Result?, Exception?) -> Unit>>()
        override fun translate(text: String, done: (TranslationManager.Result?, Exception?) -> Unit) {
            requests.add(text to done)
        }

        fun answer(i: Int = requests.size - 1, fail: String? = null) {
            val (text, done) = requests[i]
            if (fail != null) done(null, TranslationManager.Failure(fail))
            else done(TranslationManager.Result(en(text), "ru", TranslationManager.ENGINE_GOOGLE), null)
        }

        companion object {
            private val words = mapOf(
                "привет" to "hello", "мир" to "world", "как" to "how", "дела" to "are you",
                "Привет" to "Hello", "Как" to "How"
            )

            fun en(text: String) = text.split(' ').joinToString(" ") { words[it.trim(',', '.')] ?: it }
        }
    }

    /** Поле приложения. */
    private class AppField : LiveTranslation.Field {
        val ed = TextBuffer()
        override fun textBefore(n: Int): String = ed.textBeforeCursor(n).toString()
        override fun replace(oldLen: Int, text: String, composing: Boolean) {
            ed.beginBatch()
            ed.finishComposingText()
            if (oldLen > 0) ed.deleteBefore(oldLen)
            if (text.isNotEmpty()) {
                if (composing) ed.setComposingText(text) else ed.commitText(text)
            }
            ed.endBatch()
        }

        override fun finishComposing() = ed.finishComposingText()
        val text get() = ed.text.toString()
        val composing get() = if (ed.compS >= 0) ed.text.substring(ed.compS, ed.compE) else ""
    }

    private lateinit var sch: ManualScheduler
    private lateinit var tr: PendingTranslator
    private lateinit var field: AppField
    private lateinit var live: LiveTranslation

    @Before
    fun setUp() {
        Dicts.ensure()
        sch = ManualScheduler()
        tr = PendingTranslator()
        field = AppField()
        live = LiveTranslation(
            field, tr, sch, settings = EngineSettings(autocorrect = false, learn = false, autoCaps = false)
        )
        live.engine.configure("ru", null, KeyboardLayoutManager.proximity(KeyboardLayoutManager.RU_LETTERS))
    }

    private fun type(s: String) {
        for (ch in s) {
            when (ch) {
                ' ' -> live.engine.onSpace()
                '⌫' -> live.engine.onBackspace()
                else -> if (ch.isLetter()) live.engine.onLetter(ch) else live.engine.onText(ch.toString())
            }
            sch.advance(50)
        }
    }

    @Test
    fun translatesAfterPause() {
        type("привет")
        assertTrue("пока печатают — не переводим", tr.requests.isEmpty())
        assertEquals("", field.text)
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        assertEquals(listOf("привет"), tr.requests.map { it.first })
        assertEquals(LiveTranslation.State.TRANSLATING, live.state)
        tr.answer()
        assertEquals("hello", field.text)
        assertEquals("подчёркнуто, пока набор не закончен", "hello", field.composing)
        assertEquals(LiveTranslation.State.DONE, live.state)
        assertEquals("привет", live.buffer.text.toString())
    }

    @Test
    fun newTextWhileTranslatingIsTranslatedNext() {
        type("привет")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        type(" мир")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        assertEquals("одновременно — один перевод", 1, tr.requests.size)
        tr.answer(0)
        assertEquals("устаревший перевод всё равно показан", "hello", field.text)
        assertEquals(listOf("привет", "привет мир"), tr.requests.map { it.first })
        tr.answer(1)
        assertEquals("hello world", field.text)
        assertEquals("hello world", field.composing)
    }

    @Test
    fun enterCommitsFinalTranslation() {
        type("привет мир")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        tr.answer()
        assertTrue(live.commit())
        assertEquals("hello world", field.text)
        assertEquals("перевод вставлен обычным текстом", "", field.composing)
        assertEquals("", live.buffer.text.toString())
        assertTrue(live.committed)
        sch.advance(10)
        assertFalse("строка пуста — ⏎ работает как обычно (отправить)", live.commit())
    }

    @Test
    fun enterBeforeTranslationWaitsForIt() {
        type("как дела")
        assertTrue(live.commit())
        assertTrue(live.commitPending)
        assertEquals(listOf("как дела"), tr.requests.map { it.first })
        tr.answer()
        assertEquals("how are you", field.text)
        assertEquals("", field.composing)
        assertEquals("", live.buffer.text.toString())
        assertFalse(live.commitPending)
    }

    @Test
    fun failedCommitKeepsText() {
        type("привет")
        assertTrue(live.commit())
        tr.answer(fail = "нет связи")
        assertEquals(LiveTranslation.State.ERROR, live.state)
        assertEquals("нет связи", live.error)
        assertEquals("привет", live.buffer.text.toString())
        assertFalse(live.commitPending)
        assertEquals("", field.text)
    }

    @Test
    fun spaceBeforeTranslationAfterExistingText() {
        field.ed.commitText("Ok")
        type("мир")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        tr.answer()
        assertEquals("Ok world", field.text)
        live.commit()
        assertEquals("Ok world", field.text)
    }

    @Test
    fun erasingEverythingRemovesTranslation() {
        type("мир")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        tr.answer()
        assertEquals("world", field.text)
        type("⌫⌫⌫")
        assertEquals("", live.buffer.text.toString())
        assertEquals("", field.text)
        assertEquals(LiveTranslation.State.IDLE, live.state)
    }

    @Test
    fun appClearingFieldStartsNewMessage() {
        type("привет")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        tr.answer()
        // приложение отправило сообщение и очистило поле
        field.ed.clear()
        live.onFieldUpdate()
        assertEquals("", live.buffer.text.toString())
        assertEquals("", live.shown)
        type("мир")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        assertEquals("мир", tr.requests.last().first)
        tr.answer()
        assertEquals("world", field.text)
    }

    @Test
    fun ownUpdatesDoNotResetMessage() {
        type("привет")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        tr.answer()
        live.onFieldUpdate()
        assertEquals("привет", live.buffer.text.toString())
        assertEquals("hello", live.shown)
    }

    @Test
    fun retranslateIgnoresOldResult() {
        type("мир")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        live.retranslate() // сменили язык перевода, пока шёл перевод
        assertEquals(1, tr.requests.size)
        tr.answer(0)
        assertEquals("старый перевод не показываем", "", field.text)
        assertEquals(2, tr.requests.size)
        tr.answer(1)
        assertEquals("world", field.text)
    }

    @Test
    fun closeLeavesTranslationAsText() {
        type("мир")
        sch.advance(LiveTranslation.DEBOUNCE_MS)
        tr.answer()
        live.close()
        assertEquals("world", field.text)
        assertEquals("", field.composing)
        type("привет")
        sch.advance(1000)
        assertEquals("после закрытия не переводим", 1, tr.requests.size)
    }
}
