package com.example.translit

import com.example.translit.KeyboardLayoutManager.KeyDef
import com.example.translit.KeyboardLayoutManager.LayoutDef
import com.example.translit.KeyboardLayoutManager.Page
import com.example.translit.KeyboardView.Key
import com.example.translit.KeyboardView.Row
import com.example.translit.KeyboardView.Type

/** Собирает ряды клавиш для KeyboardView из описаний раскладок. */
object KeyboardModels {

    class BottomSpec(
        val page: Page,
        val lettersLabel: String,
        val fieldKind: FieldProfile.Kind,
        val enterIcon: Int
    )

    private fun charKey(k: KeyDef, hint: Boolean): Key =
        Key(Type.CHAR, k.label, k.label, 1f, k.longPress, hint = if (hint) hintOf(k) else null)

    /** Подсказка в углу клавиши: цифра, если она есть среди вариантов (как у верхнего ряда), иначе первый вариант. */
    private fun hintOf(k: KeyDef): String? =
        k.longPress.firstOrNull { it.length == 1 && it[0].isDigit() } ?: k.longPress.firstOrNull()

    private fun shift() = Key(Type.SHIFT, width = 0f, func = true)
    private fun backspace(w: Float = 0f) = Key(Type.BACKSPACE, width = w, icon = R.drawable.ic_backspace, func = true)
    private fun mode(label: String, target: Page, w: Float) =
        Key(Type.MODE, label, width = w, func = true, target = target.ordinal)

    fun bottomRow(spec: BottomSpec): Row {
        val modeKey = if (spec.page == Page.LETTERS) mode("?123", Page.SYMBOLS1, 1.5f)
        else mode(spec.lettersLabel, Page.LETTERS, 1.5f)
        val (leftPunct, leftVariants) = when (spec.fieldKind) {
            FieldProfile.Kind.EMAIL -> "@" to listOf("_", "-", "+")
            FieldProfile.Kind.URI -> "/" to listOf(":", "-", "_", "?", "=", "&", "#")
            else -> "," to listOf("\"", "'", ";", ":", "-", "(", ")")
        }
        val periodVariants = when (spec.fieldKind) {
            FieldProfile.Kind.EMAIL, FieldProfile.Kind.URI -> listOf(".com", ".ru", ".net", ".org", ".io", "-", "_")
            else -> listOf("?", "!", "…", "@", "#", "&", "%", "/")
        }
        return Row(
            listOf(
                modeKey,
                Key(Type.EMOJI, width = 1f, icon = R.drawable.ic_smile, func = true),
                Key(Type.GLOBE, width = 1f, icon = R.drawable.ic_globe, func = true),
                Key(Type.CHAR, leftPunct, leftPunct, 1f, leftVariants, func = true),
                Key(Type.SPACE, width = 4f),
                Key(Type.CHAR, ".", ".", 1f, periodVariants, func = true),
                Key(Type.ENTER, width = 1.5f, icon = spec.enterIcon, func = true)
            ),
            fill = true
        )
    }

    /** Буквы: в последний ряд добавляются ⇧ и ⌫ (и для пользовательских раскладок тоже). */
    fun letters(def: LayoutDef, bottom: Row): List<Row> {
        val out = ArrayList<Row>()
        for ((i, r) in def.rows.withIndex()) {
            val keys = r.map { charKey(it, hint = true) }
            out.add(if (i == def.rows.size - 1) Row(listOf(shift()) + keys + listOf(backspace())) else Row(keys))
        }
        out.add(bottom)
        return out
    }

    fun symbols(page: Page, bottom: Row): List<Row> {
        val def = if (page == Page.SYMBOLS2) KeyboardLayoutManager.SYMBOLS2 else KeyboardLayoutManager.SYMBOLS1
        val toggle = if (page == Page.SYMBOLS2) mode("?123", Page.SYMBOLS1, 1.5f) else mode("=\\<", Page.SYMBOLS2, 1.5f)
        val out = ArrayList<Row>()
        for ((i, r) in def.rows.withIndex()) {
            val keys = r.map { charKey(it, hint = false) }
            out.add(if (i == def.rows.size - 1) Row(listOf(toggle) + keys + listOf(backspace(1.5f))) else Row(keys))
        }
        out.add(bottom)
        return out
    }

    fun emoji(bottom: Row): List<Row> {
        val rows = KeyboardLayoutManager.EMOJI.rows
        val out = ArrayList<Row>()
        for ((i, r) in rows.withIndex()) {
            val keys = r.map { Key(Type.CHAR, it.label, it.label, 1f) }
            out.add(if (i == rows.size - 1) Row(keys + listOf(backspace(2f))) else Row(keys))
        }
        out.add(bottom)
        return out
    }

    /** Цифровая / телефонная клавиатура 4×4. */
    fun numpad(page: Page, enterIcon: Int): List<Row> {
        val def = if (page == Page.PHONE) KeyboardLayoutManager.PHONE else KeyboardLayoutManager.NUMBER
        val r = def.rows
        fun digit(k: KeyDef) = Key(Type.CHAR, k.label, k.label, 1f, k.longPress, textScale = 1.1f,
            hint = k.longPress.firstOrNull()?.takeIf { page == Page.PHONE && k.label == "0" })
        fun side(k: KeyDef) = Key(Type.CHAR, k.label, k.label, 1f, k.longPress, func = true)
        return listOf(
            Row(r[0].take(3).map { digit(it) } + side(r[0][3]), fill = true),
            Row(r[1].map { digit(it) } + Key(Type.SPACE, "␣", width = 1f, func = true), fill = true),
            Row(r[2].map { digit(it) } + backspace(1f), fill = true),
            Row(listOf(side(r[3][0]), digit(r[3][1]), side(r[3][2]),
                Key(Type.ENTER, width = 1f, icon = enterIcon, func = true)), fill = true)
        )
    }
}
