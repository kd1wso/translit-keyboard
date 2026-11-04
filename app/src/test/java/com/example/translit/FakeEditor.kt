package com.example.translit

import kotlin.math.max
import kotlin.math.min

/**
 * Поле ввода для тестов: ведёт себя как EditText + BaseInputConnection —
 * composing-регион, выделение, пакетные изменения и отложенные onUpdateSelection.
 */
class FakeEditor : EditorBridge {
    val text = StringBuilder()
    var selS = 0
    var selE = 0
    var compS = -1
    var compE = -1

    private var repS = 0
    private var repE = 0
    private var repCS = -1
    private var repCE = -1
    private var batch = 0

    /** Очередь onUpdateSelection: oldS, oldE, newS, newE, candS, candE. */
    val updates = ArrayDeque<IntArray>()
    val actions = ArrayList<Int>()
    val keys = ArrayList<Int>()

    private fun changed() {
        if (batch == 0) report()
    }

    private fun report() {
        if (selS != repS || selE != repE || compS != repCS || compE != repCE) {
            updates.addLast(intArrayOf(repS, repE, selS, selE, compS, compE))
            repS = selS; repE = selE; repCS = compS; repCE = compE
        }
    }

    override fun beginBatch() { batch++ }
    override fun endBatch() {
        if (batch > 0) batch--
        if (batch == 0) report()
    }

    private fun lo() = min(selS, selE)
    private fun hi() = max(selS, selE)

    override fun textBeforeCursor(n: Int): CharSequence = text.substring(max(0, lo() - n), lo())
    override fun textAfterCursor(n: Int): CharSequence = text.substring(hi(), min(text.length, hi() + n))

    override fun commitText(text: CharSequence) {
        val a = if (compS >= 0) compS else lo()
        val b = if (compS >= 0) compE else hi()
        this.text.replace(a, b, text.toString())
        selS = a + text.length; selE = selS
        compS = -1; compE = -1
        changed()
    }

    override fun setComposingText(text: CharSequence) {
        val a = if (compS >= 0) compS else lo()
        val b = if (compS >= 0) compE else hi()
        this.text.replace(a, b, text.toString())
        selS = a + text.length; selE = selS
        if (text.isEmpty()) { compS = -1; compE = -1 } else { compS = a; compE = a + text.length }
        changed()
    }

    override fun finishComposingText() {
        compS = -1; compE = -1
        changed()
    }

    override fun deleteBefore(n: Int) {
        val a = lo()
        val from = max(0, a - n)
        val d = a - from
        text.delete(from, a)
        selS -= d; selE -= d
        if (compS >= 0) {
            if (compS >= a) { compS -= d; compE -= d } else if (compE > from) { compS = -1; compE = -1 }
        }
        changed()
    }

    override fun setSelection(start: Int, end: Int) {
        selS = start; selE = end
        changed()
    }

    override fun sendKey(keyCode: Int) {
        keys.add(keyCode)
        when (keyCode) {
            InputEngine.KEY_DEL -> {
                compS = -1; compE = -1
                if (selS != selE) {
                    val a = lo(); text.delete(a, hi()); selS = a; selE = a
                } else if (selS > 0) {
                    var n = 1
                    if (selS >= 2 && Character.isSurrogatePair(text[selS - 2], text[selS - 1])) n = 2
                    text.delete(selS - n, selS); selS -= n; selE = selS
                }
            }
            InputEngine.KEY_ENTER -> {
                compS = -1; compE = -1
                text.replace(lo(), hi(), "\n"); selS = lo() + 1; selE = selS
            }
            InputEngine.KEY_LEFT -> { selS = max(0, lo() - 1); selE = selS }
            InputEngine.KEY_RIGHT -> { selS = min(text.length, hi() + 1); selE = selS }
        }
        changed()
    }

    override fun performEditorAction(action: Int) { actions.add(action) }

    override fun cursorCapsMode(reqModes: Int): Int = getCapsMode(text, lo(), reqModes)

    // --- действия пользователя (не клавиатуры)
    fun userMoveCursor(pos: Int) { selS = pos; selE = pos; changed() }
    fun userSelect(a: Int, b: Int) { selS = a; selE = b; changed() }

    companion object {
        /** Порт android.text.TextUtils.getCapsMode. */
        fun getCapsMode(cs: CharSequence, off: Int, reqModes: Int): Int {
            if (off < 0) return 0
            var mode = 0
            if (reqModes and FieldProfile.CAP_CHARACTERS != 0) mode = mode or FieldProfile.CAP_CHARACTERS
            if (reqModes and (FieldProfile.CAP_WORDS or FieldProfile.CAP_SENTENCES) == 0) return mode
            var i = off
            while (i > 0) {
                val c = cs[i - 1]
                if (c != '"' && c != '\'' && Character.getType(c) != Character.START_PUNCTUATION.toInt()) break
                i--
            }
            var j = i
            while (j > 0 && (cs[j - 1] == ' ' || cs[j - 1] == '\t')) j--
            if (j == 0 || cs[j - 1] == '\n') return mode or FieldProfile.CAP_WORDS or FieldProfile.CAP_SENTENCES
            if (reqModes and FieldProfile.CAP_SENTENCES == 0) {
                if (i != j) mode = mode or FieldProfile.CAP_WORDS
                return mode
            }
            if (i == j) return mode
            while (j > 0) {
                val c = cs[j - 1]
                if (c != '"' && c != '\'' && Character.getType(c) != Character.END_PUNCTUATION.toInt()) break
                j--
            }
            if (j > 0) {
                val c = cs[j - 1]
                if (c == '.' || c == '?' || c == '!') {
                    if (c == '.') {
                        var k = j - 2
                        while (k >= 0) {
                            val ch = cs[k]
                            if (ch == '.') return mode
                            if (!Character.isLetter(ch)) break
                            k--
                        }
                    }
                    return mode or FieldProfile.CAP_SENTENCES
                }
            }
            return mode
        }
    }
}
