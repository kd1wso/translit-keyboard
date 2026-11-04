#!/usr/bin/env python3
"""
Собирает словари подсказок app/src/main/assets/dict/{ru,en}.tsv из частотных
списков FrequencyWords (OpenSubtitles 2018, лицензия CC BY-SA 4.0):
https://github.com/hermitdave/FrequencyWords

Запуск:  python3 tools/build_dicts.py [папка с ru_50k.txt и en_50k.txt]
Без аргумента списки скачиваются с GitHub.

Формат строки:  ключ<TAB>частота[<TAB>написание[<TAB>b]]
  ключ      — слово в нижнем регистре, «ё» заменена на «е»; строки отсортированы по ключу
  написание — если отличается от ключа (I'm, ещё); может быть пустым
  b         — грубое слово: не предлагается в подсказках, но и не «исправляется»
"""
import os
import re
import sys
import urllib.request

SRC = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{0}/{0}_50k.txt"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "dict")

RU_WORD = re.compile(r"^[а-яё]+(?:-[а-яё]+)*$")
EN_WORD = re.compile(r"^[a-z]+(?:['-][a-z]+)*$")
RU_SINGLE = set("абвжиксоуя")
EN_SINGLE = {"a", "i"}

RU_BLOCK = re.compile("|".join([
    r"^(?:на|по|о|от|ни|за|до|рас|раз|при|у|вы|с|съ|недо|пере|под|об|обо|про)?ху[йеёияю]",
    r"пизд",
    r"^[её]б",
    r"(?:за|на|по|у|вы|до|про|пере|при|недо|разъ|отъ|съ|въ|объ|подъ|изъ|долбо)[её]б",
    r"^бля$", r"^блят", r"^бляд",
    r"^муд(?:ак|ач|ил|оз)",
    r"^пид[ао]?р", r"^педик(?:и|а|у|ом|ов|ам|ами|ах)?$", r"^педераст",
    r"залуп",
    r"^г[ао]ндон",
    r"^сук(?:а|и|е|у|ой|ам|ами|ах)$",
    r"^суч(?:ка|ки|ке|ку|кой|ара|ий)$",
    r"^шлюх",
    r"^манд(?:а|ы|е|у|ой)$", r"^мандав",
]))
EN_BLOCK = re.compile("|".join([
    r"^(?:fuck|motherf|shit|bullshit|cunt|nigg|asshole|bitch|whore|slut|dickhead|cocksuck|"
    r"bastard|twat|wank|retard)",
    r"^fag(?:s|got|gots|gy)?$",
    r"^puss(?:y|ies)$",
]))

# Сокращения: в исходном списке они разбиты на части (don + 't), восстанавливаем.
# Значение — либо «осколок», из которого берём частоту, либо примерная частота.
EN_CONTRACTIONS = {
    "don't": "don", "doesn't": "doesn", "didn't": "didn", "isn't": "isn", "wasn't": "wasn",
    "aren't": "aren", "couldn't": "couldn", "wouldn't": "wouldn", "shouldn't": "shouldn",
    "haven't": "haven", "hasn't": "hasn", "weren't": "weren", "ain't": "ain",
    "i'm": 4300000, "it's": 3000000, "that's": 1500000, "you're": 1300000, "can't": 1200000,
    "i'll": 1100000, "i've": 800000, "what's": 700000, "he's": 700000, "let's": 600000,
    "we're": 600000, "there's": 500000, "she's": 500000, "won't": 500000, "they're": 400000,
    "i'd": 400000, "you'll": 300000, "we'll": 250000, "you've": 200000, "here's": 150000,
    "who's": 150000, "where's": 120000, "you'd": 100000, "we've": 100000, "how's": 80000,
    "he'll": 80000, "it'll": 60000, "they've": 60000, "she'll": 40000, "that'll": 40000,
    "they'll": 60000, "we'd": 50000, "he'd": 50000, "she'd": 30000, "they'd": 30000,
    "y'all": 20000, "ma'am": 50000, "o'clock": 22000,
}
# Слова без апострофа, которые на самом деле — сокращения (их будет исправлять автозамена)
EN_DROP = {
    "dont", "doesnt", "didnt", "isnt", "wasnt", "arent", "couldnt", "wouldnt", "shouldnt",
    "havent", "hasnt", "werent", "aint", "im", "thats", "youre", "cant", "ive", "whats",
    "hes", "theres", "wont", "theyre", "youll", "youve", "heres", "whos", "wheres", "youd",
    "weve", "hows", "itll", "theyve", "thatll", "theyll", "hed", "theyd", "yall",
    "doesn", "didn", "isn", "wasn", "aren", "couldn", "wouldn", "shouldn", "haven", "hasn",
    "weren", "ain", "ll", "re", "ve",
}


# Разговорные слова и сокращения из переписки, которых мало в субтитрах: без них автозамена
# «исправляет» «спс» и «thx». Добавляются с умеренной частотой, если их нет в списке.
RU_EXTRA = """
прив приветик приветики норм нормас нормально спс пасиб пасиба пасибо спасибки пжл пж плиз щас ща чё шо
ок окей оке лан ладн кек лол ору ржу ржака ахах ахаха ахахах хаха хахах хахаха имхо оч мб хз крч кста ваще
чел чела челик кринж рофл сорян сорри дарова здарова хай покеда споки бро сис братан братишка гоу изи жиза
годнота топчик имба хайп вайб краш токсик абьюз стрим стример стримы стримит донат мем мемы мемас мемасик
лс личку личке инст инста инсту инсте телега телегу телеге тг вк вотсап вотсапе ватсап тикток тиктоке
ютуб ютубе гугл гугле загуглить погуглить гуглить зум зуме созвон созвона созвонимся созвониться дедлайн
дедлайна дедлайны апдейт фидбэк фидбек митап коворкинг лайк лайки лайкнуть репост репостнуть сторис
селфи смайлик смайлики смайл эмодзи стикер стикеры гифка вайфай блютуз смартфон айфон андроид ноут ноуте
комп компе приложуха прога проги баг баги фича фичи юзер юзать чекнуть скинь скинуть скину скриншот скрин
скрины видос видосы видосик днюха днюху днюхой мск спб
"""
EN_EXTRA = """
lol lmao lmfao rofl omg omfg btw idk idc imo imho tbh brb afk asap fyi irl smh ngl nvm np ty thx pls plz ppl
msg msgs txt bc cuz cos coz bday gonna wanna gotta kinda sorta dunno lemme gimme ya yeah yep yup nope nah
okay ok kk hmm hmmm haha hahaha hehe bruh dude covid zoom youtube google googled iphone android wifi app
apps emoji emojis selfie selfies hashtag tweet tweets retweet instagram insta tiktok netflix uber crypto
bitcoin blockchain podcast podcasts vlog blog blogger meme memes gif gifs lit vibe vibes vibing sus cringe
cringey yeet stan simp rizz slay lowkey highkey bestie fomo yolo gotcha tho thru nite luv ur xoxo xd ikr
wyd hbu wbu gtg ttyl tmr tmrw ofc obv obvs def prob probs rn atm jk ily omw otw eta faq diy ai ui api url
pdf usb gps tv pc email emails online offline login logout website smartphone laptop
"""
EXTRA_COUNT = {"ru": 1500, "en": 5000}


def read_fixes(lang):
    """Опечатки из assets/dict/<lang>.fixes.tsv: их нет в словаре, автозамена берёт исправление из таблицы."""
    path = os.path.join(OUT, f"{lang}.fixes.tsv")
    out = set()
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("#") or "\t" not in line:
                    continue
                out.add(line.split("\t", 1)[0].strip().replace("ё", "е"))
    return out


def read_list(lang, folder):
    if folder:
        with open(os.path.join(folder, f"{lang}_50k.txt"), encoding="utf-8") as f:
            text = f.read()
    else:
        with urllib.request.urlopen(SRC.format(lang)) as r:
            text = r.read().decode("utf-8")
    for line in text.splitlines():
        parts = line.strip().split(" ")
        if len(parts) == 2 and parts[1].isdigit():
            yield parts[0].strip().lower(), int(parts[1])


def build(lang, folder):
    raw = list(read_list(lang, folder))
    counts = dict(raw)
    entries = {}  # key -> [count, {display: count}]

    def add(word, count):
        key = word.replace("ё", "е")
        e = entries.setdefault(key, [0, {}])
        e[0] += count
        e[1][word] = e[1].get(word, 0) + count

    for word, count in raw:
        if lang == "ru":
            if not RU_WORD.match(word) or (len(word) == 1 and word not in RU_SINGLE):
                continue
        else:
            if not EN_WORD.match(word) or (len(word) == 1 and word not in EN_SINGLE):
                continue
            if word in EN_DROP:
                continue
            if word == "don":
                count = min(count, 2000)  # почти весь «don» — это don't
        add(word, count)

    if lang == "en":
        for word, src in EN_CONTRACTIONS.items():
            count = counts.get(src, 0) if isinstance(src, str) else src
            if count > 0:
                add(word, count)

    for typo in read_fixes(lang):
        entries.pop(typo, None)

    for word in (RU_EXTRA if lang == "ru" else EN_EXTRA).split():
        if word.replace("ё", "е") not in entries:
            add(word, EXTRA_COUNT[lang])

    block = RU_BLOCK if lang == "ru" else EN_BLOCK
    lines = []
    blocked = 0
    for key in sorted(entries):
        total, variants = entries[key]
        display = max(variants.items(), key=lambda kv: kv[1])[0]
        if lang == "en":
            if key == "i" or key.startswith("i'"):
                display = "I" + display[1:]
        is_blocked = bool(block.search(key))
        blocked += is_blocked
        row = [key, str(total)]
        if display != key or is_blocked:
            row.append(display if display != key else "")
        if is_blocked:
            row.append("b")
        lines.append("\t".join(row))
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{lang}.tsv")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{lang}: {len(lines)} слов, из них грубых {blocked} -> {os.path.relpath(path)}")


if __name__ == "__main__":
    folder = sys.argv[1] if len(sys.argv) > 1 else None
    for lang in ("ru", "en"):
        build(lang, folder)
