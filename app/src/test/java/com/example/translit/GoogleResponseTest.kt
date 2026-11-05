package com.example.translit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Разбор ответа Google Translate (org.json настоящий только под Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleResponseTest {

    @Test
    fun parsesTranslationAndSourceLanguage() {
        val body = """[[["Hi, how are you? ","Привет, как дела? ",null,null,10],["Fine.","Нормально.",null,null,10]],null,"ru",null,null,null,1]"""
        val (text, src) = TranslationManager.parseGoogle(body)!!
        assertEquals("Hi, how are you? Fine.", text)
        assertEquals("ru", src)
    }

    @Test
    fun emptyResponse() {
        assertNull(TranslationManager.parseGoogle("[null,null,\"en\"]"))
    }
}
