package com.example.translit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EngineTest {

    @Before
    fun reset() {
        UserHistory.resetInMemory()
        SuggestionEngine.setExtraWords("ru", emptyList())
        SuggestionEngine.setExtraWords("en", emptyList())
    }

    private fun ru(translit: TranslitMode? = null, profile: FieldProfile = Harness.NO_CAPS) =
        Harness(profile = profile, lang = "ru", translit = translit)

    private fun en(profile: FieldProfile = Harness.NO_CAPS) = Harness(profile = profile, lang = "en")

    // ------------------------------------------------------------ транслит и удаление

    @Test
    fun translitBackspaceRemovesWholeSound() {
        val h = ru(TranslitMode.COLLOQUIAL)
        h.type("щи")
        assertEquals("schi", h.text)
        h.type("⌫")
        assertEquals("sch", h.text)
        h.type("⌫")
        assertEquals("", h.text)
    }

    @Test
    fun translitInvisibleSoftSignDeletedWithLetter() {
        val h = ru(TranslitMode.COLLOQUIAL)
        h.type("день")
        assertEquals("den", h.text)
        h.type("⌫")
        assertEquals("de", h.text)
    }

    @Test
    fun translitCapsLock() {
        val h = ru(TranslitMode.COLLOQUIAL)
        h.shift(); h.shift()
        assertEquals(InputEngine.Shift.LOCKED, h.eng.shift)
        h.type("женя ")
        assertEquals("ZHENYA ", h.text)
    }

    @Test
    fun translitShiftOnce() {
        val h = ru(TranslitMode.COLLOQUIAL)
        h.shift()
        h.type("женя ")
        assertEquals("Zhenya ", h.text)
    }

    @Test
    fun translitChips() {
        val h = ru(TranslitMode.COLLOQUIAL)
        h.type("прив")
        assertTrue(h.chips.toString(), "privet" in h.chips)
    }

    @Test
    fun translitAutocorrect() {
        val h = ru(TranslitMode.COLLOQUIAL)
        h.type("превет ")
        assertEquals("privet ", h.text)
    }

    @Test
    fun passwordFieldTypesRawAndDeletesWholeSound() {
        val h = ru(TranslitMode.COLLOQUIAL, FieldProfile.PASSWORD)
        h.type("щ")
        assertEquals("sch", h.text)
        h.type("⌫")
        assertEquals("", h.text)
    }

    @Test
    fun passwordFieldNoCorrectionNoChipsNoLearning() {
        val h = ru(profile = FieldProfile.PASSWORD)
        h.type("превет")
        assertTrue(h.chips.isEmpty())
        h.type(" ")
        assertEquals("превет ", h.text)
        assertEquals(0, UserHistory.count("ru", "превет"))
    }

    // ------------------------------------------------------------ автоисправление

    @Test
    fun realRussianWordsUntouched() {
        val h = ru()
        val s = "кот сон дама папка щи мама жена женя "
        h.type(s)
        assertEquals(s, h.text)
    }

    @Test
    fun realEnglishWordsUntouched() {
        val h = en()
        val s = "cat hat bed cup bag can ten "
        h.type(s)
        assertEquals(s, h.text)
    }

    @Test
    fun russianTyposFixed() {
        for ((typo, fix) in listOf(
            "превет" to "привет", "спосибо" to "спасибо", "сегодя" to "сегодня",
            "компютер" to "компьютер", "пожалуйсто" to "пожалуйста", "извени" to "извини"
        )) {
            val h = ru()
            h.type("$typo ")
            assertEquals(typo, "$fix ", h.text)
        }
    }

    @Test
    fun englishTyposFixed() {
        for ((typo, fix) in listOf(
            "teh" to "the", "dont" to "don't", "i" to "I", "im" to "I'm",
            "becuase" to "because", "recieve" to "receive", "thier" to "their"
        )) {
            val h = en()
            h.type("$typo ")
            assertEquals(typo, "$fix ", h.text)
        }
    }

    @Test
    fun prefixOfLongerWordNotCutShort() {
        val h = ru()
        h.type("прив")
        assertTrue(h.chips.toString(), h.eng.chips.none { it.kind == InputEngine.ChipKind.CORRECTION })
        assertTrue(h.chips.toString(), "привет" in h.chips)
        h.type(" ")
        assertEquals("прив ", h.text)
    }

    @Test
    fun commonMisspellingsTable() {
        for ((typo, fix) in listOf("вобщем" to "в общем", "незнаю" to "не знаю", "здраствуйте" to "здравствуйте")) {
            val h = ru()
            h.type("$typo ")
            assertEquals(typo, "$fix ", h.text)
        }
        for ((typo, fix) in listOf("alot" to "a lot", "untill" to "until", "definately" to "definitely")) {
            val h = en()
            h.type("$typo ")
            assertEquals(typo, "$fix ", h.text)
        }
        val h = ru()
        h.shift()
        h.type("вобщем,")
        assertEquals("В общем,", h.text)
        h.type("⌫")
        assertEquals("Вобщем,", h.text)
    }

    @Test
    fun chatSlangNotCorrected() {
        val h = ru()
        val s = "прив норм спс кек щас ок "
        h.type(s)
        assertEquals(s, h.text)
        val e = en()
        val t = "thx gif lol btw idk "
        e.type(t)
        assertEquals(t, e.text)
    }

    @Test
    fun correctionKeepsCapital() {
        val h = ru()
        h.shift()
        h.type("превет ")
        assertEquals("Привет ", h.text)
    }

    @Test
    fun allCapsNotCorrected() {
        val h = ru()
        h.shift(); h.shift()
        h.type("превет ")
        assertEquals("ПРЕВЕТ ", h.text)
    }

    @Test
    fun correctionChipShown() {
        val h = ru()
        h.type("превет")
        assertEquals("«превет»", h.chips[0])
        assertEquals("привет", h.chips[1])
    }

    @Test
    fun backspaceUndoesCorrectionAndLearns() {
        val h = ru()
        h.type("превет ")
        assertEquals("привет ", h.text)
        h.type("⌫")
        assertEquals("превет", h.text)
        h.type("как ")
        assertEquals("превет как ", h.text)
        assertTrue(UserHistory.count("ru", "превет") >= UserHistory.VALID_COUNT)
        // теперь слово своё — больше не исправляется
        h.type("превет ")
        assertEquals("превет как превет ", h.text)
    }

    @Test
    fun undoBeforePunctuation() {
        val h = ru()
        h.type("превет,")
        assertEquals("привет,", h.text)
        h.type("⌫")
        assertEquals("превет,", h.text)
    }

    @Test
    fun typedChipKeepsTypoAndLearns() {
        val h = ru()
        h.type("превет")
        h.pick("«превет»")
        assertEquals("превет ", h.text)
        h.type("превет ")
        assertEquals("превет превет ", h.text)
    }

    @Test
    fun autocorrectCanBeDisabled() {
        val h = Harness(lang = "ru", settings = EngineSettings(autocorrect = false))
        h.type("превет ")
        assertEquals("превет ", h.text)
    }

    @Test
    fun apostropheAndHyphenWords() {
        val e = en()
        e.type("don't can't ")
        assertEquals("don't can't ", e.text)
        val r = ru()
        r.type("кто-то как-нибудь ")
        assertEquals("кто-то как-нибудь ", r.text)
    }

    // ------------------------------------------------------------ подсказки и обучение

    @Test
    fun completionsShown() {
        val h = ru()
        h.type("спас")
        assertTrue(h.chips.toString(), "спасибо" in h.chips)
    }

    @Test
    fun pickCompletion() {
        val h = ru()
        h.type("спас")
        h.pick("спасибо")
        assertEquals("спасибо ", h.text)
    }

    @Test
    fun predictionsAfterWord() {
        val h = ru()
        h.type("спасибо ")
        assertTrue(h.chips.toString(), "пожалуйста" in h.chips)
        h.pick("пожалуйста")
        assertEquals("спасибо пожалуйста ", h.text)
    }

    @Test
    fun newWordLearned() {
        val h = ru()
        h.type("кринжово ")
        h.type("кринжово ")
        assertEquals("кринжово кринжово ", h.text)
        h.type("крин")
        assertTrue(h.chips.toString(), "кринжово" in h.chips)
    }

    @Test
    fun learningCanBeDisabled() {
        val h = Harness(lang = "ru", settings = EngineSettings(learn = false))
        h.type("кринжово кринжово ")
        assertEquals(0, UserHistory.count("ru", "кринжово"))
    }

    // ------------------------------------------------------------ пунктуация, регистр

    @Test
    fun autoCapsAfterSentence() {
        val h = Harness(profile = FieldProfile.TEXT, lang = "ru")
        h.type("привет. как дела? норм")
        assertEquals("Привет. Как дела? Норм", h.text)
    }

    @Test
    fun noAutoCapsWhenDisabled() {
        val h = Harness(profile = FieldProfile.TEXT, lang = "ru", settings = EngineSettings(autoCaps = false))
        h.type("привет")
        assertEquals("привет", h.text)
    }

    @Test
    fun doubleSpacePeriod() {
        val h = ru()
        h.type("привет  ")
        assertEquals("привет. ", h.text)
    }

    @Test
    fun doubleSpaceTooSlowIsTwoSpaces() {
        val h = ru()
        h.type("привет ")
        h.now += 5000
        h.type(" ")
        assertEquals("привет  ", h.text)
    }

    @Test
    fun punctuationAfterPickedWordSwapsSpace() {
        val h = ru()
        h.type("привет")
        h.pick("привет")
        assertEquals("привет ", h.text)
        h.type(", как")
        assertEquals("привет, как", h.text)
    }

    @Test
    fun shiftDoubleTapNotTriggeredAcrossLetters() {
        val h = en()
        h.shift()
        h.type("a")
        h.shift()
        assertEquals(InputEngine.Shift.ONCE, h.eng.shift)
    }

    // ------------------------------------------------------------ курсор и поле

    @Test
    fun cursorMovedByUserDoesNotBreakBackspace() {
        val h = en()
        h.type("hello")
        h.ed.userMoveCursor(2)
        h.deliver()
        h.type("⌫")
        assertEquals("hllo", h.text)
    }

    @Test
    fun belatedUpdatesDoNotResetWord() {
        val h = Harness(lang = "ru", autoDeliver = false)
        h.type("при")
        h.deliverOne()
        h.type("в")
        h.deliverOne()
        h.type("ет")
        h.deliver()
        h.type(" ")
        h.deliver()
        assertEquals("привет ", h.text)
    }

    @Test
    fun selectionDeleted() {
        val h = en()
        h.type("hello world")
        h.ed.userSelect(0, 6)
        h.deliver()
        h.type("⌫")
        assertEquals("world", h.text)
    }

    @Test
    fun typingInsideWordIsDirect() {
        val h = en()
        h.type("abc")
        h.step { moveCursor(-1) }
        h.type("x")
        assertEquals("abxc", h.text)
        assertTrue(h.chips.isEmpty())
    }

    @Test
    fun deleteWord() {
        val h = en()
        h.type("one two three")
        h.step { deleteWord() }
        assertEquals("one two ", h.text)
        h.step { deleteWord() }
        assertEquals("one ", h.text)
    }

    @Test
    fun backspaceSwipeSelectsWords() {
        val h = en()
        h.type("one two three")
        h.step { assertTrue(backspaceSwipeStart()) }
        h.step { backspaceSwipe(2) }
        assertEquals(4, h.ed.selS)
        assertEquals(13, h.ed.selE)
        h.step { backspaceSwipeEnd() }
        assertEquals("one ", h.text)
    }

    @Test
    fun emojiDeletedWhole() {
        val h = en()
        for (e in listOf("👨‍👩‍👧", "👍🏽", "🇷🇺", "😀")) h.step { onText(e) }
        assertEquals("👨‍👩‍👧👍🏽🇷🇺😀", h.text)
        h.type("⌫")
        assertEquals("👨‍👩‍👧👍🏽🇷🇺", h.text)
        h.type("⌫")
        assertEquals("👨‍👩‍👧👍🏽", h.text)
        h.type("⌫")
        assertEquals("👨‍👩‍👧", h.text)
        h.type("⌫")
        assertEquals("", h.text)
    }

    @Test
    fun backspaceOnEmptyFieldSendsKey() {
        val h = en()
        h.type("⌫")
        assertEquals(listOf(InputEngine.KEY_DEL), h.ed.keys)
    }

    @Test
    fun enterPerformsAction() {
        val h = Harness(profile = Harness.NO_CAPS.copy(enterAction = 4, multiLine = false), lang = "ru")
        h.type("привет\n")
        assertEquals("привет", h.text)
        assertEquals(listOf(4), h.ed.actions)
    }

    @Test
    fun enterInMultilineInsertsNewline() {
        val h = ru()
        h.type("привет\nкак")
        assertEquals("привет\nкак", h.text)
    }

    @Test
    fun numberField() {
        val p = FieldProfile(FieldProfile.Kind.NUMBER, false, false, false, 0, false, 0, false)
        val h = Harness(profile = p, lang = "ru")
        h.type("12.5⌫7")
        assertEquals("12.7", h.text)
        assertTrue(h.chips.isEmpty())
    }

    @Test
    fun noChipsWhileTypingInsideOtherWords() {
        val h = en()
        h.type("cat")
        assertFalse(h.chips.isEmpty())
    }
}
