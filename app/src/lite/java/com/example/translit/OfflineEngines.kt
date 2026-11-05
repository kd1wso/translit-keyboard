package com.example.translit

/** Лёгкая сборка (-Plite): перевода на устройстве нет, только онлайн. */
object OfflineEngines {
    val default: OfflineEngine = object : OfflineEngine {
        override val available = false
        override fun supports(lang: String) = false
        override fun downloaded(): Set<String> = emptySet()
        override fun isDownloaded(lang: String) = false
        override fun translate(text: String, source: String, target: String): String? = null
        override fun detect(text: String): String? = null
        override fun download(langs: List<String>, wifiOnly: Boolean) = "в этой сборке нет перевода без интернета"
        override fun delete(lang: String): String? = null
    }
}
