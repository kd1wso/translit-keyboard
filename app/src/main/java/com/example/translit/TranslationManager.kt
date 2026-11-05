package com.example.translit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Перевод: на телефоне ([OfflineEngine], Google ML Kit) или онлайн — Google Translate
 * с резервным MyMemory. Язык текста определяется на телефоне (ML Kit) или по буквам.
 * Результаты кэшируются в памяти (LRU), переводы с панели — ещё и на диске.
 */
object TranslationManager {

    /** AUTO — без интернета, если языки скачаны, иначе онлайн; OFFLINE — текст не уходит в сеть. */
    enum class Mode { AUTO, OFFLINE, ONLINE }

    /**
     * [source] — язык оригинала, [engine] — чем переведено («» — переводить не понадобилось),
     * [offlineMissing] — какие языки скачать, чтобы такое переводилось без интернета.
     */
    data class Result(
        val text: String,
        val source: String,
        val engine: String,
        val offlineMissing: List<String> = emptyList()
    )

    /** Не удалось перевести; [missing] — какие языки нужно скачать для перевода без интернета. */
    class Failure(message: String, val missing: List<String> = emptyList()) : Exception(message)

    /** Язык текста; [reliable] — определён уверенно, [latin] — русский латиницей (транслит). */
    data class Detected(val lang: String, val reliable: Boolean, val latin: Boolean = false)

    const val ENGINE_OFFLINE = "без интернета"
    const val ENGINE_GOOGLE = "Google"
    const val ENGINE_MYMEMORY = "MyMemory"

    val LANGS = listOf(
        "en" to "English", "ru" to "Русский", "uk" to "Українська", "be" to "Беларуская",
        "bg" to "Български", "de" to "Deutsch", "fr" to "Français", "es" to "Español",
        "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe", "pl" to "Polski",
        "kk" to "Қазақша", "zh" to "中文", "ja" to "日本語", "ko" to "한국어",
        "ar" to "العربية", "hi" to "हिन्दी", "cs" to "Čeština", "el" to "Ελληνικά",
        "nl" to "Nederlands", "sv" to "Svenska", "fi" to "Suomi", "ro" to "Română",
        "hu" to "Magyar", "he" to "עברית", "th" to "ไทย", "vi" to "Tiếng Việt",
        "id" to "Bahasa", "ka" to "ქართული", "az" to "Azərbaycan", "uz" to "Oʻzbekcha"
    )

    fun name(code: String): String = LANGS.firstOrNull { it.first == code }?.second ?: code

    /** Перевод на телефоне; в тестах подменяется. */
    @Volatile
    var offline: OfflineEngine = OfflineEngines.default

    /** Онлайн-перевод: (текст, язык оригинала или null — определить, язык перевода); в тестах подменяется. */
    @Volatile
    var online: (String, String?, String) -> Result? = { text, source, target -> onlineTranslate(text, source, target) }

    /** Где выполнять переводы (в тестах — сразу). */
    @Volatile
    var runner: (Runnable) -> Unit = { executor.execute(it) }

    private val executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "translate").apply { isDaemon = true } }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    // ================================================================ кэш

    private const val CACHE_FILE = "translate_cache_v2.json"
    private const val OLD_CACHE_FILE = "translate_cache.json"
    private const val MAX_CACHE = 200

    private val lock = Any()
    private val memCache = LinkedHashMap<String, Result>(MAX_CACHE, 0.75f, true)
    private var diskLoaded = false

    private fun cacheKey(text: String, target: String) = "$target|$text"

    private fun loadDisk(ctx: Context?) {
        if (diskLoaded || ctx == null) return
        diskLoaded = true
        try {
            File(ctx.filesDir, OLD_CACHE_FILE).delete()
            val f = File(ctx.filesDir, CACHE_FILE)
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.getJSONObject(k)
                memCache[k] = Result(v.getString("t"), v.optString("s"), v.optString("e"))
            }
        } catch (_: Exception) {
        }
    }

    private fun saveDisk(ctx: Context) {
        try {
            val obj = JSONObject()
            for ((k, v) in memCache) obj.put(k, JSONObject().put("t", v.text).put("s", v.source).put("e", v.engine))
            File(ctx.filesDir, CACHE_FILE).writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    fun getCached(ctx: Context?, text: String, target: String): Result? = synchronized(lock) {
        loadDisk(ctx)
        memCache[cacheKey(text.trim(), target)]
    }

    private fun putCache(ctx: Context?, text: String, target: String, r: Result, persist: Boolean) = synchronized(lock) {
        loadDisk(ctx)
        memCache[cacheKey(text, target)] = r
        while (memCache.size > MAX_CACHE) {
            val eldest = memCache.keys.firstOrNull() ?: break
            memCache.remove(eldest)
        }
        if (persist && ctx != null) saveDisk(ctx)
    }

    fun clearMemoryCache() = synchronized(lock) { memCache.clear() }

    // ================================================================ перевод

    /** Сейчас Wi‑Fi (или кабель) — для скачивания языков «только по Wi‑Fi». */
    fun onWifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    } catch (_: Exception) {
        true
    }

    /** Можно ли сейчас перевести без интернета (оба языка скачаны). */
    fun offlineReady(source: String, target: String): Boolean {
        val o = offline
        return o.available && o.supports(source) && o.supports(target) &&
            o.isDownloaded(source) && o.isDownloaded(target)
    }

    /** Каких языков не хватает для перевода без интернета. */
    fun missingModels(source: String, target: String): List<String> {
        val o = offline
        return listOf(source, target).distinct().filter { o.supports(it) && !o.isDownloaded(it) }
    }

    /**
     * Синхронный перевод — только из фонового потока. [source] — язык, если известен точно
     * (например, по раскладке), иначе определяется. [persist] — сохранить в кэше на диске.
     */
    fun translate(
        ctx: Context?, text: String, target: String, mode: Mode,
        source: String? = null, persist: Boolean = true
    ): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result("", source ?: target, "")
        synchronized(lock) {
            loadDisk(ctx)
            memCache[cacheKey(t, target)]
        }?.let { return it }
        val det = if (source != null) Detected(source, true) else detect(t)
        val input = if (det.latin) ReverseTranslit.convertText(t, keepEnglish = true) else t
        if (det.reliable && det.lang == target) return Result(input, target, "")
        val r = when (mode) {
            Mode.OFFLINE -> offlineOrFail(input, det.lang, target)
            Mode.ONLINE -> onlineOrFail(input, if (det.reliable) det.lang else null, target)
            Mode.AUTO -> {
                val viaPhone = if (det.reliable && offlineReady(det.lang, target)) {
                    offline.translate(input, det.lang, target)?.let { Result(it, det.lang, ENGINE_OFFLINE) }
                } else null
                viaPhone ?: onlineOrFail(input, if (det.reliable) det.lang else null, target).let { on ->
                    val src = on.source.ifEmpty { det.lang }
                    val o = offline
                    if (o.available && o.supports(src) && o.supports(target)) on.copy(offlineMissing = missingModels(src, target))
                    else on
                }
            }
        }
        putCache(ctx, t, target, r, persist)
        return r
    }

    private fun offlineOrFail(text: String, source: String, target: String): Result {
        val o = offline
        if (!o.available) throw Failure("в этой сборке нет перевода без интернета")
        for (l in listOf(source, target)) {
            if (!o.supports(l)) throw Failure("${name(l)} без интернета не переводится")
        }
        val missing = missingModels(source, target)
        if (missing.isNotEmpty()) throw Failure("скачайте " + missing.joinToString(", ") { name(it) }, missing)
        val out = o.translate(text, source, target) ?: throw Failure("не удалось перевести без интернета")
        return Result(out, source, ENGINE_OFFLINE)
    }

    private fun onlineOrFail(text: String, source: String?, target: String): Result =
        online(text, source, target) ?: throw Failure("нет связи с сервисом перевода")

    // ================================================================ язык текста

    /** Перевес слов, после которого латиница точно транслит (или точно не он). */
    private const val TRANSLIT_SURE = 2.0

    /**
     * Язык текста: на телефоне (ML Kit) или по буквам; русский латиницей узнаётся ещё и по словарям.
     * [preferTranslit] — латиницу, про которую неясно, считать транслитом (так пишет сам пользователь).
     */
    fun detect(text: String, preferTranslit: Boolean = false): Detected {
        val score = translitScore(text)
        if (score != null && score >= TRANSLIT_SURE) return Detected("ru", true, latin = true)
        var phone: Detected? = null
        val o = offline
        if (o.available) {
            val tag = o.detect(text)
            if (tag != null) {
                val parts = tag.split('-')
                val lang = when (val l = parts[0].lowercase()) {
                    "iw" -> "he"
                    "in" -> "id"
                    "fil" -> "tl"
                    else -> l
                }
                val latin = parts.size > 1 && parts[1].equals("Latn", ignoreCase = true)
                phone = when {
                    !latin || lang == "en" -> Detected(lang, true)
                    lang == "ru" -> Detected("ru", true, latin = true) // транслит: privet → привет
                    else -> Detected(lang, false)
                }
            }
        }
        if (phone != null && phone.reliable) return phone
        if (preferTranslit && score != null && score > -TRANSLIT_SURE) return Detected("ru", true, latin = true)
        return phone ?: guessLanguage(text)
    }

    private val LATIN_WORD = Regex("[A-Za-z]+(?:['’][A-Za-z]*)*")

    /**
     * Латиница без особых букв — русский транслитом («privet, kak dela») или английский
     * (а то и немецкий)? Сумма [ReverseTranslit.vote] по словам: > 0 — похоже на транслит,
     * < 0 — нет, 0 — непонятно. null — в тексте не только латиница ASCII (кириллица, «ü», «ł»…).
     */
    fun translitScore(text: String, maxWords: Int = 40): Double? {
        var latin = false
        for (c in text) {
            if (c in 'a'..'z' || c in 'A'..'Z') latin = true
            else if (c.isLetter()) return null
        }
        if (!latin) return null
        if (!SuggestionEngine.isLoaded("ru") || !SuggestionEngine.isLoaded("en")) return 0.0
        var score = 0.0
        var n = 0
        for (m in LATIN_WORD.findAll(text)) {
            val w = m.value
            if (w.length < 2) continue
            if (++n > maxWords) break
            score += ReverseTranslit.vote(w).score
        }
        return score
    }

    /** Язык по буквам: письменность, а в кириллице и латинице — характерные буквы. */
    fun guessLanguage(text: String): Detected {
        var cyr = 0
        var lat = 0
        val seen = HashSet<Char>()
        for (c in text) {
            val lc = c.lowercaseChar()
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.CYRILLIC -> { cyr++; seen.add(lc) }
                Character.UnicodeScript.LATIN -> { lat++; seen.add(lc) }
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return Detected("ja", true)
                Character.UnicodeScript.HANGUL -> return Detected("ko", true)
                Character.UnicodeScript.ARABIC -> return Detected("ar", true)
                Character.UnicodeScript.HEBREW -> return Detected("he", true)
                Character.UnicodeScript.DEVANAGARI -> return Detected("hi", true)
                Character.UnicodeScript.THAI -> return Detected("th", true)
                Character.UnicodeScript.GREEK -> return Detected("el", true)
                Character.UnicodeScript.GEORGIAN -> return Detected("ka", true)
                else -> Unit
            }
        }
        if (cyr == 0 && lat == 0) {
            if (text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return Detected("zh", true)
            return Detected("en", false)
        }
        fun has(chars: String) = chars.any { it in seen }
        if (cyr >= lat) {
            return when {
                has("әғқңөұүһ") -> Detected("kk", true) // в казахском есть и «і»
                has("ў") -> Detected("be", true)        // и в белорусском
                has("іїєґ") -> Detected("uk", true)
                else -> Detected("ru", true)
            }
        }
        val lang = when {
            has("ąęłśźżćń") -> "pl"
            has("ğşı") -> "tr"
            has("ășşțţ") && !has("ğı") -> "ro"
            has("čřěůšž") -> "cs"
            has("őű") -> "hu"
            has("ãõ") -> "pt"
            has("ñ¿¡") -> "es"
            has("äöüß") -> "de"
            has("åø") -> "sv"
            has("éèêàçëîôûœ") -> "fr"
            else -> "en"
        }
        // латиница без особых букв — английский лишь предположительно (или транслит, или немецкий без умлаутов)
        return Detected(lang, lang != "en")
    }

    // ================================================================ онлайн

    private fun http(url: URL, post: String? = null, timeoutMs: Int = 8000): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
        try {
            if (post != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun onlineTranslate(text: String, source: String?, target: String): Result? =
        tryGoogle(text, source, target) ?: tryMyMemory(text, source, target)

    /** Ответ Google: [[["перевод","оригинал",…],…],null,"ru",…] → перевод и язык оригинала. */
    fun parseGoogle(body: String): Pair<String, String?>? {
        val arr = JSONArray(body)
        val segs = arr.optJSONArray(0) ?: return null
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.optJSONArray(i) ?: continue
            if (seg.isNull(0)) continue
            sb.append(seg.optString(0))
        }
        if (sb.isEmpty()) return null
        val src = if (arr.length() > 2 && !arr.isNull(2)) arr.optString(2).ifEmpty { null } else null
        return sb.toString() to src
    }

    private fun tryGoogle(text: String, source: String?, target: String): Result? = try {
        val body = http(
            URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=${source ?: "auto"}&tl=$target&dt=t"),
            post = "q=" + URLEncoder.encode(text, "UTF-8")
        )
        parseGoogle(body)?.let { (out, src) -> Result(out, src ?: source ?: "", ENGINE_GOOGLE) }
    } catch (_: Exception) {
        null
    }

    private fun tryMyMemory(text: String, source: String?, target: String): Result? = try {
        if (text.length > 500) null
        else {
            val src = source ?: guessLanguage(text).lang
            val body = http(
                URL(
                    "https://api.mymemory.translated.net/get?q=" +
                        URLEncoder.encode(text, "UTF-8") + "&langpair=$src|$target"
                )
            )
            JSONObject(body).getJSONObject("responseData").optString("translatedText")
                .ifEmpty { null }?.let { Result(it, src, ENGINE_MYMEMORY) }
        }
    } catch (_: Exception) {
        null
    }

    // ================================================================ в фоне

    /** Перевести в фоне; [done] вызывается в главном потоке. */
    fun translateAsync(
        ctx: Context?, text: String, target: String, mode: Mode, source: String? = null,
        persist: Boolean = true, done: (Result?, Exception?) -> Unit
    ) {
        runner(Runnable {
            val (r, e) = try {
                translate(ctx, text, target, mode, source, persist) to null
            } catch (e: Exception) {
                null to e
            }
            main.post { done(r, e) }
        })
    }

    /** Определить язык в фоне; [done] — в главном потоке. */
    /** Язык текста в фоне; [done] получает и текст для перевода (транслит — уже кириллицей). */
    fun detectAsync(text: String, preferTranslit: Boolean = false, done: (Detected, String) -> Unit) {
        runner(Runnable {
            var input = text
            val d = try {
                detect(text, preferTranslit).also {
                    if (it.latin) input = ReverseTranslit.convertText(text, keepEnglish = true)
                }
            } catch (_: Exception) {
                guessLanguage(text)
            }
            main.post { done(d, input) }
        })
    }

    /** Где выполнять долгие дела — скачивание моделей (в тестах — сразу). */
    @Volatile
    var slowRunner: (Runnable) -> Unit = { Thread(it, "translate-models").apply { isDaemon = true }.start() }

    /** Выполнить долгое дело в фоне и вернуть результат в главный поток. */
    fun <T> background(work: () -> T, done: (T) -> Unit) {
        slowRunner(Runnable {
            val v = work()
            main.post { done(v) }
        })
    }
}
