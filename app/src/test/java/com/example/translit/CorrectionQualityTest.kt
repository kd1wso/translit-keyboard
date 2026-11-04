package com.example.translit

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Качество автозамены: частые опечатки исправляются, разговорные слова — нет. */
class CorrectionQualityTest {

    private val prRu = KeyboardLayoutManager.proximity(KeyboardLayoutManager.RU_LETTERS)
    private val prEn = KeyboardLayoutManager.proximity(KeyboardLayoutManager.EN_LETTERS)

    @Before
    fun setUp() {
        Dicts.ensure()
        UserHistory.resetInMemory()
    }

    private fun fix(lang: String, w: String): String? =
        SuggestionEngine.correction(lang, WordDictionary.normalize(w), if (lang == "ru") prRu else prEn, false)

    private fun check(lang: String, cases: String) {
        val wrong = ArrayList<String>()
        for (pair in cases.trim().split(Regex("\\s*,\\s*"))) {
            val (typo, want) = pair.split("→").map { it.trim() }
            val got = fix(lang, typo) ?: "∅"
            if (got != want) wrong.add("$typo→$got (ожидалось $want)")
        }
        assertEquals(wrong.joinToString(), 0, wrong.size)
    }

    @Test
    fun russianTypos() = check(
        "ru",
        """превет→привет, спосибо→спасибо, сегодя→сегодня, компютер→компьютер, пожалуйсто→пожалуйста,
        извени→извини, харашо→хорошо, хорошл→хорошо, привте→привет, сдлеать→сделать, сделвть→сделать,
        чтото→что-то, кудато→куда-то, зделать→сделать, ещо→ещё, будующий→будущий, симпатишный→симпатичный,
        здраствуйте→здравствуйте, вобщем→в общем, впринципе→в принципе, извинити→извините,
        пожайлуста→пожалуйста, скаждым→с каждым, досвидания→до свидания, оден→один, малако→молоко,
        сабака→собака, тоесть→то есть, незнаю→не знаю"""
    )

    @Test
    fun englishTypos() = check(
        "en",
        """teh→the, dont→don't, im→I'm, becuase→because, recieve→receive, thier→their, wierd→weird,
        definately→definitely, seperate→separate, occured→occurred, untill→until, tommorow→tomorrow,
        alot→a lot, wich→which, realy→really, beleive→believe, freind→friend, goverment→government,
        thats→that's, youre→you're, isnt→isn't, didnt→didn't, wouldnt→wouldn't, hte→the, adn→and,
        waht→what, jsut→just, whcih→which, taht→that, i→∅"""
    )

    @Test
    fun realAndSlangWordsKept() {
        val ru = """прив норм спс пжл щас чё ок лол кек имхо плиз оч мб хз крч кста ваще чел кринж рофл сорян
            ютуб телега инста вотсап тикток зум гугл дедлайн созвон лайк сторис мем жиза топчик пасиб окей
            кот сон дама папка щи мама жена женя одна понятненько котики солнышко малыш"""
        val en = """lol omg btw idk imo tbh brb gonna wanna gotta kinda yeah yep nope okay ok hmm haha lmao bruh
            dude covid zoom youtube google iphone wifi app emoji selfie hashtag tiktok netflix crypto meme gif
            vibe sus cringe yeet gotcha tho thru ur kk np ty thx pls plz cuz bday cat hat bed cup bag can ten"""
        val changed = ArrayList<String>()
        for ((lang, list) in listOf("ru" to ru, "en" to en)) {
            for (w in list.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val c = fix(lang, w)
                if (c != null) changed.add("$w→$c")
            }
        }
        assertEquals(changed.joinToString(), 0, changed.size)
    }
}
