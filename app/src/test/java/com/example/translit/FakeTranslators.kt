package com.example.translit

/** Перевод на телефоне для тестов: «скачанные» языки и словарь переводов. */
class FakeOffline(
    var have: MutableSet<String> = mutableSetOf("en"),
    private val supported: Set<String> = setOf("en", "ru", "de", "fr", "es", "uk")
) : OfflineEngine {
    val calls = ArrayList<String>()
    val downloads = ArrayList<List<String>>()
    var detectResult: ((String) -> String?)? = null

    override val available = true
    override fun supports(lang: String) = lang in supported
    override fun downloaded(): Set<String> = have + "en"
    override fun translate(text: String, source: String, target: String): String? {
        calls.add("$source>$target:$text")
        return "[$target offline] $text"
    }
    override fun detect(text: String): String? = detectResult?.invoke(text)
    override fun download(langs: List<String>, wifiOnly: Boolean): String? {
        downloads.add(langs)
        have.addAll(langs)
        return null
    }
    override fun delete(lang: String): String? {
        have.remove(lang)
        return null
    }
}

/** Онлайн-перевод для тестов: известные фразы, остальное — «[en] текст». */
class FakeOnline {
    val calls = ArrayList<String>()
    var fail = false
    val phrases = HashMap<String, String>()

    fun translate(text: String, source: String?, target: String): TranslationManager.Result? {
        calls.add("${source ?: "auto"}>$target:$text")
        if (fail) return null
        val out = phrases["$target:$text"] ?: "[$target] $text"
        val src = source ?: TranslationManager.guessLanguage(text).lang
        return TranslationManager.Result(out, src, TranslationManager.ENGINE_GOOGLE)
    }
}
