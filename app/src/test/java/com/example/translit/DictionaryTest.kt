package com.example.translit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DictionaryTest {

    @Before
    fun reset() {
        UserHistory.resetInMemory()
    }

    private fun dict(tsv: String) = WordDictionary.load(tsv.byteInputStream())

    @Test
    fun lookupAndCompletions() {
        val d = dict("кот\t50\nкошка\t80\nкошмар\t30\nкошь\t999\t\tb\nмама\t100\n")
        assertEquals(5, d.size)
        assertTrue(d.indexOf("кошка") >= 0)
        assertEquals(-1, d.indexOf("кош"))
        assertEquals(80, d.count("кошка"))
        val c = d.completions("кош", 5).map { d.keyAt(it) }
        assertEquals(listOf("кошка", "кошмар"), c)  // заблокированное «кошь» не подсказывается
    }

    @Test
    fun unsortedInputIsSorted() {
        val d = dict("я\t1\nа\t2\nб\t3\n")
        assertEquals(listOf("а", "б", "я"), (0 until d.size).map { d.keyAt(it) })
    }

    @Test
    fun normalize() {
        assertEquals("еще", WordDictionary.normalize("Ещё"))
        assertEquals("don't", WordDictionary.normalize("Don’t"))
    }

    @Test
    fun spellingIsKept() {
        val d = dict("i\t10\tI\ni'm\t5\tI'm\n")
        assertEquals("I'm", d.spellingAt(d.indexOf("i'm")))
        assertEquals("cat", dict("cat\t1\n").spellingAt(0))
    }

    @Test
    fun userHistoryRoundTrip() {
        val f = File.createTempFile("history", ".tsv")
        f.delete()
        UserHistory.load(f)
        UserHistory.learn("ru", "кринжово", null, 2)
        UserHistory.learn("en", "zoomer", "Zoomer", 1)
        UserHistory.learnPair("ru", "очень", "кринжово")
        UserHistory.saveIfDirty()
        assertTrue(f.exists())

        UserHistory.resetInMemory()
        assertEquals(0, UserHistory.count("ru", "кринжово"))
        UserHistory.load(f)
        assertEquals(2, UserHistory.count("ru", "кринжово"))
        assertEquals("Zoomer", UserHistory.spelling("en", "zoomer"))
        assertEquals(1, UserHistory.pairCount("ru", "очень", "кринжово"))
        assertEquals("кринжово", UserHistory.completions("ru", "крин", 1, 3).first().first)

        UserHistory.clear()
        assertEquals(0, UserHistory.count("ru", "кринжово"))
        assertFalse(f.exists())
        f.delete()
    }

    @Test
    fun negativeLearningForgets() {
        UserHistory.learn("ru", "превет", null, 1)
        UserHistory.learn("ru", "превет", null, -1)
        assertEquals(0, UserHistory.count("ru", "превет"))
        assertNull(UserHistory.spelling("ru", "превет"))
    }
}
