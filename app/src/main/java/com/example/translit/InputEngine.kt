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
    /** Выделенный текст или null, если приложение его не отдаёт. */
    fun selectedText(): CharSequence? = null
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
    val doubleSpacePeriod: Boolean = true,
    /** Исправлять слово, набранное не в той раскладке («ghbdtn» → «привет»), и переключать раскладку. */
    val layoutFix: Boolean = true
)

/**
 * Логика ввода без Android-зависимостей: набор слова, транслит, подсказки, автоисправление
 * с отменой, регистр, пробелы и пунктуация, удаление, отслеживание курсора.
 */
class InputEngine(private val ed: EditorBridge) {

    enum class Shift { OFF, ONCE, AUTO, LOCKED }
    /** [RAW] — в обратном транслите: оставить латиницу как набрана. */
    enum class ChipKind { TYPED, CORRECTION, WORD, PREDICTION, LAYOUT, ACTION, RAW }

    /** Подсказка; [auto] — её вставит пробел (выделяется цветом). */
    data class Chip(val label: String, val kind: ChipKind, val word: String, val auto: Boolean = false)

    companion object {
        const val KEY_DEL = 67
        const val KEY_ENTER = 66
        const val KEY_LEFT = 21
        const val KEY_RIGHT = 22

        /** [onSwitchLanguage]: вернуть раскладку, которая была до исправления раскладки. */
        const val SWITCH_BACK = "back"

        private const val OPENERS = "([{«\"'„“‘<"
        private const val CORRECTING_SEPARATORS = ".,!?;:)»\"…"
        private const val SWAP_PUNCT = ".,!?"
        private const val SENTENCE_END = ".!?…"
        private const val MAX_SELECTION = 5000
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
    /** Обратный транслит: клавиши латиницей, в поле — кириллица («privet» → «привет»). */
    var reverse = false
        private set
    private var prox: Map<Char, Set<Char>> = emptyMap()
    var profile = FieldProfile.TEXT
        private set

    /** Исправление раскладки: 0 — нет, 1 — ЙЦУКЕН ↔ QWERTY, 2 — только ЯВЕРТЫ → QWERTY. */
    var layoutFixMode = 0
    /** Как выводить русское слово после исправления раскладки: null — кириллицей, иначе транслитом. */
    var fixRuRender: TranslitMode? = null
    /** Система транслитерации для действий с выделенным текстом. */
    var actionTranslit: TranslitMode = TranslitMode.COLLOQUIAL
    /** Переключить раскладку на язык ("ru"/"en") после исправления раскладки; [SWITCH_BACK] — отмена. */
    var onSwitchLanguage: ((String) -> Unit)? = null

    var shift = Shift.OFF
        private set
    var chips: List<Chip> = emptyList()
        private set

    // --- набираемое слово (composing)
    private val word = StringBuilder()
    private val locked = ArrayList<Boolean>()
    private var wordStrict = false
    private var composingActive = false

    // --- набор без composing: буквы слова и то, что уже вставлено, чтобы ⌫ удалял «щ» → «sch»
    // целиком, а буквы, зависящие от соседних (ГОСТ, BGN, обратный транслит), исправлялись
    private val directSrc = StringBuilder()
    private val directLocks = ArrayList<Boolean>()
    private var directOut = ""
    private var directBefore: Char? = null

    // --- контекст: последние слова (нормализованные), последнее — в конце
    private val context = ArrayList<String>(3)

    private class Correction(
        val typedRendered: String,
        val fixedRendered: String,
        val typedKey: String,
        val fixedKey: String,
        var sep: String,
        val lang: String,
        /** Исправлена раскладка: отмена переключает раскладку обратно. */
        val layout: Boolean = false
    )

    /** Исправление набранного слова: [word] — слово словаря, [cased] — с регистром набранного. */
    private class Fix(
        val word: String,
        val cased: String,
        val key: String,
        val rendered: String,
        val lang: String,
        val layout: Boolean
    )

    private class LayoutCand(val fix: Fix, val confident: Boolean)

    private var lastCorrection: Correction? = null
    private var phantomSpace = false
    private var pendingSwitch: String? = null
    private var pendingKey: String? = null
    private var chipLayoutFix: Fix? = null

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

    fun configure(lang: String, translit: TranslitMode?, prox: Map<Char, Set<Char>>, reverse: Boolean = false) {
        // отмена последнего исправления переживает смену раскладки (исправление раскладки само её меняет)
        val undo = if (word.isEmpty()) lastCorrection else null
        val phantom = word.isEmpty() && phantomSpace
        finishComposing()
        this.lang = lang
        this.translit = translit
        this.reverse = reverse
        this.prox = prox
        context.clear()
        lastCorrection = undo
        phantomSpace = phantom
        clearDirect()
        refresh()
    }

    fun startInput(p: FieldProfile, selStart: Int, selEnd: Int) {
        if (composingActive) ed.finishComposingText()
        composingActive = false
        resetWord()
        clearDirect()
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
        clearDirect()
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

    private fun clearDirect() {
        directSrc.setLength(0)
        directLocks.clear()
        directOut = ""
        directBefore = null
    }

    /** Набранные буквы → текст в поле. [before] — символ перед словом (для правил транслита). */
    private fun render(src: CharSequence, locks: List<Boolean>?, before: Char? = null): String {
        if (reverse) return applyCase(ReverseTranslit.live(src, lang.ifEmpty { "ru" }), src, locks)
        val t = translit ?: return src.toString()
        return t.translitWord(src, locks, before)
    }

    /** Слово словаря (уже с регистром) → текст в поле. */
    private fun renderWord(w: String, locks: List<Boolean>?): String = if (reverse) w else render(w, locks)

    /** Ключ словаря для набранного: в обратном транслите — то, что получилось кириллицей. */
    private fun dictKey(core: CharSequence, locks: List<Boolean>?): String =
        WordDictionary.normalize(if (reverse) render(core, locks) else core)

    /** Дефис и апостроф в конце слова — не часть слова (в обратном транслите апостроф — «ь»). */
    private fun isTailChar(c: Char): Boolean = c == '-' || (!reverse && (c == '\'' || c == '’'))

    private fun coreLength(typed: CharSequence): Int {
        var n = typed.length
        while (n > 0 && isTailChar(typed[n - 1])) n--
        return n
    }

    /** Соседние клавиши имеют смысл, только если буквы слова — это буквы клавиш. */
    private val corrProx: Map<Char, Set<Char>> get() = if (reverse) emptyMap() else prox

    /** ⌫ в слове: удалили достаточно букв, чтобы в поле что-то исчезло. */
    private fun backspaceDone(before: String, after: String): Boolean =
        if (reverse) after.length < before.length else after != before

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
        if (reverse) return cased
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

    private fun learn(key: String, spelling: String?, inc: Int = 1, inLang: String = lang, pair: Boolean = true) {
        if (!canLearn() || key.length < 2 || key.length > 40) return
        if (key.any { !(it.isLetter() || it == '\'' || it == '-') }) return
        UserHistory.learn(inLang, key, spelling, inc)
        if (pair) context.lastOrNull()?.let { UserHistory.learnPair(inLang, it, key) }
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

    // ================================================================ исправления

    /** Регистр из словаря для слова, набранного строчными: «москва» → «Москва», «i» → «I». */
    private fun dictCase(w: String, inLang: String, key: String): String {
        if (!isAllLower(w)) return w
        val sp = SuggestionEngine.caseFix(inLang, key) ?: return w
        if (sp.length != w.length) return sp
        // ё из набранного сохраняем
        val sb = StringBuilder(w.length)
        for (i in w.indices) sb.append(if (sp[i].isUpperCase()) w[i].uppercaseChar() else w[i])
        return sb.toString()
    }

    /**
     * Исправление опечатки; в обратном транслите — ещё и выбор известного слова среди прочтений
     * («eto» → «это», «otsyuda» → «отсюда», «den» → «день»). null — оставить как есть.
     */
    private fun typoFix(core: String, coreLocks: List<Boolean>, typedKey: String): Fix? {
        if (core.isEmpty() || !canCorrect() || isAllCaps(core, coreLocks)) return null
        if (reverse) {
            val shown = ReverseTranslit.live(core, lang)
            // слово, которое пользователь уже оставлял как есть, не трогаем
            if (UserHistory.count(lang, typedKey) >= UserHistory.VALID_COUNT) return null
            val best = ReverseTranslit.knownWords(core, shown, lang, 1).firstOrNull()
            val w: String = when {
                best != null -> best
                SuggestionEngine.isKnown(lang, typedKey) -> return null
                else -> SuggestionEngine.correction(lang, typedKey, emptyMap(), wordStrict) ?: return null
            }
            val key = WordDictionary.normalize(w)
            val spelled = dictCase(w, lang, key)
            val cased = applyCase(spelled, core, coreLocks)
            if (cased == render(core, coreLocks)) return null
            return Fix(spelled, cased, key, cased, lang, false)
        }
        val fix = SuggestionEngine.correction(lang, typedKey, prox, wordStrict)
            ?: if (isAllLower(core)) SuggestionEngine.caseFix(lang, typedKey) else null
        if (fix == null || fix == core) return null
        val cased = applyCase(fix, core, coreLocks)
        return Fix(fix, cased, WordDictionary.normalize(fix), render(cased, null), lang, false)
    }

    /**
     * Слово, набранное не в той раскладке: латиница вместо ЙЦУКЕН («ghbdtn» → «привет»)
     * или кириллица вместо QWERTY («руддщ» → «hello»). [confident] — можно заменить пробелом.
     */
    private fun layoutCandidate(core: String, typedKey: String, typo: Fix?): LayoutCand? {
        if (layoutFixMode == 0 || core.length < 2) return null
        if (!SuggestionEngine.isLoaded("ru") || !SuggestionEngine.isLoaded("en")) return null
        val latinSource = reverse || (lang == "en" && translit == null)
        if (latinSource) {
            if (layoutFixMode != 1) return null
            if (core.any { !(it in 'a'..'z' || it in 'A'..'Z' || it == '\'') }) return null
            if (reverse) {
                if (ReverseTranslit.knownWords(core, null, "ru", 1).isNotEmpty()) return null
            } else if (SuggestionEngine.isKnown("en", typedKey)) return null
            val ru = LayoutFix.toRu(core)
            if (!LayoutFix.isWord(ru) || ru.any { it in 'a'..'z' || it in 'A'..'Z' }) return null
            val key = WordDictionary.normalize(ru)
            val cnt = SuggestionEngine.wordCount("ru", key)
            if (cnt <= 0) return null
            val confident = core.length >= 3 && (cnt >= 1000 || (core.length >= 5 && cnt >= 100)) &&
                (typo == null || cnt >= 20000)
            val cased = dictCase(ru, "ru", key)
            val rendered = fixRuRender?.translitWord(cased) ?: cased
            return LayoutCand(Fix(ru.lowercase(), cased, key, rendered, "ru", true), confident)
        }
        if (lang != "ru") return null
        if (SuggestionEngine.isKnown("ru", typedKey)) return null
        val en = LayoutFix.toEn(core, yaverty = layoutFixMode == 2)
        if (!LayoutFix.isWord(en) || en.any { !(it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == '-') }) return null
        val key = WordDictionary.normalize(en)
        val cnt = SuggestionEngine.wordCount("en", key)
        if (cnt <= 0) return null
        val confident = core.length >= 4 && cnt >= 2000 && typo == null
        val cased = dictCase(en, "en", key)
        return LayoutCand(Fix(en.lowercase(), cased, key, cased, "en", true), confident)
    }

    private fun autoLayout(c: LayoutCand?): Boolean =
        c != null && c.confident && settings.layoutFix && profile.autocorrect

    /** Что сделает пробел с набранным словом. */
    private fun decideFix(core: String, coreLocks: List<Boolean>, typedKey: String): Fix? {
        val typo = typoFix(core, coreLocks, typedKey)
        val lay = layoutCandidate(core, typedKey, typo)
        return if (autoLayout(lay)) lay!!.fix else typo
    }

    // ================================================================ регистр

    private fun updateAutoCaps() {
        if (shift == Shift.LOCKED || shift == Shift.ONCE) return
        if (word.isNotEmpty() || directSrc.isNotEmpty()) {
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
            if (word.isEmpty() && directSrc.isEmpty()) {
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
                    directLetter(c, lock)
                }
            } else if (word.isNotEmpty()) {
                word.append(c)
                locked.add(lock)
                edCompose(render(word, locked))
            } else {
                directLetter(c, lock)
            }
        }
        phantomSpace = false
        lastCorrection = null
        last = Last.LETTER
        lastShiftTap = 0
        if (shift == Shift.ONCE || shift == Shift.AUTO) shift = Shift.OFF
        refresh()
    }

    private fun directLetter(c: Char, lock: Boolean) {
        // текст перед курсором поменялся не нами — начинаем новое слово
        if (directSrc.isNotEmpty() && ed.textBeforeCursor(directOut.length)?.toString() != directOut) clearDirect()
        if (directSrc.length >= 64) clearDirect()
        if (directSrc.isEmpty()) directBefore = ed.textBeforeCursor(1)?.lastOrNull()?.takeIf { it.isLetter() }
        directSrc.append(c)
        directLocks.add(lock)
        replaceDirect(render(directSrc, directLocks, directBefore))
    }

    /** Заменяет вставленное слово новым, трогая только изменившийся конец. */
    private fun replaceDirect(out: String) {
        val old = directOut
        var p = 0
        while (p < old.length && p < out.length && old[p] == out[p]) p++
        if (p < old.length) edDelete(old.length - p)
        if (p < out.length) edCommit(out.substring(p))
        directOut = out
    }

    /** Любой текст с клавиши: буква, символ, цифра, эмодзи, «.com». */
    fun onText(text: String) {
        if (text.isEmpty()) return
        val ch = if (text.length == 1) text[0] else null
        if (ch != null && ch.isLetter()) {
            onLetter(ch)
            return
        }
        if (ch != null && reverse && (ch == '\'' || ch == '’') && (word.isNotEmpty() || directSrc.isNotEmpty())) {
            // в обратном транслите апостроф — мягкий/твёрдый знак: den' → день, ob''ekt → объект
            onLetter('\'')
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
            clearDirect()
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
        if (ch != null && (SENTENCE_END.indexOf(ch) >= 0 || ch == '\n')) {
            context.clear()
            pendingKey = null
        }
        updateAutoCaps()
        refresh()
        flushPendingSwitch()
    }

    /** Завершает набираемое слово: автоисправление, обучение, контекст. */
    private fun finishWord(autocorrect: Boolean, sep: String) {
        if (word.isEmpty()) return
        val typed = word.toString()
        val coreLen = coreLength(typed)
        val core = typed.substring(0, coreLen)
        val tail = typed.substring(coreLen)
        val coreLocks = locked.subList(0, coreLen).toList()
        val typedKey = dictKey(core, coreLocks)
        val typedRendered = render(typed, locked)
        val fix = if (autocorrect && core.isNotEmpty()) decideFix(core, coreLocks, typedKey) else null
        val rendered = if (fix == null) typedRendered else fix.rendered + tail
        edCommit(rendered)
        lastCorrection = if (fix != null && (fix.key != typedKey || rendered != typedRendered))
            Correction(typedRendered, rendered, typedKey, fix.key, sep, lang, fix.layout) else null
        if (fix != null && fix.layout) {
            // слово из другого языка: учим там и переключаем раскладку
            learn(fix.key, null, 1, fix.lang, pair = false)
            pendingSwitch = fix.lang
            pendingKey = fix.key
        } else {
            val key = fix?.key ?: typedKey
            val out = fix?.cased ?: if (reverse) render(core, coreLocks) else core
            learn(key, learnedSpelling(out, key, wordStrict))
            pushContext(key)
        }
        resetWord()
    }

    /** После исправления раскладки: попросить сервис переключить её (это вызовет [configure]). */
    private fun flushPendingSwitch() {
        val target = pendingSwitch ?: return
        pendingSwitch = null
        val key = pendingKey
        pendingKey = null
        val cb = onSwitchLanguage ?: return
        cb(target)
        if (key != null) {
            context.clear()
            pushContext(key)
        }
        updateAutoCaps()
        refresh()
    }

    /** Завершить слово как есть (смена раскладки, перемещение курсора, скрытие клавиатуры). */
    fun finishComposing() {
        if (word.isNotEmpty()) batch { finishWord(false, "") }
        if (composingActive) {
            ed.finishComposingText()
            composingActive = false
        }
        clearDirect()
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
                    clearDirect()
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
            clearDirect()
            phantomSpace = false
        }
        last = Last.SPACE
        lastSpaceTime = now
        updateAutoCaps()
        refresh()
        flushPendingSwitch()
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
            clearDirect()
            phantomSpace = false
            when {
                profile.enterAction != 0 -> ed.performEditorAction(profile.enterAction)
                profile.multiLine && profile.kind != FieldProfile.Kind.RAW -> edCommit("\n")
                else -> edKey(KEY_ENTER)
            }
        }
        context.clear()
        pendingKey = null
        last = Last.OTHER
        updateAutoCaps()
        refresh()
        flushPendingSwitch()
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
                    // удаляем последнюю букву; невидимые в транслите (ь, ъ) — вместе со следующей,
                    // в обратном транслите «ш» из «sh» — целиком
                    val before = render(word, locked)
                    var after: String
                    do {
                        word.setLength(word.length - 1)
                        locked.removeAt(locked.size - 1)
                        after = if (word.isEmpty()) "" else render(word, locked)
                    } while (word.isNotEmpty() && !backspaceDone(before, after))
                    edCompose(after)
                    if (word.isEmpty()) wordStrict = false
                }
                lastCorrection != null && revertCorrection() -> reverted = true
                directSrc.isNotEmpty() -> {
                    if (ed.textBeforeCursor(directOut.length)?.toString() != directOut) {
                        clearDirect()
                        deleteGrapheme()
                    } else {
                        val before = directOut
                        var after: String
                        do {
                            directSrc.setLength(directSrc.length - 1)
                            directLocks.removeAt(directLocks.size - 1)
                            after = if (directSrc.isEmpty()) "" else render(directSrc, directLocks, directBefore)
                        } while (directSrc.isNotEmpty() && !backspaceDone(before, after))
                        if (after == before) {
                            // стёрлись только невидимые буквы — стираем видимый символ
                            clearDirect()
                            deleteGrapheme()
                        } else {
                            replaceDirect(after)
                            if (directSrc.isEmpty()) clearDirect()
                        }
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
        flushPendingSwitch()
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
        if (canLearn()) {
            // набранное оставили — больше не исправлять
            UserHistory.learn(c.lang, c.typedKey, null, 3)
            if (!c.layout && c.fixedKey != c.typedKey) UserHistory.learn(c.lang, c.fixedKey, null, -1)
        }
        if (c.layout) {
            // раскладку переключили зря — возвращаем
            pendingSwitch = SWITCH_BACK
            pendingKey = c.typedKey
        } else if (context.isNotEmpty()) context[context.size - 1] = c.typedKey
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
            clearDirect()
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
        if (chip.kind == ChipKind.ACTION) {
            applyAction(chip)
            return
        }
        batch {
            when (chip.kind) {
                ChipKind.TYPED, ChipKind.CORRECTION, ChipKind.WORD -> {
                    if (word.isEmpty()) return@batch
                    val typed = word.toString()
                    val asTyped = chip.kind == ChipKind.TYPED
                    val rendered = if (asTyped) render(typed, locked) else renderCased(chip.word, typed, locked)
                    edCommit("$rendered ")
                    val key: String
                    val out: String
                    if (asTyped) {
                        val n = coreLength(typed)
                        val core = typed.substring(0, n)
                        val locks = locked.subList(0, n).toList()
                        key = dictKey(core, locks)
                        out = if (reverse) render(core, locks) else core
                    } else {
                        out = applyCase(chip.word, typed, locked)
                        key = WordDictionary.normalize(chip.word.trimEnd('-', '\'', '’'))
                    }
                    learn(key, learnedSpelling(out, key, wordStrict), if (asTyped) 2 else 1)
                    pushContext(key)
                    resetWord()
                }
                ChipKind.RAW -> {
                    if (word.isEmpty()) return@batch
                    edCommit(word.toString() + " ")
                    context.clear()
                    resetWord()
                }
                ChipKind.LAYOUT -> {
                    val f = chipLayoutFix
                    if (word.isEmpty() || f == null) return@batch
                    val tail = word.substring(coreLength(word))
                    edCommit(f.rendered + tail + " ")
                    learn(f.key, null, 2, f.lang, pair = false)
                    pendingSwitch = f.lang
                    pendingKey = f.key
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
                    edCommit(renderWord(w, if (shift == Shift.LOCKED) List(w.length) { true } else null) + " ")
                    for (part in chip.word.split(' ', ',').filter { it.isNotBlank() }) {
                        val key = WordDictionary.normalize(part)
                        learn(key, null)
                        pushContext(key)
                    }
                }
                ChipKind.ACTION -> Unit
            }
            lastCorrection = null
            phantomSpace = false
            clearDirect()
        }
        last = Last.PICK
        if (shift == Shift.ONCE || shift == Shift.AUTO) shift = Shift.OFF
        updateAutoCaps()
        refresh()
        flushPendingSwitch()
    }

    /** Действие с выделенным текстом: заменить и оставить результат выделенным. */
    private fun applyAction(chip: Chip) {
        val action = try {
            TextActions.Action.valueOf(chip.word)
        } catch (_: Exception) {
            return
        }
        val sel = ed.selectedText()?.toString() ?: return
        if (sel.isEmpty() || !hasSelection()) return
        val out = TextActions.apply(action, sel, actionTranslit)
        val start = minOf(selS, selE)
        batch {
            edCommit(out)
            ed.setSelection(start, start + out.length)
            selS = start
            selE = start + out.length
        }
        lastCorrection = null
        phantomSpace = false
        clearDirect()
        context.clear()
        last = Last.OTHER
        refresh()
    }

    private fun selectionChips(): List<Chip> {
        when (profile.kind) {
            FieldProfile.Kind.TEXT, FieldProfile.Kind.URI, FieldProfile.Kind.EMAIL -> Unit
            else -> return emptyList()
        }
        val sel = ed.selectedText()?.toString() ?: return emptyList()
        if (sel.isEmpty() || sel.length > MAX_SELECTION) return emptyList()
        return TextActions.available(sel).map { Chip(it.label, ChipKind.ACTION, it.name) }
    }

    private fun computeChips(): List<Chip> {
        chipLayoutFix = null
        if (word.isEmpty() && directSrc.isEmpty() && hasSelection()) return selectionChips()
        if (!settings.suggestions || !profile.suggestions || lang.isEmpty()) return emptyList()
        if (word.isNotEmpty()) {
            val typed = word.toString()
            val coreLen = coreLength(typed)
            if (coreLen == 0) return emptyList()
            val core = typed.substring(0, coreLen)
            val coreLocks = locked.subList(0, coreLen).toList()
            val key = dictKey(core, coreLocks)
            val res = SuggestionEngine.suggest(
                lang, key, context.lastOrNull(), corrProx, allowCorrection = false, strict = wordStrict
            )
            val typo = typoFix(core, coreLocks, key)
            val lay = layoutCandidate(core, key, typo)
            val auto = if (autoLayout(lay)) lay!!.fix else typo
            val out = ArrayList<Chip>(4)
            val typedLabel = render(typed, locked)
            fun add(c: Chip) {
                if (out.size < 4 && out.none { it.label == c.label }) out.add(c)
            }
            add(Chip(if (auto != null) "«$typedLabel»" else typedLabel, ChipKind.TYPED, typed))
            if (auto != null) {
                if (auto.layout) {
                    chipLayoutFix = auto
                    add(Chip(auto.rendered, ChipKind.LAYOUT, auto.rendered, auto = true))
                } else {
                    add(Chip(auto.rendered, ChipKind.CORRECTION, auto.word, auto = true))
                }
            }
            if (lay != null && auto !== lay.fix) {
                chipLayoutFix = lay.fix
                add(Chip(lay.fix.rendered, ChipKind.LAYOUT, lay.fix.rendered))
            }
            if (reverse) {
                // другие известные прочтения: «moi» → «мои» / «мой»
                for (w in ReverseTranslit.knownWords(core, ReverseTranslit.live(core, lang), lang, 3)) {
                    add(Chip(renderCased(dictCase(w, lang, WordDictionary.normalize(w)), core, locked), ChipKind.WORD, w))
                }
            }
            for (w in res.words) add(Chip(renderCased(w, core, locked), ChipKind.WORD, w))
            if (reverse && core.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                // латиница как есть (iPhone, ok) — последней подсказкой
                val raw = Chip(typed, ChipKind.RAW, typed)
                if (out.size < 4) out.add(raw) else out[3] = raw
            }
            return out
        }
        if (directSrc.isNotEmpty() || context.isEmpty()) return emptyList()
        val preds = SuggestionEngine.predict(lang, context.lastOrNull(), context.getOrNull(context.size - 2))
        return preds.map { p ->
            val w = when (shift) {
                Shift.LOCKED -> p.uppercase()
                Shift.ONCE, Shift.AUTO -> p.replaceFirstChar { it.uppercaseChar() }
                Shift.OFF -> p
            }
            Chip(renderWord(w, if (shift == Shift.LOCKED) List(w.length) { true } else null), ChipKind.PREDICTION, p)
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
