package com.example.translit

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Неизменяемый частотный словарь: отсортированные ключи + бинарный поиск.
 * Всё держится в памяти, поэтому поиск по префиксу на каждое нажатие не читает диск.
 *
 * Формат файла (assets/dict/<язык>.tsv): ключ<TAB>частота[<TAB>написание[<TAB>b]]
 */
class WordDictionary private constructor(
    private val keys: Array<String>,
    private val counts: IntArray,
    private val spellings: Array<String?>,
    private val blocked: BooleanArray
) {
    val size: Int get() = keys.size

    fun keyAt(i: Int): String = keys[i]
    fun countAt(i: Int): Int = counts[i]
    fun isBlockedAt(i: Int): Boolean = blocked[i]
    fun spellingAt(i: Int): String = spellings[i] ?: keys[i]

    /** Индекс ключа (уже нормализованного) или -1. */
    fun indexOf(key: String): Int {
        var lo = 0
        var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = keys[mid].compareTo(key)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun count(key: String): Int {
        val i = indexOf(key)
        return if (i >= 0) counts[i] else 0
    }

    /** Первый индекс, ключ которого >= [key]. */
    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** До [limit] самых частых слов, начинающихся с [prefix] (без грубых слов). */
    fun completions(prefix: String, limit: Int): IntArray {
        if (prefix.isEmpty() || limit <= 0) return IntArray(0)
        val from = lowerBound(prefix)
        val to = lowerBound(prefix + '\uFFFF')
        val best = IntArray(limit) { -1 }
        var n = 0
        for (i in from until to) {
            if (blocked[i]) continue
            val c = counts[i]
            if (n == limit && c <= counts[best[n - 1]]) continue
            // вставка в отсортированный по убыванию частоты массив
            var pos = if (n < limit) n++ else n - 1
            while (pos > 0 && counts[best[pos - 1]] < c) {
                best[pos] = best[pos - 1]
                pos--
            }
            best[pos] = i
        }
        return best.copyOf(n)
    }

    companion object {
        val EMPTY = WordDictionary(emptyArray(), IntArray(0), emptyArray(), BooleanArray(0))

        /** Нижний регистр, «ё» → «е», типографский апостроф → обычный. */
        fun normalize(s: CharSequence): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                sb.append(
                    when (ch) {
                        'ё', 'Ё' -> 'е'
                        '’', 'ʼ', '‘' -> '\''
                        else -> ch.lowercaseChar()
                    }
                )
            }
            return sb.toString()
        }

        fun load(input: InputStream): WordDictionary {
            val keys = ArrayList<String>(50_000)
            val counts = ArrayList<Int>(50_000)
            val spellings = ArrayList<String?>(50_000)
            val blocked = ArrayList<Boolean>(50_000)
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty() || line[0] == '#') continue
                    val parts = line.split('\t')
                    if (parts.size < 2) continue
                    val key = parts[0]
                    val count = parts[1].toIntOrNull() ?: continue
                    keys.add(key)
                    counts.add(count)
                    spellings.add(parts.getOrNull(2)?.ifEmpty { null })
                    blocked.add(parts.getOrNull(3) == "b")
                }
            }
            var order: List<Int> = keys.indices.toList()
            var sorted = true
            for (i in 1 until keys.size) if (keys[i - 1] > keys[i]) { sorted = false; break }
            if (!sorted) order = order.sortedBy { keys[it] }
            return WordDictionary(
                Array(order.size) { keys[order[it]] },
                IntArray(order.size) { counts[order[it]] },
                Array(order.size) { spellings[order[it]] },
                BooleanArray(order.size) { blocked[order[it]] }
            )
        }
    }
}
