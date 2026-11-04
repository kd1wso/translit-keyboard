package com.example.translit

import com.example.translit.TextActions.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Перевод между раскладками и действия с выделенным текстом. */
class LayoutFixTest {

    @Test
    fun qwertyToRussian() {
        assertEquals("привет", LayoutFix.toRu("ghbdtn"))
        assertEquals("Привет", LayoutFix.toRu("Ghbdtn"))
        assertEquals("ёжик", LayoutFix.toRu("`;br"))
        assertEquals("съешь", LayoutFix.toRu("c]tim"))
    }

    @Test
    fun russianToQwerty() {
        assertEquals("hello", LayoutFix.toEn("руддщ"))
        assertEquals("Hello", LayoutFix.toEn("Руддщ"))
        assertEquals("hello", LayoutFix.toEn("челло", yaverty = true))
        assertEquals("qwerty", LayoutFix.toEn("явертыю".dropLast(1), yaverty = true))
    }

    @Test
    fun swapPicksDirectionAndPunctuation() {
        assertEquals("привет, как дела?", LayoutFix.swap("ghbdtn? rfr ltkf&"))
        assertEquals("hello, world!", LayoutFix.swap("руддщб цщкдв!"))
    }

    @Test
    fun slug() {
        assertEquals("privet-mir", TextActions.slug("Привет, мир!"))
        assertEquals("schuka-2024", TextActions.slug("  Щука 2024 "))
        assertEquals("dont-stop", TextActions.slug("Don't stop"))
    }

    @Test
    fun actionsDependOnText() {
        assertEquals(listOf(Action.LATIN, Action.LAYOUT, Action.SLUG), TextActions.available("Привет, мир!"))
        assertEquals(listOf(Action.CYRILLIC, Action.LAYOUT, Action.SLUG), TextActions.available("Privet, mir!"))
        assertEquals(listOf(Action.CYRILLIC, Action.LAYOUT), TextActions.available("privet"))
        assertEquals(emptyList<Action>(), TextActions.available("  "))
        assertEquals(emptyList<Action>(), TextActions.available("123"))
    }

    @Test
    fun applyActions() {
        assertEquals("Privet, mir!", TextActions.apply(Action.LATIN, "Привет, мир!", TranslitMode.COLLOQUIAL))
        assertEquals("Shchuka", TextActions.apply(Action.LATIN, "Щука", TranslitMode.PASSPORT))
        assertEquals("Привет, мир!", TextActions.apply(Action.CYRILLIC, "Privet, mir!", TranslitMode.COLLOQUIAL))
        assertEquals("привет", TextActions.apply(Action.LAYOUT, "ghbdtn", TranslitMode.COLLOQUIAL))
    }
}
