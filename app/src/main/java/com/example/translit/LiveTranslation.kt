package com.example.translit

/**
 * Режим «пишу по-русски — отправляю по-английски»: текст набирается в строке клавиатуры
 * ([buffer] — со всеми подсказками, исправлениями и транслитом), а в поле приложения сразу
 * попадает перевод (подчёркнутым, пока набор не закончен). ⏎ вставляет окончательный перевод;
 * если приложение само убрало перевод из поля (сообщение отправлено) — начинается новое сообщение.
 *
 * Без Android-зависимостей: поле, переводчик и таймер передаются снаружи (в тестах — подделки).
 */
class LiveTranslation(
    private val field: Field,
    private val translator: Translator,
    private val scheduler: Scheduler,
    private val profile: FieldProfile = LIVE_PROFILE,
    settings: EngineSettings = EngineSettings()
) {
    /** Поле приложения. */
    interface Field {
        /** [n] символов перед курсором. */
        fun textBefore(n: Int): String?

        /** Одним пакетом заменить [oldLen] символов перед курсором на [text] (подчёркнутым, если [composing]). */
        fun replace(oldLen: Int, text: String, composing: Boolean)

        fun finishComposing()
    }

    /** Перевести [text]; [done] вызвать в том же потоке, что и все остальные вызовы (главном). */
    fun interface Translator {
        fun translate(text: String, done: (TranslationManager.Result?, Exception?) -> Unit)
    }

    interface Scheduler {
        fun post(delayMs: Long, r: Runnable)
        fun cancel(r: Runnable)
    }

    enum class State { IDLE, TYPING, TRANSLATING, DONE, ERROR }

    companion object {
        /** Пауза в наборе, после которой переводим. */
        const val DEBOUNCE_MS = 350L

        val LIVE_PROFILE = FieldProfile(
            FieldProfile.Kind.TEXT, suggestions = true, autocorrect = true, learning = true,
            capsModes = FieldProfile.CAP_SENTENCES, composing = true, enterAction = 0, multiLine = false
        )
    }

    /** Строка набора. */
    val buffer = TextBuffer()
    val engine = InputEngine(buffer)

    /** Изменились строка набора, подсказки или состояние перевода — перерисовать. */
    var onChange: (() -> Unit)? = null

    var state = State.IDLE
        private set
    /** Ошибка последнего перевода и каких языков не хватает для перевода без интернета. */
    var error: String? = null
        private set
    var missing: List<String> = emptyList()
        private set
    /** Последний перевод (чем переведено, каких языков не хватает для перевода без интернета). */
    var lastResult: TranslationManager.Result? = null
        private set
    /** Перевод только что вставлен по ⏎ — следующий ⏎ отправит сообщение. */
    var committed = false
        private set
    /** Ждём перевод, чтобы вставить его по ⏎. */
    var commitPending = false
        private set

    /** Что вставлено нами в поле сейчас (перевод, возможно с пробелом впереди). */
    var shown = ""
        private set

    val isEmpty: Boolean get() = buffer.text.isEmpty()

    private val updates = ArrayDeque<IntArray>()
    private var lastText = ""
    private var inFlight: String? = null
    private var requestWaiting = false
    private var resultFor: String? = null
    private var lastTranslation = ""
    private var prefix = ""
    private var gen = 0
    private var closed = false
    private var inOp = false

    private val deliver = Runnable { deliverUpdates() }
    private val tick = Runnable { requestTranslation() }

    init {
        // изменения строки набора доходят до движка позже, как onUpdateSelection от настоящего поля
        buffer.onUpdate = { u ->
            updates.addLast(u)
            scheduler.cancel(deliver)
            scheduler.post(0, deliver)
        }
        engine.listener = {
            textChanged()
            onChange?.invoke()
        }
        engine.settings = settings
        engine.startInput(profile, 0, 0)
    }

    private fun deliverUpdates() {
        while (updates.isNotEmpty()) {
            val u = updates.removeFirst()
            engine.onUpdateSelection(u[0], u[1], u[2], u[3], u[4], u[5])
        }
    }

    // ================================================================ набор → перевод

    private fun textChanged() {
        if (closed) return
        val t = buffer.text.toString()
        if (t == lastText) return
        lastText = t
        error = null
        missing = emptyList()
        if (t.isNotBlank()) committed = false
        scheduler.cancel(tick)
        if (t.isBlank()) {
            // всё стёрли — убираем перевод из поля
            gen++
            requestWaiting = false
            commitPending = false
            state = State.IDLE
            show("")
            return
        }
        state = State.TYPING
        scheduler.post(DEBOUNCE_MS, tick)
    }

    private fun requestTranslation() {
        if (closed) return
        val t = lastText
        if (t.isBlank()) return
        if (inFlight != null) {
            // переведём, когда закончится текущий перевод
            requestWaiting = true
            return
        }
        requestWaiting = false
        inFlight = t
        val g = gen
        if (state != State.DONE || resultFor != t) state = State.TRANSLATING
        onChange?.invoke()
        translator.translate(t.trim()) { r, e -> onResult(g, t, r, e) }
    }

    private fun onResult(g: Int, t: String, r: TranslationManager.Result?, e: Exception?) {
        inFlight = null
        if (closed) return
        if (g != gen) {
            if (requestWaiting) requestTranslation()
            return
        }
        val stale = t != lastText
        if (r != null) {
            lastResult = r
            error = null
            missing = r.offlineMissing // переведено онлайн — можно скачать языки, чтобы было без интернета
            if (!stale) {
                resultFor = t
                lastTranslation = r.text
            }
            if (commitPending && !stale) {
                finishCommit(r.text)
                return
            }
            // и устаревший перевод (начала текста) лучше прежнего
            show(r.text)
            state = if (stale) State.TYPING else State.DONE
        } else {
            error = e?.message ?: "не удалось перевести"
            missing = (e as? TranslationManager.Failure)?.missing ?: emptyList()
            state = State.ERROR
            if (!stale) commitPending = false
        }
        onChange?.invoke()
        if (requestWaiting || (commitPending && stale)) requestTranslation()
    }

    /** Показать перевод в поле вместо прежнего. */
    private fun show(translation: String) {
        if (shown.isEmpty()) prefix = spaceBefore(translation)
        val out = if (translation.isEmpty()) "" else prefix + translation
        if (out == shown) return
        replaceShown(out, composing = true)
    }

    private fun replaceShown(out: String, composing: Boolean) {
        inOp = true
        try {
            val old = shown
            val intact = old.isEmpty() || field.textBefore(old.length) == old
            field.replace(if (intact) old.length else 0, out, composing)
            shown = if (composing) out else ""
        } finally {
            inOp = false
        }
    }

    /** Пробел перед переводом, если иначе он приклеится к тексту в поле. */
    private fun spaceBefore(translation: String): String {
        val first = translation.firstOrNull() ?: return ""
        if (!first.isLetterOrDigit()) return ""
        val before = field.textBefore(1)
        return if (!before.isNullOrEmpty() && !before[0].isWhitespace()) " " else ""
    }

    // ================================================================ ⏎ и сброс

    /** ⏎: вставить окончательный перевод. false — строка набора пуста, ⏎ работает как обычно. */
    fun commit(): Boolean {
        if (closed) return false
        engine.finishWordAutocorrect()
        val t = buffer.text.toString()
        if (t.isBlank()) return false
        if (commitPending) return true
        commitPending = true
        lastText = t
        scheduler.cancel(tick)
        if (inFlight == null && resultFor == t && state == State.DONE) {
            finishCommit(lastTranslation)
            return true
        }
        requestTranslation()
        onChange?.invoke()
        return true
    }

    private fun finishCommit(translation: String) {
        if (shown.isEmpty()) prefix = spaceBefore(translation)
        replaceShown(if (translation.isEmpty()) "" else prefix + translation, composing = false)
        commitPending = false
        resetBuffer()
        committed = true
        state = State.IDLE
        onChange?.invoke()
    }

    /** Поле приложения изменилось (onUpdateSelection): не убрали ли оттуда наш перевод. */
    fun onFieldUpdate() {
        if (closed || inOp || shown.isEmpty()) return
        if (field.textBefore(shown.length) == shown) return
        // сообщение отправлено, поле очищено или курсор переставлен — начинаем новое сообщение
        field.finishComposing()
        shown = ""
        resetBuffer()
        committed = false
        state = State.IDLE
        error = null
        missing = emptyList()
        onChange?.invoke()
    }

    /** Язык перевода или раскладка сменились — перевести заново. */
    fun retranslate() {
        if (closed) return
        gen++
        requestWaiting = false
        resultFor = null
        error = null
        missing = emptyList()
        scheduler.cancel(tick)
        val t = buffer.text.toString()
        lastText = t
        if (t.isBlank()) {
            onChange?.invoke()
            return
        }
        state = State.TRANSLATING
        requestTranslation()
    }

    private fun resetBuffer() {
        gen++
        scheduler.cancel(tick)
        commitPending = false
        requestWaiting = false
        buffer.clear()
        updates.clear()
        scheduler.cancel(deliver)
        lastText = ""
        resultFor = null
        lastTranslation = ""
        engine.startInput(profile, 0, 0)
    }

    /** Выход из режима: перевод остаётся в поле обычным текстом. */
    fun close() {
        if (closed) return
        engine.finishComposing()
        closed = true
        scheduler.cancel(tick)
        scheduler.cancel(deliver)
        if (shown.isNotEmpty()) field.finishComposing()
        shown = ""
    }
}
