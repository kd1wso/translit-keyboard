package com.example.translit

import android.content.Context
import android.provider.UserDictionary
import java.io.File
import java.util.TreeMap
import kotlin.math.ln

/**
 * Подсказки и автоисправление.
 *  • частотные словари ~48 тыс. слов (assets/dict), поиск по префиксу в памяти;
 *  • выученные слова и пары слов (UserHistory);
 *  • слова из системного пользовательского словаря и словарей-плагинов (filesDir/dictionaries/<имя>.txt);
 *  • фразы-ассоциации для предсказания следующего слова.
 *
 * Все слова — в «исходном» алфавите: для транслита это русские слова, транслит делает InputEngine.
 */
object SuggestionEngine {

    data class Result(
        /** Исправление (с правильным написанием) или null, если исправлять не нужно. */
        val correction: String?,
        /** Набранное слово есть в словаре или выучено. */
        val typedKnown: Boolean,
        /** Дополнения по префиксу (без набранного слова и исправления). */
        val words: List<String>
    )

    @Volatile private var dicts: Map<String, WordDictionary> = emptyMap()
    @Volatile private var extra: Map<String, TreeMap<String, String>> = emptyMap()
    @Volatile private var extraLoadedAt = 0L

    private const val RU_ALPHA = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
    private const val EN_ALPHA = "abcdefghijklmnopqrstuvwxyz"

    /** Частые «фонетические» ошибки в русском: безударные гласные, парные согласные. */
    private val RU_SOUNDALIKE: Set<String> = listOf(
        "ао", "еи", "ея", "иы", "еэ", "ую", "ая", "шщ", "зс", "дт", "бп", "вф", "гк", "ьъ", "жш", "цс"
    ).flatMap { listOf(it, it.reversed()) }.toSet()

    // ------------------------------------------------------------------ загрузка

    fun dict(lang: String): WordDictionary = dicts[lang] ?: WordDictionary.EMPTY

    fun isLoaded(lang: String): Boolean = dicts.containsKey(lang)

    @Synchronized
    fun put(lang: String, d: WordDictionary) {
        dicts = dicts + (lang to d)
    }

    /** Загружает словари из assets (долго — вызывать в фоне). */
    fun loadAssets(ctx: Context) {
        for (lang in listOf("ru", "en")) {
            if (isLoaded(lang)) continue
            try {
                ctx.assets.open("dict/$lang.fixes.tsv").use { putFixes(lang, parseFixes(it)) }
            } catch (_: Exception) {
            }
            try {
                ctx.assets.open("dict/$lang.tsv").use { put(lang, WordDictionary.load(it)) }
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ таблица частых ошибок

    @Volatile private var fixes: Map<String, Map<String, String>> = emptyMap()

    /** Строки «опечатка<TAB>исправление»; # — комментарий. */
    fun parseFixes(input: java.io.InputStream): Map<String, String> {
        val m = HashMap<String, String>()
        input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            if (line.isEmpty() || line[0] == '#') return@forEachLine
            val t = line.indexOf('\t')
            if (t <= 0) return@forEachLine
            val fix = line.substring(t + 1).trim()
            if (fix.isNotEmpty()) m[WordDictionary.normalize(line.substring(0, t).trim())] = fix
        }
        return m
    }

    @Synchronized
    fun putFixes(lang: String, m: Map<String, String>) {
        fixes = fixes + (lang to m)
    }

    private fun langOfWord(w: String, locale: String?): String {
        val loc = locale?.lowercase() ?: ""
        if (loc.startsWith("ru") || loc.startsWith("uk") || loc.startsWith("be")) return "ru"
        if (loc.startsWith("en")) return "en"
        return if (w.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) "ru" else "en"
    }

    /** Системный пользовательский словарь + словари-плагины. Вызывать в фоне. */
    fun loadExtraWords(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - extraLoadedAt < 5 * 60_000) return
        extraLoadedAt = now
        val m = HashMap<String, TreeMap<String, String>>()
        fun add(raw: String, locale: String?) {
            val w = raw.trim()
            if (w.isEmpty() || w.length > 40 || w.any { it.isWhitespace() }) return
            m.getOrPut(langOfWord(w, locale)) { TreeMap() }[WordDictionary.normalize(w)] = w
        }
        try {
            ctx.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val w = c.getString(0) ?: continue
                    add(w, c.getString(1))
                }
            }
        } catch (_: Exception) {
        }
        val dir = File(ctx.filesDir, "dictionaries")
        if (dir.isDirectory) {
            dir.listFiles { f -> f.extension == "txt" }?.forEach { f ->
                try {
                    f.forEachLine { add(it, null) }
                } catch (_: Exception) {
                }
            }
        }
        extra = m
    }

    /** Для тестов. */
    fun setExtraWords(lang: String, words: Collection<String>) {
        val t = TreeMap<String, String>()
        for (w in words) t[WordDictionary.normalize(w)] = w
        extra = extra + (lang to t)
    }

    // ------------------------------------------------------------------ оценка слов

    /** Слово известно: есть в словаре (включая грубые), в доп. словарях или выучено. */
    fun isKnown(lang: String, key: String): Boolean {
        if (key.isEmpty()) return false
        if (dict(lang).indexOf(key) >= 0) return true
        if (extra[lang]?.containsKey(key) == true) return true
        return UserHistory.count(lang, key) >= UserHistory.VALID_COUNT
    }

    /** ln(частоты) с учётом личной статистики; null — неизвестное или грубое слово. */
    private fun baseScore(lang: String, key: String, d: WordDictionary): Double? {
        val i = d.indexOf(key)
        val user = UserHistory.count(lang, key)
        if (i >= 0) {
            if (d.isBlockedAt(i)) return null
            return ln(d.countAt(i) + 1.0 + user * 200.0)
        }
        if (user >= UserHistory.VALID_COUNT) return ln(1.0 + user * 200.0)
        if (extra[lang]?.containsKey(key) == true) return ln(1000.0)
        return null
    }

    fun spellingOf(lang: String, key: String): String {
        val d = dict(lang)
        val i = d.indexOf(key)
        if (i >= 0) return d.spellingAt(i)
        UserHistory.spelling(lang, key)?.let { return it }
        extra[lang]?.get(key)?.let { return it }
        return key
    }

    private fun near(prox: Map<Char, Set<Char>>, a: Char, b: Char): Boolean =
        prox[a]?.contains(b) == true

    /**
     * Исправление опечатки с высокой уверенностью или null.
     * Рассматриваются слова на расстоянии одной правки (лишняя/пропущенная/заменённая буква,
     * перестановка соседних); соседние клавиши и частые ошибки стоят дешевле.
     * [strict] — слово с заглавной посреди предложения (вероятно, имя): порог выше.
     */
    fun correction(lang: String, key: String, prox: Map<Char, Set<Char>>, strict: Boolean): String? {
        val d = dict(lang)
        val n = key.length
        if (d.size == 0 || n < 2 || n > 30) return null
        val alpha = when (lang) {
            "ru" -> RU_ALPHA
            "en" -> EN_ALPHA
            else -> return null
        }
        if (key.any { !(it in alpha || it == '\'' || it == '-') }) return null
        if (UserHistory.count(lang, key) >= UserHistory.VALID_COUNT) return null
        fixes[lang]?.get(key)?.let { return it }
        if (isKnown(lang, key)) return null

        val cands = HashMap<String, Double>()
        // «далёкие» правки (не соседняя клавиша, не удвоение) в коротких словах почти всегда ошибочны:
        // «thx» — не опечатка в «the»
        val farExtra = when {
            n <= 3 -> Double.NEGATIVE_INFINITY
            n == 4 -> -1.5
            else -> 0.0
        }
        fun consider(c: String, penalty: Double, far: Boolean = false) {
            if (c.isEmpty() || c[0] == '-' || c[0] == '\'') return
            if (far && farExtra == Double.NEGATIVE_INFINITY) return
            val s = baseScore(lang, c, d) ?: return
            val t = s + penalty + (if (far) farExtra else 0.0)
            val old = cands[c]
            if (old == null || t > old) cands[c] = t
        }
        val sb = StringBuilder(n + 1)
        // набранное — начало известного слова («прив» → «привет»): последнюю букву не выкидываем,
        // иначе посреди слова подсказка и автозамена прыгают на короткое «при»
        val topCompletion = d.completions(key, 1).let { if (it.isNotEmpty()) d.countAt(it[0]) else 0 }
        val prefixOfKnown = topCompletion >= 20
        // 1. лишняя буква
        for (i in 0 until n) {
            val ch = key[i]
            val prev = if (i > 0) key[i - 1] else null
            val next = if (i + 1 < n) key[i + 1] else null
            if (i == n - 1 && prefixOfKnown && ch != prev) continue
            val far = !(ch == prev || ch == next || ch == '\'' || ch == '-' ||
                (prev != null && near(prox, ch, prev)) || (next != null && near(prox, ch, next)))
            var p = when {
                ch == prev || ch == next -> -1.5
                (prev != null && near(prox, ch, prev)) || (next != null && near(prox, ch, next)) -> -2.5
                ch == '\'' || ch == '-' -> -2.0
                else -> -3.5
            }
            if (i == 0) p -= 1.0
            sb.setLength(0)
            sb.append(key, 0, i).append(key, i + 1, n)
            consider(sb.toString(), p, far)
        }
        // 2. перестановка соседних букв
        for (i in 0 until n - 1) {
            if (key[i] == key[i + 1]) continue
            sb.setLength(0)
            sb.append(key, 0, i).append(key[i + 1]).append(key[i]).append(key, i + 2, n)
            consider(sb.toString(), if (i == 0) -2.5 else -2.0)
        }
        // 3. замена буквы
        for (i in 0 until n) {
            val ch = key[i]
            for (c in alpha) {
                if (c == ch) continue
                val close = near(prox, ch, c) || (lang == "ru" && RU_SOUNDALIKE.contains("$ch$c"))
                var p = if (close) -2.0 else -4.5
                if (i == 0) p -= 0.5
                sb.setLength(0)
                sb.append(key, 0, i).append(c).append(key, i + 1, n)
                consider(sb.toString(), p, !close)
            }
        }
        // 4. пропущенная буква (и апостроф/дефис: dont → don't, чтото → что-то)
        for (i in 0..n) {
            for (c in alpha) {
                val doubled = (i > 0 && key[i - 1] == c) || (i < n && key[i] == c)
                val sign = lang == "ru" && (c == 'ь' || c == 'ъ')
                var p = when {
                    doubled -> -2.0
                    sign -> -1.5
                    else -> -3.0
                }
                if (i == 0) p -= 0.5
                sb.setLength(0)
                sb.append(key, 0, i).append(c).append(key, i, n)
                consider(sb.toString(), p)
            }
            if (i in 1 until n) {
                sb.setLength(0)
                sb.append(key, 0, i).append('\'').append(key, i, n)
                if (lang == "en") consider(sb.toString(), 0.0)
                sb.setCharAt(i, '-')
                consider(sb.toString(), -1.0)
            }
        }
        if (cands.isEmpty()) return null
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var second = Double.NEGATIVE_INFINITY
        for ((w, s) in cands) {
            if (s > bestScore) {
                second = bestScore
                bestScore = s
                best = w
            } else if (s > second) {
                second = s
            }
        }
        val b = best ?: return null
        var threshold = 6.0 + (if (n <= 3) 1.0 else 0.0) + (if (strict) 2.0 else 0.0)
        // начало частого слова заменяем на другое слово только при очень уверенной правке
        if (prefixOfKnown && !b.startsWith(key)) threshold += 0.3 * ln(topCompletion + 1.0)
        if (bestScore < threshold) return null
        if (bestScore - second < 1.0) return null
        if (n < 3 && !b.contains('\'')) return null
        return spellingOf(lang, b)
    }

    /** Правильный регистр известного слова: i → I, i'm → I'm. */
    fun caseFix(lang: String, key: String): String? {
        val d = dict(lang)
        val i = d.indexOf(key)
        if (i < 0) return null
        val sp = d.spellingAt(i)
        return if (sp != key && sp.lowercase() == key) sp else null
    }

    private fun assoc(lang: String): Map<String, List<String>> = if (lang == "en") assocEn else assocRu

    private fun contextBonus(lang: String, prev: String?, k: String): Double {
        if (prev == null) return 0.0
        var b = 0.0
        val pc = UserHistory.pairCount(lang, prev, k)
        if (pc > 0) b += 2.0 + 1.5 * ln(pc + 1.0)
        val phrases = assoc(lang)[prev]
        if (phrases != null && phrases.any { it == k || it.startsWith("$k ") || it.startsWith("$k,") }) b += 2.0
        return b
    }

    /**
     * Подсказки для набираемого слова. [key] — нормализованное слово, [prev] — предыдущее слово.
     */
    fun suggest(
        lang: String,
        key: String,
        prev: String?,
        prox: Map<Char, Set<Char>>,
        allowCorrection: Boolean,
        strict: Boolean,
        limit: Int = 3
    ): Result {
        if (key.isEmpty()) return Result(null, false, emptyList())
        val d = dict(lang)
        val typedKnown = isKnown(lang, key)
        val correction = if (allowCorrection) correction(lang, key, prox, strict) else null
        val corrKey = correction?.let { WordDictionary.normalize(it) }
        val scores = HashMap<String, Double>()
        fun add(k: String, base: Double) {
            if (k == key || k == corrKey) return
            val i = d.indexOf(k)
            if (i >= 0 && d.isBlockedAt(i)) return
            val s = base + contextBonus(lang, prev, k)
            val old = scores[k]
            if (old == null || s > old) scores[k] = s
        }
        for (i in d.completions(key, 12)) {
            val k = d.keyAt(i)
            add(k, ln(d.countAt(i) + 1.0 + UserHistory.count(lang, k) * 200.0))
        }
        for ((k, c) in UserHistory.completions(lang, key, UserHistory.VALID_COUNT, 8)) {
            val i = d.indexOf(k)
            add(k, ln((if (i >= 0) d.countAt(i) else 0) + 1.0 + c * 200.0))
        }
        extra[lang]?.subMap(key, key + '\uFFFF')?.keys?.take(8)?.forEach { add(it, ln(1000.0)) }
        val words = scores.entries.sortedByDescending { it.value }
            .take(limit)
            .map { spellingOf(lang, it.key) }
        return Result(correction, typedKnown, words)
    }

    /** Следующее слово (или фраза) после [prev] (и [prev2] перед ним). */
    fun predict(lang: String, prev: String?, prev2: String?, limit: Int = 3): List<String> {
        if (prev == null || lang.isEmpty()) return emptyList()
        val d = dict(lang)
        val out = LinkedHashSet<String>()
        val personal = UserHistory.nextWords(lang, prev, 6).filter { (w, _) ->
            val i = d.indexOf(w)
            i < 0 || !d.isBlockedAt(i)
        }
        personal.filter { it.second >= 2 }.forEach { out.add(spellingOf(lang, it.first)) }
        if (prev2 != null) assoc(lang)["$prev2 $prev"]?.forEach { out.add(it) }
        assoc(lang)[prev]?.forEach { out.add(it) }
        personal.filter { it.second < 2 }.forEach { out.add(spellingOf(lang, it.first)) }
        return out.take(limit)
    }

    private val assocRu: Map<String, List<String>> = mapOf(
        "привет" to listOf("как дела", "что нового", "давно не виделись", "здорово", "приветик"),
        "здравствуй" to listOf("как дела", "что нового", "рад тебя видеть"),
        "как" to listOf("дела", "ты", "поживаешь", "настроение", "прошли выходные", "сам"),
        "дела" to listOf("хорошо", "нормально", "плохо", "а у тебя", "потихоньку", "отлично"),
        "ты" to listOf("как", "где", "когда", "что делаешь", "здесь", "свободен"),
        "пока" to listOf("до встречи", "увидимся", "до завтра", "спокойной ночи", "береги себя"),
        "спокойной" to listOf("ночи", "ночи, сладких снов"),
        "ночи" to listOf("сладких снов", "до утра"),
        "доброе" to listOf("утро", "утро, как спалось"),
        "утро" to listOf("доброе", "как спалось", "кофе будешь"),
        "добрый" to listOf("день", "вечер"),
        "день" to listOf("добрый", "как прошел", "гиблый"),
        "вечер" to listOf("добрый", "чем займемся", "в кино"),
        "спасибо" to listOf("пожалуйста", "не за что", "обращайся", "всегда пожалуйста"),
        "пожалуйста" to listOf("не за что", "обращайся"),
        "извини" to listOf("все нормально", "ничего страшного", "проехали", "не парься"),
        "прости" to listOf("все нормально", "ничего страшного", "я не обижаюсь"),
        "да" to listOf("конечно", "точно", "без проблем", "договорились", "давай"),
        "нет" to listOf("не хочу", "не могу", "в другой раз", "извини", "спасибо"),
        "хорошо" to listOf("договорились", "отлично", "ладно", "по рукам", "что дальше"),
        "люблю" to listOf("тебя", "тоже тебя люблю", "очень скучаю"),
        "скучаю" to listOf("по тебе", "тоже скучаю", "приезжай скорее"),
        "позвони" to listOf("мне", "как доберешься", "позже", "вечером"),
        "напиши" to listOf("мне", "как приедешь", "в телеграм", "когда будешь"),
        "встретимся" to listOf("завтра", "позже", "где и когда", "у метро"),
        "где" to listOf("ты", "ты сейчас", "встретимся", "находишься"),
        "когда" to listOf("ты приедешь", "встретимся", "созвонимся", "будешь"),
        "что" to listOf("делаешь", "нового", "случилось", "будешь делать"),
        "пойдем" to listOf("гулять", "в кино", "кофе выпьем", "погуляем", "покушаем"),
        "кофе" to listOf("будешь", "выпьем", "с молоком", "попьем"),
        "жду" to listOf("тебя", "звонка", "ответа", "сообщения"),
        "до" to listOf("встречи", "завтра", "свидания", "скорого"),
        "как дела" to listOf("хорошо", "нормально", "а у тебя", "все отлично"),
        "как ты" to listOf("поживаешь", "себя чувствуешь", "добираешься"),
        "что нового" to listOf("ничего", "расскажу при встрече", "много всего"),
        "рад тебя" to listOf("видеть", "слышать"),
        "давно не" to listOf("виделись", "созванивались", "писали"),
        "а у" to listOf("тебя", "вас", "нас"),
        "не за" to listOf("что", "что, обращайся"),
        "все нормально" to listOf("не переживай", "забудь", "проехали"),
        "ничего страшного" to listOf("бывает", "забудь", "все ок"),
        "сладких снов" to listOf("до утра", "спокойной ночи")
    )

    private val assocEn: Map<String, List<String>> = mapOf(
        "hello" to listOf("how are you", "hi there", "good to see you", "what's up"),
        "hi" to listOf("how are you", "what's up", "there", "long time no see"),
        "how" to listOf("are you", "about", "much", "was your day", "are you doing"),
        "are" to listOf("you okay", "you coming", "you sure", "we there yet", "you"),
        "you" to listOf("too", "are welcome", "look great", "tell me", "doing"),
        "thanks" to listOf("you're welcome", "no problem", "anytime", "glad to help"),
        "thank" to listOf("you", "you so much", "you very much"),
        "sorry" to listOf("it's okay", "no worries", "my bad", "don't worry"),
        "good" to listOf("morning", "night", "job", "to see you", "evening"),
        "morning" to listOf("good morning", "how did you sleep", "coffee?"),
        "night" to listOf("good night", "sweet dreams", "sleep well"),
        "bye" to listOf("see you", "take care", "talk later", "good night"),
        "see" to listOf("you later", "you soon", "you tomorrow", "what you mean"),
        "yes" to listOf("of course", "sure", "absolutely", "no problem", "please"),
        "no" to listOf("thanks", "way", "I can't", "not today", "problem"),
        "love" to listOf("you", "you too", "this so much", "it"),
        "miss" to listOf("you", "you too", "so much"),
        "call" to listOf("me", "me later", "you back", "me tonight"),
        "meet" to listOf("you later", "at the usual place", "tomorrow", "up"),
        "what" to listOf("are you doing", "happened", "is up", "do you think", "time"),
        "where" to listOf("are you", "are we going", "is it", "to meet"),
        "when" to listOf("are you coming", "do we meet", "will you call", "is it"),
        "okay" to listOf("then", "got it", "no problem", "see you", "cool"),
        "please" to listOf("help me", "let me know", "call me back", "wait"),
        "welcome" to listOf("to our team", "back", "home", "anytime"),
        "how are you" to listOf("fine thanks", "good, and you", "not bad"),
        "long time" to listOf("no see", "we should catch up"),
        "no problem" to listOf("glad to help", "anytime", "you're welcome")
    )
}
