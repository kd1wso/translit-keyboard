package com.example.translit

import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.max

/** Минимальный интерфейс к полю ввода (в приложении — обёртка над InputConnection). */
interface EditorBridge {
    fun textBeforeCursor(n: Int): CharSequence?
    fun textAfterCursor(n: Int): CharSequence?
    fun setComposingText(text: CharSequence)
    fun finishComposingText()
    fun commitText(text: CharSequence)
    fun deleteBefore(n: Int)
    fun setSelection(start: Int, end: Int)
    fun sendKey(keyCode: Int)
    fun performEditorAction(action: Int)
    fun cursorCapsMode(reqModes: Int): Int
    fun beginBatch()
    fun endBatch()
}

/** Что за поле: от этого зависят подсказки, автоисправление, обучение и раскладка. */
data class FieldProfile(
    val kind: Kind,
    val suggestions: Boolean,
    val autocorrect: Boolean,
    val learning: Boolean,
    /** Запрошенные полем режимы заглавных (TextUtils.CAP_MODE_*), 0 — не нужно. */
    val capsModes: Int,
    /** Набирать слово как composing-текст (подчёркнутое, редактируемое целиком). */
    val composing: Boolean,
    /** EditorInfo.IME_ACTION_* для Enter или 0 — обычный перевод строки. */
    val enterAction: Int,
    val multiLine: Boolean
) {
    enum class Kind { TEXT, EMAIL, URI, PASSWORD, NUMBER, PHONE, DATETIME, RAW }

    companion object {
        const val CAP_CHARACTERS = 0x1000
        const val CAP_WORDS = 0x2000
        const val CAP_SENTENCES = 0x4000

        val TEXT = FieldProfile(Kind.TEXT, true, true, true, CAP_SENTENCES, true, 0, true)
        val PASSWORD = FieldProfile(Kind.PASSWORD, false, false, false, 0, false, 0, false)
    }
}

data class EngineSettings(
    val autocorrect: Boolean = true,
    val suggestions: Boolean = true,
    val learn: Boolean = true,
    val autoCaps: Boolean = true,
    val doubleSpacePeriod: Boolean = true
)

/**
 * Логика ввода без Android-зависимостей: набор слова, транслит, подсказки, автоисправление
 * с отменой, регистр, пробелы и пунктуация, удаление, отслеживание курсора.
 */
class InputEngine(private val ed: EditorBridge) {

    enum class Shift { OFF, ONCE, AUTO, LOCKED }
    enum class ChipKind { TYPED, CORRECTION, WORD, PREDICTION }
    data class Chip(val label: String, val kind: ChipKind, val word: String)

    companion object {
        const val KEY_DEL = 67
        const val KEY_ENTER = 66
        const val KEY_LEFT = 21
        const val KEY_RIGHT = 22

        private const val OPENERS = "([{«\"'„“‘<"
        private const val CORRECTING_SEPARATORS = ".,!?;:)»\"…"
        private const val SWAP_PUNCT = ".,!?"
        private const val SENTENCE_END = ".!?…"
        private const val DOUBLE_SPACE_MS = 1200L
        private const val SHIFT_DOUBLE_TAP_MS = 400L

        fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '_'

        /**
         * Начало последнего символа (графемы) строки: эмодзи с тоном кожи, семьи через ZWJ,
         * флаги, буквы с диакритикой удаляются целиком.
         */
        fun graphemeStart(s: CharSequence): Int {
            val end = s.length
            if (end == 0) return 0
            var start = end - 1
            try {
                val it = BreakIterator.getCharacterInstance()
                it.setText(s.toString())
                val p = it.preceding(end)
                if (p != BreakIterator.DONE && p < end) start = p
            } catch (_: Exception) {
            }
            return minOf(start, manualGraphemeStart(s))
        }

        private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

        private fun isExtend(cp: Int): Boolean {
            if (cp in 0x1F3FB..0x1F3FF || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3) return true
            if (cp in 0xE0020..0xE007F) return true
            val t = Character.getType(cp)
            return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() ||
                t == Character.COMBINING_SPACING_MARK.toInt()
        }

        private fun manualGraphemeStart(s: CharSequence): Int {
            val end = s.length
            if (end >= 2 && s[end - 1] == '\n' && s[end - 2] == '\r') return end - 2
            if (isRegional(Character.codePointBefore(s, end))) {
                var n = 0
                var j = end
                while (j > 0) {
                    val c = Character.codePointBefore(s, j)
                    if (!isRegional(c)) break
                    n++
                    j -= Character.charCount(c)
                }
                return end - if (n % 2 == 0) 4 else 2
            }
            var i = end
            while (true) {
                while (i > 0 && isExtend(Character.codePointBefore(s, i))) i -= Character.charCount(Character.codePointBefore(s, i))
                if (i > 0) i -= Character.charCount(Character.codePointBefore(s, i))
                if (i > 1 && s[i - 1] == '\u200D') {
                    i--
                    continue
                }
                return i
            }
        }

        /** Начало последнего слова (вместе с пробелами после него) — для удаления словами. */
        fun wordBoundary(s: CharSequence, end: Int = s.length): Int {
            var i = end
            while (i > 0 && s[i - 1].isWhitespace()) i--
            if (i > 0 && isWordChar(s[i - 1])) {
                while (i > 0 && isWordChar(s[i - 1])) i--
            } else {
                while (i > 0 && !isWordChar(s[i - 1]) && !s[i - 1].isWhitespace()) i--
            }
            return i
        }
    }

    var listener: (() -> Unit)? = null
    var clock: () -> Long = { System.currentTimeMillis() }
    var settings = EngineSettings()

    var lang = ""
        private set
    var translit: TranslitMode? = null
        private set
    private var prox: Map<Char, Set<Char>> = emptyMap()
    var profile = FieldProfile.TEXT
        private set

    var shift = Shift.OFF
        private set
    var chips: List<Chip> = emptyList()
        private set

    // --- набираемое слово (composing)
    private val word = StringBuilder()
    private val locked = ArrayList<Boolean>()
    private var wordStrict = false
    private var composingActive = false

    // --- набор без composing: вывод каждой буквы, чтобы ⌫ удалял «щ» → «sch» целиком
    private val direct = ArrayList<String>()

    // --- контекст: последние слова (нормализованные), последнее — в конце
    private val context = ArrayList<String>(3)

    private class Correction(
        val typedRendered: String,
        val fixedRendered: String,
        val typedKey: String,
        val fixedKey: String,
        var sep: String,
        val lang: String
    )

    private var lastCorrection: Correction? = null
    private var phantomSpace = false

    private enum class Last { NONE, LETTER, SPACE, PICK, SWAP, PERIOD, OTHER }

    private var last = Last.NONE
    private var lastSpaceTime = 0L
    private var lastShiftTap = 0L

    // --- ожидаемое положение курсора (для отличия своих изменений от чужих)
    private var selS = -1
    private var selE = -1
    private var compS = -1

    // --- выделение свайпом по ⌫
    private var swipeAnchor = -1
    private var swipeText = ""

    val isComposing: Boolean get() = word.isNotEmpty()

    // ================================================================ настройка

    fun configure(lang: String, translit: TranslitMode?, prox: Map<Char, Set<Char>>) {
        finishComposing()
        this.lang = lang
        this.translit = translit
        this.prox = prox
        context.clear()
        lastCorrection = null
        direct.clear()
        refresh()
    }

    fun startInput(p: FieldProfile, selStart: Int, selEnd: Int) {
        if (composingActive) ed.finishComposingText()
        composingActive = false
        resetWord()
        direct.clear()
        context.clear()
        lastCorrection = null
        phantomSpace = false
        last = Last.NONE
        swipeAnchor = -1
        profile = p
        selS = selStart
        selE = selEnd
        compS = -1
        shift = Shift.OFF
        updateAutoCaps()
        refresh()
    }

    // ================================================================ курсор

    fun onUpdateSelection(oldS: Int, oldE: Int, newS: Int, newE: Int, candS: Int, candE: Int) {
        if (selS < 0) {
            // положение было неизвестно (после клавиш-событий) — принимаем как есть
            selS = newS
            selE = newE
            if (composingActive && candS >= 0) compS = candS
            if (word.isEmpty()) {
                updateAutoCaps()
                refresh()
            }
            return
        }
        if (isBelated(oldS, oldE, newS, newE)) {
            if (newS == selS && newE == selE && composingActive && word.isNotEmpty() && candS < 0) {
                // поле само сбросило composing — не продолжаем «призрачное» слово
                ed.finishComposingText()
                composingActive = false
                resetWord()
                updateAutoCaps()
                refresh()
            }
            return
        }
        // курсор переместил пользователь или приложение изменило текст
        selS = newS
        selE = newE
        compS = -1
        if (composingActive) ed.finishComposingText()
        composingActive = false
        resetWord()
        direct.clear()
        context.clear()
        lastCorrection = null
        phantomSpace = false
        last = Last.NONE
        swipeAnchor = -1
        updateAutoCaps()
        refresh()
    }

    private fun isBelated(oldS: Int, oldE: Int, newS: Int, newE: Int): Boolean {
        if (selS == newS && selE == newE) return true
        if (selS == oldS && selE == oldE && (oldS != newS || oldE != newE)) return false
        return newS == newE &&
            (newS - oldS).toLong() * (selS - newS) >= 0 &&
            (newE - oldE).toLong() * (selE - newE) >= 0
    }

    private fun hasSelection(): Boolean = selS >= 0 && selE > selS

    // ================================================================ операции с полем

    private inline fun batch(block: () -> Unit) {
        ed.beginBatch()
        try {
            block()
        } finally {
            ed.endBatch()
        }
    }

    private fun edCommit(text: String) {
        val base = if (composingActive) compS else if (selS >= 0) minOf(selS, selE) else -1
        ed.commitText(text)
        composingActive = false
        compS = -1
        if (base >= 0) {
            selS = base + text.length
            selE = selS
        } else {
            selS = -1
            selE = -1
        }
    }

    private fun edCompose(text: String) {
        val base = if (composingActive) compS else if (selS >= 0) minOf(selS, selE) else -1
        ed.setComposingText(text)
        if (text.isEmpty()) {
            ed.finishComposingText()
            composingActive = false
            compS = -1
        } else {
            composingActive = true
            compS = base
        }
        if (base >= 0) {
            selS = base + text.length
            selE = selS
        } else {
            selS = -1
            selE = -1
        }
    }

    private fun edDelete(n: Int) {
        if (n <= 0) return
        ed.deleteBefore(n)
        if (selS >= 0) {
            selS = max(0, selS - n)
            selE = selS
        }
    }

    private fun edKey(code: Int) {
        ed.sendKey(code)
        selS = -1
        selE = -1
    }

    // ================================================================ вспомогательное

    private fun resetWord() {
        word.setLength(0)
        locked.clear()
        wordStrict = false
    }

    private fun render(src: CharSequence, locks: List<Boolean>?): String =
        translit?.translitWord(src, locks) ?: src.toString()

    private fun isAllCaps(s: CharSequence, locks: List<Boolean>?): Boolean {
        if (locks != null && locks.isNotEmpty() && locks.all { it }) return true
        var letters = 0
        for (c in s) if (c.isLetter()) {
            if (!c.isUpperCase()) return false
            letters++
        }
        return letters >= 2
    }

    private fun isAllLower(s: CharSequence): Boolean = s.none { it.isLetter() && it.isUpperCase() }

    /** Применяет регистр набранного слова к подсказке. */
    private fun applyCase(w: String, typed: CharSequence, locks: List<Boolean>?): String = when {
        typed.isEmpty() -> w
        isAllCaps(typed, locks) -> w.uppercase()
        typed[0].isUpperCase() -> w.replaceFirstChar { it.uppercaseChar() }
        else -> w
    }

    private fun renderCased(w: String, typed: CharSequence, locks: List<Boolean>?): String {
        val cased = applyCase(w, typed, locks)
        val allCaps = isAllCaps(typed, locks)
        return render(cased, if (allCaps) List(cased.length) { true } else null)
    }

    private fun canCorrect(): Boolean =
        settings.autocorrect && profile.autocorrect && lang.isNotEmpty() && SuggestionEngine.isLoaded(lang)

    private fun canLearn(): Boolean = settings.learn && profile.learning && lang.isNotEmpty()

    private fun useComposing(): Boolean = profile.composing && (lang.isNotEmpty() || translit != null)

    /** Курсор «приклеен» к тексту: перед ним буква/цифра/символ, после — буква. */
    private fun attachedToText(): Boolean {
        val before = ed.textBeforeCursor(1)
        if (!before.isNullOrEmpty()) {
            val b = before[before.length - 1]
            if (!(b.isWhitespace() || OPENERS.indexOf(b) >= 0)) return true
        }
        val after = ed.textAfterCursor(1)
        if (!after.isNullOrEmpty()) {
            val a = after[0]
            if (a.isLetterOrDigit() || a == '\'' || a == '’' || a == '_' || a == '-') return true
        }
        return false
    }

    private fun pushContext(phrase: String) {
        // исправление может быть из двух слов («в общем») — контекстом служит последнее
        val key = phrase.substringAfterLast(' ')
        if (key.isEmpty()) return
        context.add(key)
        while (context.size > 2) context.removeAt(0)
    }

    private fun learn(key: String, spelling: String?, inc: Int = 1) {
        if (!canLearn() || key.length < 2 || key.length > 40) return
        if (key.any { !(it.isLetter() || it == '\'' || it == '-') }) return
        UserHistory.learn(lang, key, spelling, inc)
        context.lastOrNull()?.let { UserHistory.learnPair(lang, it, key) }
    }

    /** Написание для обучения: заглавную сохраняем только у слов вроде имён (не начало фразы). */
    private fun learnedSpelling(out: String, key: String, strict: Boolean): String? {
        if (SuggestionEngine.dict(lang).indexOf(key) >= 0) return null
        val hasUpper = out.any { it.isUpperCase() }
        if (!hasUpper) return null
        if (isAllCaps(out, null)) return if (out.length <= 5) out else null
        val innerUpper = out.drop(1).any { it.isUpperCase() }
        return if (strict || innerUpper) out else null
    }

    // ================================================================ регистр

    private fun updateAutoCaps() {
        if (shift == Shift.LOCKED || shift == Shift.ONCE) return
        if (word.isNotEmpty() || direct.isNotEmpty()) {
            if (shift == Shift.AUTO) shift = Shift.OFF
            return
        }
        val want = settings.autoCaps && profile.capsModes != 0 &&
            ed.cursorCapsMode(profile.capsModes) != 0
        shift = if (want) Shift.AUTO else Shift.OFF
    }

    fun onShiftTap() {
        val now = clock()
        val old = shift
        val dbl = now - lastShiftTap < SHIFT_DOUBLE_TAP_MS
        shift = when {
            old == Shift.LOCKED -> Shift.OFF
            dbl -> Shift.LOCKED
            old == Shift.OFF -> Shift.ONCE
            else -> Shift.OFF
        }
        lastShiftTap = if (shift == Shift.LOCKED || old == Shift.LOCKED) 0 else now
        refresh()
    }

    fun onShiftLongPress() {
        shift = Shift.LOCKED
        lastShiftTap = 0
        refresh()
    }

    /** Подписи клавиш — заглавными? */
    val upperCase: Boolean get() = shift != Shift.OFF

    // ================================================================ ввод букв

    fun onLetter(ch: Char) {
        val upper = shift != Shift.OFF
        val lock = shift == Shift.LOCKED
        val c = if (upper) ch.uppercaseChar() else ch
        batch {
            if (word.isEmpty() && direct.isEmpty()) {
                if (phantomSpace) {
                    edCommit(" ")
                    phantomSpace = false
                }
                lastCorrection = null
                val composes = useComposing() && !attachedToText()
                if (composes) {
                    wordStrict = upper && !lock && shift != Shift.AUTO &&
                        ed.cursorCapsMode(FieldProfile.CAP_SENTENCES) == 0
                    word.append(c)
                    locked.add(lock)
                    edCompose(render(word, locked))
                } else {
                    directLetter(c, upper, lock)
                }
            } else if (word.isNotEmpty()) {
                word.append(c)
                locked.add(lock)
                edCompose(render(word, locked))
            } else {
                directLetter(c, upper, lock)
            }
        }
        phantomSpace = false
        lastCorrection = null
        last = Last.LETTER
        lastShiftTap = 0
        if (shift == Shift.ONCE || shift == Shift.AUTO) shift = Shift.OFF
        refresh()
    }

    private fun directLetter(c: Char, upper: Boolean, lock: Boolean) {
        val out = translit?.translit(c, upper, lock) ?: c.toString()
        if (out.isEmpty()) return
        edCommit(out)
        direct.add(out)
        if (direct.size > 64) direct.removeAt(0)
    }

    /** Любой текст с клавиши: буква, символ, цифра, эмодзи, «.com». */
    fun onText(text: String) {
        if (text.isEmpty()) return
        val ch = if (text.length == 1) text[0] else null
        if (ch != null && ch.isLetter()) {
            onLetter(ch)
            return
        }
        if (ch != null && (ch == '-' || ch == '\'' || ch == '’') && word.isNotEmpty() &&
            word[word.length - 1].isLetter()
        ) {
            // дефис/апостроф внутри слова: кто-то, don't
            word.append(ch)
            locked.add(false)
            batch { edCompose(render(word, locked)) }
            last = Last.LETTER
            refresh()
            return
        }
        batch {
            val correcting = ch != null && CORRECTING_SEPARATORS.indexOf(ch) >= 0
            if (word.isNotEmpty()) finishWord(correcting, text) else lastCorrection = null
            direct.clear()
            if (ch != null && SWAP_PUNCT.indexOf(ch) >= 0 &&
                (last == Last.SPACE || last == Last.PICK || last == Last.SWAP) &&
                ed.textBeforeCursor(1)?.toString() == " "
            ) {
                // «привет ,» → «привет, »
                edDelete(1)
                edCommit("$ch ")
                last = Last.SWAP
                lastCorrection = null
            } else {
                if (phantomSpace && (ch == null || CORRECTING_SEPARATORS.indexOf(ch) < 0)) edCommit(" ")
                edCommit(text)
                last = Last.OTHER
            }
            phantomSpace = false
        }
        if (ch != null && (SENTENCE_END.indexOf(ch) >= 0 || ch == '\n')) context.clear()
        updateAutoCaps()
        refresh()
    }

    /** Завершает набираемое слово: автоисправление, обучение, контекст. */
    private fun finishWord(autocorrect: Boolean, sep: String) {
        if (word.isEmpty()) return
        val typed = word.toString()
        var coreLen = typed.length
        while (coreLen > 0 && typed[coreLen - 1].let { it == '-' || it == '\'' || it == '’' }) coreLen--
        val core = typed.substring(0, coreLen)
        val tail = typed.substring(coreLen)
        val coreLocks = locked.subList(0, coreLen).toList()
        val typedKey = WordDictionary.normalize(core)
        var out = core
        var fixedKey: String? = null
        if (autocorrect && core.isNotEmpty() && canCorrect() && !isAllCaps(core, coreLocks)) {
            val fix = SuggestionEngine.correction(lang, typedKey, prox, wordStrict)
                ?: if (isAllLower(core)) SuggestionEngine.caseFix(lang, typedKey) else null
            if (fix != null && fix != core) {
                out = applyCase(fix, core, coreLocks)
                fixedKey = WordDictionary.normalize(fix)
            }
        }
        val typedRendered = render(typed, locked)
        val rendered = if (fixedKey == null) typedRendered else render(out + tail, null)
        edCommit(rendered)
        lastCorrection = if (fixedKey != null && fixedKey != typedKey || rendered != typedRendered)
            Correction(typedRendered, rendered, typedKey, fixedKey ?: typedKey, sep, lang) else null
        val key = fixedKey ?: typedKey
        learn(key, learnedSpelling(out, key, wordStrict))
        pushContext(key)
        resetWord()
    }

    /** Завершить слово как есть (смена раскладки, перемещение курсора, скрытие клавиатуры). */
    fun finishComposing() {
        if (word.isNotEmpty()) batch { finishWord(false, "") }
        if (composingActive) {
            ed.finishComposingText()
            composingActive = false
        }
        direct.clear()
        lastCorrection = null
    }

    // ================================================================ пробел / Enter

    fun onSpace() {
        val now = clock()
        batch {
            when {
                last == Last.SWAP || last == Last.PICK -> {
                    // после выбора подсказки или «, » пробел уже стоит
                    last = Last.SPACE
                    lastSpaceTime = now
                    refresh()
                    return
                }
                word.isNotEmpty() -> {
                    finishWord(true, " ")
                    edCommit(" ")
                }
                settings.doubleSpacePeriod && profile.kind == FieldProfile.Kind.TEXT &&
                    last == Last.SPACE && now - lastSpaceTime < DOUBLE_SPACE_MS &&
                    canDoubleSpacePeriod() -> {
                    edDelete(1)
                    edCommit(". ")
                    lastCorrection = null
                    context.clear()
                    last = Last.PERIOD
                    phantomSpace = false
                    direct.clear()
                    lastSpaceTime = 0
                    updateAutoCaps()
                    refresh()
                    return
                }
                else -> {
                    lastCorrection = null
                    edCommit(" ")
                }
            }
            direct.clear()
            phantomSpace = false
        }
        last = Last.SPACE
        lastSpaceTime = now
        updateAutoCaps()
        refresh()
    }

    private fun canDoubleSpacePeriod(): Boolean {
        val b = ed.textBeforeCursor(2)?.toString() ?: return false
        if (b.length < 2 || b[1] != ' ') return false
        val c = b[0]
        return c.isLetterOrDigit() || c == ')' || c == '"' || c == '»' || c == '\'' || c == '”'
    }

    fun onEnter() {
        batch {
            if (word.isNotEmpty()) finishWord(true, "\n")
            lastCorrection = null
            direct.clear()
            phantomSpace = false
            when {
                profile.enterAction != 0 -> ed.performEditorAction(profile.enterAction)
                profile.multiLine && profile.kind != FieldProfile.Kind.RAW -> edCommit("\n")
                else -> edKey(KEY_ENTER)
            }
        }
        context.clear()
        last = Last.OTHER
        updateAutoCaps()
        refresh()
    }

    // ================================================================ удаление

    fun onBackspace() {
        var reverted = false
        batch {
            when {
                profile.kind == FieldProfile.Kind.RAW && word.isEmpty() -> edKey(KEY_DEL)
                hasSelection() -> {
                    if (composingActive) {
                        ed.finishComposingText()
                        composingActive = false
                        resetWord()
                    }
                    edCommit("")
                }
                word.isNotEmpty() -> {
                    // удаляем последнюю букву; невидимые в транслите (ь, ъ) — вместе со следующей
                    val before = render(word, locked).length
                    do {
                        word.setLength(word.length - 1)
                        locked.removeAt(locked.size - 1)
                    } while (word.isNotEmpty() && render(word, locked).length >= before)
                    edCompose(render(word, locked))
                    if (word.isEmpty()) wordStrict = false
                }
                lastCorrection != null && revertCorrection() -> reverted = true
                direct.isNotEmpty() -> {
                    val out = direct.removeAt(direct.size - 1)
                    if (ed.textBeforeCursor(out.length)?.toString() == out) edDelete(out.length)
                    else {
                        direct.clear()
                        deleteGrapheme()
                    }
                }
                else -> deleteGrapheme()
            }
            lastCorrection = null
            if (!reverted) phantomSpace = false
        }
        last = Last.OTHER
        updateAutoCaps()
        refresh()
    }

    private fun revertCorrection(): Boolean {
        val c = lastCorrection ?: return false
        lastCorrection = null
        val expect = c.fixedRendered + c.sep
        if (ed.textBeforeCursor(expect.length)?.toString() != expect) return false
        edDelete(expect.length)
        if (c.sep == " ") {
            edCommit(c.typedRendered)
            phantomSpace = true
        } else {
            edCommit(c.typedRendered + c.sep)
        }
        if (canLearn() && c.lang == lang) {
            UserHistory.learn(lang, c.typedKey, null, 3)
            if (c.fixedKey != c.typedKey) UserHistory.learn(lang, c.fixedKey, null, -1)
        }
        if (context.isNotEmpty()) context[context.size - 1] = c.typedKey
        return true
    }

    private fun deleteGrapheme() {
        val before = ed.textBeforeCursor(64)?.toString()
        if (before.isNullOrEmpty()) {
            edKey(KEY_DEL)
            return
        }
        edDelete(before.length - graphemeStart(before))
    }

    /** Удаление слова целиком (долгое удержание ⌫). */
    fun deleteWord() {
        batch {
            when {
                word.isNotEmpty() -> {
                    resetWord()
                    edCompose("")
                }
                hasSelection() -> edCommit("")
                else -> {
                    val before = ed.textBeforeCursor(64)?.toString() ?: ""
                    if (before.isEmpty()) edKey(KEY_DEL)
                    else edDelete(max(1, before.length - wordBoundary(before)))
                }
            }
            direct.clear()
            lastCorrection = null
            phantomSpace = false
        }
        last = Last.OTHER
        updateAutoCaps()
        refresh()
    }

    // --- свайп влево по ⌫: выделяем слова, при отпускании удаляем
    fun backspaceSwipeStart(): Boolean {
        finishComposing()
        if (selS < 0 || selS != selE) return false
        swipeText = ed.textBeforeCursor(1000)?.toString() ?: ""
        swipeAnchor = selS
        return swipeText.isNotEmpty()
    }

    fun backspaceSwipe(steps: Int) {
        if (swipeAnchor < 0) return
        var end = swipeText.length
        repeat(steps) { if (end > 0) end = wordBoundary(swipeText, end) }
        val start = swipeAnchor - (swipeText.length - end)
        ed.setSelection(start, swipeAnchor)
        selS = start
        selE = swipeAnchor
    }

    fun backspaceSwipeEnd() {
        if (swipeAnchor < 0) return
        batch {
            if (selS >= 0 && selE > selS) edCommit("")
        }
        swipeAnchor = -1
        swipeText = ""
        last = Last.OTHER
        updateAutoCaps()
        refresh()
    }

    // ================================================================ курсор свайпом по пробелу

    fun moveCursor(steps: Int) {
        if (steps == 0) return
        finishComposing()
        phantomSpace = false
        last = Last.OTHER
        context.clear()
        val code = if (steps < 0) KEY_LEFT else KEY_RIGHT
        batch { repeat(abs(steps)) { edKey(code) } }
        refresh()
    }

    // ================================================================ подсказки

    fun pickChip(index: Int) {
        val chip = chips.getOrNull(index) ?: return
        batch {
            when (chip.kind) {
                ChipKind.TYPED, ChipKind.CORRECTION, ChipKind.WORD -> {
                    if (word.isEmpty()) return@batch
                    val typed = word.toString()
                    val asTyped = chip.kind == ChipKind.TYPED
                    val out = if (asTyped) typed else applyCase(chip.word, typed, locked)
                    val rendered = if (asTyped) render(typed, locked) else renderCased(chip.word, typed, locked)
                    edCommit("$rendered ")
                    val key = WordDictionary.normalize(out.trimEnd('-', '\'', '’'))
                    learn(key, learnedSpelling(out, key, wordStrict), if (asTyped) 2 else 1)
                    pushContext(key)
                    resetWord()
                }
                ChipKind.PREDICTION -> {
                    val before = ed.textBeforeCursor(1)?.toString() ?: ""
                    if (phantomSpace || (before.isNotEmpty() && !before[0].isWhitespace())) edCommit(" ")
                    val w = when (shift) {
                        Shift.LOCKED -> chip.word.uppercase()
                        Shift.ONCE, Shift.AUTO -> chip.word.replaceFirstChar { it.uppercaseChar() }
                        Shift.OFF -> chip.word
                    }
                    edCommit(render(w, if (shift == Shift.LOCKED) List(w.length) { true } else null) + " ")
                    for (part in chip.word.split(' ', ',').filter { it.isNotBlank() }) {
                        val key = WordDictionary.normalize(part)
                        learn(key, null)
                        pushContext(key)
                    }
                }
            }
            lastCorrection = null
            phantomSpace = false
            direct.clear()
        }
        last = Last.PICK
        if (shift == Shift.ONCE || shift == Shift.AUTO) shift = Shift.OFF
        updateAutoCaps()
        refresh()
    }

    private fun computeChips(): List<Chip> {
        if (!settings.suggestions || !profile.suggestions || lang.isEmpty()) return emptyList()
        if (word.isNotEmpty()) {
            val typed = word.toString()
            var coreLen = typed.length
            while (coreLen > 0 && typed[coreLen - 1].let { it == '-' || it == '\'' || it == '’' }) coreLen--
            if (coreLen == 0) return emptyList()
            val core = typed.substring(0, coreLen)
            val key = WordDictionary.normalize(core)
            val allCaps = isAllCaps(core, locked)
            val res = SuggestionEngine.suggest(
                lang, key, context.lastOrNull(), prox,
                allowCorrection = canCorrect() && !allCaps, strict = wordStrict
            )
            val corr = res.correction
                ?: if (canCorrect() && !allCaps && isAllLower(core)) SuggestionEngine.caseFix(lang, key) else null
            val out = ArrayList<Chip>(4)
            val typedLabel = render(typed, locked)
            out.add(Chip(if (corr != null) "«$typedLabel»" else typedLabel, ChipKind.TYPED, typed))
            if (corr != null) out.add(Chip(renderCased(corr, core, locked), ChipKind.CORRECTION, corr))
            for (w in res.words) {
                if (out.size >= 4) break
                val label = renderCased(w, core, locked)
                if (out.none { it.label == label }) out.add(Chip(label, ChipKind.WORD, w))
            }
            return out
        }
        if (direct.isNotEmpty() || context.isEmpty()) return emptyList()
        val preds = SuggestionEngine.predict(lang, context.lastOrNull(), context.getOrNull(context.size - 2))
        return preds.map { p ->
            val w = when (shift) {
                Shift.LOCKED -> p.uppercase()
                Shift.ONCE, Shift.AUTO -> p.replaceFirstChar { it.uppercaseChar() }
                Shift.OFF -> p
            }
            Chip(render(w, if (shift == Shift.LOCKED) List(w.length) { true } else null), ChipKind.PREDICTION, p)
        }
    }

    /** Пересчитать подсказки (например, когда догрузились словари). */
    fun refresh() {
        chips = try {
            computeChips()
        } catch (_: Exception) {
            emptyList()
        }
        listener?.invoke()
    }
}
