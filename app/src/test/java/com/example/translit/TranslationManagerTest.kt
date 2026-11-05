package com.example.translit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TranslationManagerTest {

    private lateinit var offline: FakeOffline
    private lateinit var online: FakeOnline
    private val savedOffline = TranslationManager.offline
    private val savedOnline = TranslationManager.online

    @Before
    fun setUp() {
        offline = FakeOffline()
        online = FakeOnline()
        TranslationManager.offline = offline
        TranslationManager.online = online::translate
        TranslationManager.clearMemoryCache()
    }

    @After
    fun tearDown() {
        TranslationManager.offline = savedOffline
        TranslationManager.online = savedOnline
        TranslationManager.clearMemoryCache()
    }

    private fun tr(text: String, target: String, mode: TranslationManager.Mode, source: String? = null) =
        TranslationManager.translate(null, text, target, mode, source, persist = false)

    @Test
    fun autoUsesPhoneWhenLanguagesDownloaded() {
        offline.have.add("ru")
        val r = tr("привет", "en", TranslationManager.Mode.AUTO)
        assertEquals(TranslationManager.ENGINE_OFFLINE, r.engine)
        assertEquals("[en offline] привет", r.text)
        assertEquals("ru", r.source)
        assertTrue(online.calls.isEmpty())
    }

    @Test
    fun autoGoesOnlineAndSuggestsDownload() {
        val r = tr("привет", "en", TranslationManager.Mode.AUTO)
        assertEquals(TranslationManager.ENGINE_GOOGLE, r.engine)
        assertEquals(listOf("ru"), r.offlineMissing)
        assertEquals(listOf("ru>en:привет"), online.calls)
        assertTrue(offline.calls.isEmpty())
    }

    @Test
    fun offlineModeNeverGoesOnline() {
        try {
            tr("привет", "de", TranslationManager.Mode.OFFLINE)
            fail("нужны модели")
        } catch (e: TranslationManager.Failure) {
            assertEquals(listOf("ru", "de"), e.missing)
        }
        assertTrue(online.calls.isEmpty())
        offline.have.addAll(listOf("ru", "de"))
        val r = tr("привет", "de", TranslationManager.Mode.OFFLINE)
        assertEquals("[de offline] привет", r.text)
        assertTrue(online.calls.isEmpty())
    }

    @Test
    fun offlineModeRejectsUnsupportedLanguage() {
        try {
            tr("сәлем", "en", TranslationManager.Mode.OFFLINE)
            fail("казахского в ML Kit нет")
        } catch (e: TranslationManager.Failure) {
            assertTrue(e.message!!, e.message!!.contains("Қазақша"))
        }
    }

    @Test
    fun onlineModeIgnoresPhone() {
        offline.have.add("ru")
        val r = tr("привет", "en", TranslationManager.Mode.ONLINE)
        assertEquals(TranslationManager.ENGINE_GOOGLE, r.engine)
        assertTrue(r.offlineMissing.isEmpty())
        assertTrue(offline.calls.isEmpty())
    }

    @Test
    fun onlineFailureIsReported() {
        online.fail = true
        try {
            tr("привет", "en", TranslationManager.Mode.AUTO)
            fail("ошибка сети")
        } catch (e: TranslationManager.Failure) {
            assertTrue(e.missing.isEmpty())
        }
    }

    @Test
    fun sameLanguageIsNotTranslated() {
        val r = tr("Привет, мир", "ru", TranslationManager.Mode.AUTO)
        assertEquals("Привет, мир", r.text)
        assertEquals("", r.engine)
        assertTrue(online.calls.isEmpty())
    }

    @Test
    fun knownSourceSkipsDetection() {
        offline.detectResult = { "de" }
        offline.have.add("ru")
        tr("привет", "en", TranslationManager.Mode.AUTO, source = "ru")
        assertEquals(listOf("ru>en:привет"), offline.calls)
    }

    @Test
    fun russianInLatinIsConvertedFirst() {
        offline.detectResult = { "ru-Latn" }
        offline.have.add("ru")
        val r = tr("privet kak dela", "en", TranslationManager.Mode.AUTO)
        assertEquals("[en offline] привет как дела", r.text)
        // а на русский — просто кириллицей
        assertEquals("привет", tr("privet", "ru", TranslationManager.Mode.AUTO).text)
    }

    @Test
    fun tellsTranslitFromEnglishByDictionaries() {
        Dicts.ensure()
        offline.detectResult = { null }
        fun latin(s: String, prefer: Boolean = false) = TranslationManager.detect(s, prefer).latin
        assertTrue(latin("privet, kak dela?"))
        assertTrue(latin("ya tebe zavtra pozvonyu"))
        assertTrue(latin("to chto nado"))
        assertFalse(latin("See you tomorrow at the station!"))
        assertFalse(latin("Thanks, no problem"))
        assertFalse("и немецкий — не транслит", latin("Guten Morgen", prefer = true))
        assertFalse("английский не транслит, даже если пишут транслитом", latin("see you", prefer = true))
        assertFalse("кириллица — не транслит", latin("привет"))
        // английские слова в транслите не портятся
        offline.have.add("ru")
        assertEquals("[en offline] Купил новый iPhone", tr("Kupil novyj iPhone", "en", TranslationManager.Mode.AUTO).text)
    }

    @Test
    fun cachesTranslations() {
        tr("как дела", "en", TranslationManager.Mode.AUTO)
        tr("как дела ", "en", TranslationManager.Mode.AUTO)
        assertEquals(1, online.calls.size)
        tr("как дела", "de", TranslationManager.Mode.AUTO)
        assertEquals(2, online.calls.size)
        assertEquals("[de] как дела", TranslationManager.getCached(null, "как дела", "de")!!.text)
    }

    @Test
    fun phoneDetectionCodesAreNormalized() {
        offline.detectResult = { "iw" }
        assertEquals(TranslationManager.Detected("he", true), TranslationManager.detect("שלום"))
        offline.detectResult = { "ja-Latn" }
        assertFalse(TranslationManager.detect("konnichiwa").reliable)
        offline.detectResult = { null }
        assertEquals("uk", TranslationManager.detect("Привіт, як справи?").lang)
    }

    @Test
    fun guessesLanguageByLetters() {
        fun g(s: String) = TranslationManager.guessLanguage(s).lang
        assertEquals("ru", g("Привет, как дела?"))
        assertEquals("uk", g("Привіт, як справи?"))
        assertEquals("be", g("Добры дзень, як справы ў цябе"))
        assertEquals("kk", g("Сәлем, қалың қалай?"))
        assertEquals("de", g("Schöne Grüße"))
        assertEquals("fr", g("Ça va très bien"))
        assertEquals("es", g("¿Qué tal, señor?"))
        assertEquals("pl", g("Dzień dobry, jak się masz?"))
        assertEquals("tr", g("Nasılsın, güzel değil mi?"))
        assertEquals("ja", g("こんにちは"))
        assertEquals("zh", g("你好"))
        assertEquals("ko", g("안녕하세요"))
        assertEquals("el", g("Γειά σου"))
        assertEquals("ka", g("გამარჯობა"))
        assertEquals("he", g("שלום"))
        assertEquals("ar", g("مرحبا"))
        val en = TranslationManager.guessLanguage("Hello, how are you?")
        assertEquals("en", en.lang)
        assertFalse("латиница без особых букв — неуверенно", en.reliable)
    }
}
