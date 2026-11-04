package com.example.translit

import java.io.File

/**
 * Обучение на устройстве: какие слова пользователь печатает и какие слова идут друг за другом.
 * Хранится локально (filesDir/user_history.tsv), никуда не отправляется.
 *
 * Строки файла:
 *   w<TAB>язык<TAB>ключ<TAB>частота[<TAB>написание]
 *   p<TAB>язык<TAB>предыдущее<TAB>следующее<TAB>частота
 */
object UserHistory {

    private class Word(var count: Int, var spelling: String?)

    private const val MAX_WORDS = 6000
    private const val MAX_PAIRS = 12000

    private val words = HashMap<String, Word>()                       // "lang|key"
    private val pairs = HashMap<String, HashMap<String, Int>>()       // "lang|prev" -> next -> count
    private var pairCount = 0
    private var dirty = false
    private var file: File? = null

    /** Слово «своё» (не исправляется, подсказывается) после стольких наборов. */
    const val VALID_COUNT = 2

    @Synchronized
    fun learn(lang: String, key: String, spelling: String? = null, inc: Int = 1) {
        if (lang.isEmpty() || key.isEmpty() || key.length > 40) return
        val w = words.getOrPut("$lang|$key") { Word(0, null) }
        w.count = (w.count + inc).coerceIn(0, 1_000_000)
        if (spelling != null) w.spelling = if (spelling == key) null else spelling
        if (w.count == 0) words.remove("$lang|$key")
        dirty = true
        if (words.size > MAX_WORDS * 11 / 10) pruneWords()
    }

    @Synchronized
    fun learnPair(lang: String, prev: String, next: String) {
        if (lang.isEmpty() || prev.isEmpty() || next.isEmpty()) return
        val m = pairs.getOrPut("$lang|$prev") { HashMap() }
        val old = m[next]
        if (old == null) pairCount++
        m[next] = (old ?: 0) + 1
        dirty = true
        if (pairCount > MAX_PAIRS * 11 / 10) prunePairs()
    }

    @Synchronized
    fun count(lang: String, key: String): Int = words["$lang|$key"]?.count ?: 0

    @Synchronized
    fun spelling(lang: String, key: String): String? = words["$lang|$key"]?.spelling

    @Synchronized
    fun pairCount(lang: String, prev: String, next: String): Int =
        pairs["$lang|$prev"]?.get(next) ?: 0

    /** Выученные слова с префиксом: (ключ, частота). */
    @Synchronized
    fun completions(lang: String, prefix: String, minCount: Int, limit: Int): List<Pair<String, Int>> {
        if (prefix.isEmpty()) return emptyList()
        val p = "$lang|$prefix"
        return words.entries.asSequence()
            .filter { it.value.count >= minCount && it.key.startsWith(p) }
            .sortedByDescending { it.value.count }
            .take(limit)
            .map { it.key.substring(lang.length + 1) to it.value.count }
            .toList()
    }

    /** Слова, которые пользователь обычно пишет после [prev]. */
    @Synchronized
    fun nextWords(lang: String, prev: String, limit: Int): List<Pair<String, Int>> {
        val m = pairs["$lang|$prev"] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    @Synchronized
    fun clear() {
        words.clear()
        pairs.clear()
        pairCount = 0
        dirty = false
        file?.delete()
    }

    private fun pruneWords() {
        val keep = words.entries.sortedByDescending { it.value.count }.take(MAX_WORDS)
            .associate { it.key to it.value }
        words.clear()
        words.putAll(keep)
    }

    private fun prunePairs() {
        data class E(val prev: String, val next: String, val count: Int)
        val all = ArrayList<E>()
        for ((prev, m) in pairs) for ((next, c) in m) all.add(E(prev, next, c))
        all.sortByDescending { it.count }
        pairs.clear()
        pairCount = 0
        for (e in all.take(MAX_PAIRS)) {
            pairs.getOrPut(e.prev) { HashMap() }[e.next] = e.count
            pairCount++
        }
    }

    /** Загружает историю с диска и объединяет с тем, что успели выучить до загрузки. */
    @Synchronized
    fun load(f: File) {
        file = f
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size >= 4 && p[0] == "w" -> {
                        val c = p[3].toIntOrNull() ?: return@forEachLine
                        val w = words.getOrPut("${p[1]}|${p[2]}") { Word(0, null) }
                        w.count += c
                        if (w.spelling == null) w.spelling = p.getOrNull(4)?.ifEmpty { null }
                    }
                    p.size >= 5 && p[0] == "p" -> {
                        val c = p[4].toIntOrNull() ?: return@forEachLine
                        val m = pairs.getOrPut("${p[1]}|${p[2]}") { HashMap() }
                        val old = m[p[3]]
                        if (old == null) pairCount++
                        m[p[3]] = (old ?: 0) + c
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Сохраняет на диск, если были изменения. Можно вызывать из фонового потока. */
    fun saveIfDirty() {
        val snapshot: String
        val target: File
        synchronized(this) {
            target = file ?: return
            if (!dirty) return
            dirty = false
            val sb = StringBuilder()
            for ((k, w) in words) {
                val bar = k.indexOf('|')
                sb.append("w\t").append(k, 0, bar).append('\t').append(k, bar + 1, k.length)
                    .append('\t').append(w.count)
                if (w.spelling != null) sb.append('\t').append(w.spelling)
                sb.append('\n')
            }
            for ((k, m) in pairs) {
                val bar = k.indexOf('|')
                for ((next, c) in m) {
                    sb.append("p\t").append(k, 0, bar).append('\t').append(k, bar + 1, k.length)
                        .append('\t').append(next).append('\t').append(c).append('\n')
                }
            }
            snapshot = sb.toString()
        }
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(snapshot)
            if (!tmp.renameTo(target)) {
                target.writeText(snapshot)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** Для тестов: работа только в памяти. */
    @Synchronized
    fun resetInMemory() {
        file = null
        words.clear()
        pairs.clear()
        pairCount = 0
        dirty = false
    }
}
