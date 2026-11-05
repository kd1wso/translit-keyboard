package com.example.translit

/**
 * Перевод на самом телефоне (Google ML Kit): модель языка скачивается один раз (~30 МБ),
 * дальше текст никуда не отправляется. Английский встроен. Перевод идёт через английский,
 * поэтому для «русский → немецкий» нужны модели обоих языков.
 *
 * Все методы, кроме [available] и [supports], блокирующие — вызывать только из фонового потока.
 * Реализации: src/mlkit (ML Kit) и src/lite (сборка без перевода на устройстве).
 */
interface OfflineEngine {
    /** Есть ли в этой сборке перевод без интернета. */
    val available: Boolean

    /** Можно ли переводить этот язык без интернета (коды ISO 639-1: en, ru, de…). */
    fun supports(lang: String): Boolean

    /** Скачанные языки (английский встроен и всегда есть). */
    fun downloaded(): Set<String>

    fun isDownloaded(lang: String): Boolean = lang == "en" || lang in downloaded()

    /** Перевод или null, если не вышло. */
    fun translate(text: String, source: String, target: String): String?

    /** Язык текста («ru», «en», «ru-Latn» — русский латиницей) или null, если непонятно. */
    fun detect(text: String): String?

    /** Скачать модели языков; null — готово, иначе текст ошибки. Может идти минуты. */
    fun download(langs: List<String>, wifiOnly: Boolean): String?

    /** Удалить модель языка; null — готово, иначе текст ошибки. */
    fun delete(lang: String): String?
}
