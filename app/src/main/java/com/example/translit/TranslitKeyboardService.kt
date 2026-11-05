package com.example.translit

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.translit.KeyboardLayoutManager.Page
import java.io.File
import java.util.concurrent.Executors

class TranslitKeyboardService : InputMethodService(), KeyboardView.Listener {

    enum class Layout { TRANSLIT, CYRILLIC, LATIN, REVERSE, CUSTOM }

    private lateinit var st: KbSettings.State
    private lateinit var pal: KbSettings.Palette
    /** Ввод в поле приложения. */
    private lateinit var mainEngine: InputEngine
    /** Режим «Набор» с переводом: клавиши идут в строку панели перевода. */
    private var live: LiveTranslation? = null
    /** Кому идут клавиши: строке перевода или полю. */
    private val engine: InputEngine get() = live?.engine ?: mainEngine

    private var layout = Layout.TRANSLIT
    private var lastDefaultLayout: String? = null
    private var customLayoutIndex = 0
    /** Раскладка до автоматического переключения (исправление раскладки) — для отмены. */
    private var layoutBeforeFix: Pair<Layout, Int>? = null
    /** Приложение, в котором сейчас печатают (для раскладки «своей для каждого приложения»). */
    private var currentPackage: String? = null
    private var page = Page.LETTERS
    private var profile = FieldProfile.TEXT
    private var lettersDef = KeyboardLayoutManager.RU_LETTERS
    private val proximityCache = HashMap<String, Map<Char, Set<Char>>>()

    private var root: FrameLayout? = null
    private var column: LinearLayout? = null
    private var strip: SuggestionStrip? = null
    private var keyboard: KeyboardView? = null
    private var overlay: PopupOverlay? = null
    private var panel: TranslatePanel? = null
    /** Язык текущей раскладки (ru, en…). */
    private var layoutLang = "ru"

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null

    /** Поле ввода для тестов (на телефоне — поле приложения от системы). */
    internal var testInputConnection: InputConnection? = null

    override fun getCurrentInputConnection(): InputConnection? =
        testInputConnection ?: super.getCurrentInputConnection()

    // ================================================================ жизненный цикл

    override fun onCreate() {
        super.onCreate()
        st = KbSettings.get(this)
        pal = KbSettings.palette(st)
        mainEngine = InputEngine(IcBridge())
        mainEngine.listener = { onEngineChanged() }
        mainEngine.onSwitchLanguage = { target -> switchForFix(target) }
        mainEngine.onTranslateRequest = { text -> haptic(null); translateText(text, Source.SELECTION) }
        mainEngine.onClipboardTranslate = { haptic(null); translateClipboard() }
        clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboard?.addPrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
        layout = layoutFromName(st.layoutName)
        lastDefaultLayout = st.layoutName
        applySettingsToEngine()
        applyLayoutToEngine()
        executor.execute {
            UserHistory.load(File(filesDir, "user_history.tsv"))
            SuggestionEngine.loadAssets(this)
            SuggestionEngine.loadExtraWords(this, force = true)
            handler.post { engine.refresh() }
        }
    }

    override fun onDestroy() {
        try {
            clipboard?.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
        live?.close()
        live = null
        UserHistory.saveIfDirty()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val frame = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = SuggestionStrip(
            this,
            onSettings = { haptic(null); openSettings() },
            onTranslate = { haptic(null); toggleTranslate() },
            onPick = { i -> haptic(null); engine.pickChip(i) }
        )
        val kv = KeyboardView(this)
        val ov = PopupOverlay(this)
        kv.listener = this
        kv.popup = ov
        val padH = dp(3f).toInt()
        kv.setPadding(padH, dp(2f).toInt(), padH, dp(4f).toInt())
        col.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()))
        col.addView(kv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        frame.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.setPadding(0, 0, 0, navBarHeight())
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            col.setPadding(0, 0, 0, bottom)
            insets
        }
        ViewCompat.requestApplyInsets(frame)
        root = frame
        column = col
        strip = s
        keyboard = kv
        overlay = ov
        live?.close()
        live = null
        oneShot = null
        panel = null
        applyTheme()
        rebuildKeys()
        onEngineChanged()
        return frame
    }

    private fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val info = attribute ?: EditorInfo()
        profile = profileFor(info)
        mainEngine.startInput(profile, info.initialSelStart, info.initialSelEnd)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        st = KbSettings.get(this)
        pal = KbSettings.palette(st)
        val pkg = info?.packageName
        currentPackage = pkg
        val saved = if (st.perAppLayout && pkg != null) KbSettings.appLayout(this, pkg) else null
        when {
            saved != null -> setLayoutByName(saved)
            st.perAppLayout || st.layoutName != lastDefaultLayout -> setLayoutByName(st.layoutName)
        }
        lastDefaultLayout = st.layoutName
        layoutBeforeFix = null
        page = when (profile.kind) {
            FieldProfile.Kind.NUMBER, FieldProfile.Kind.DATETIME -> Page.NUMBER
            FieldProfile.Kind.PHONE -> Page.PHONE
            else -> Page.LETTERS
        }
        // то же поле заново (приложение очистило его после отправки) — панель перевода остаётся
        if (restarting && panel != null && profile.kind == FieldProfile.Kind.TEXT) live?.onFieldUpdate()
        else closeTranslate()
        applySettingsToEngine()
        applyLayoutToEngine()
        applyTheme()
        rebuildKeys()
        onEngineChanged()
        maybeOfferClip()
        executor.execute { SuggestionEngine.loadExtraWords(this) }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        mainEngine.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        live?.onFieldUpdate()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        closeTranslate()
        mainEngine.finishComposing()
        keyboard?.cancelTouches()
        super.onFinishInputView(finishingInput)
        executor.execute { UserHistory.saveIfDirty() }
    }

    private fun layoutFromName(name: String): Layout {
        val l = try { Layout.valueOf(name) } catch (_: Exception) { Layout.TRANSLIT }
        return if (l == Layout.CUSTOM) Layout.TRANSLIT else l
    }

    /** «TRANSLIT», «LATIN»… или «CUSTOM:id» для своей раскладки. */
    private fun setLayoutByName(name: String) {
        if (name.startsWith("CUSTOM:")) {
            val i = KeyboardLayoutManager.customLayouts(this).indexOfFirst { it.id == name.substring(7) }
            if (i >= 0) {
                layout = Layout.CUSTOM
                customLayoutIndex = i
                return
            }
            layout = layoutFromName(st.layoutName)
            return
        }
        layout = layoutFromName(name)
    }

    private fun layoutName(): String =
        if (layout == Layout.CUSTOM) "CUSTOM:" + lettersDef.id else layout.name

    /** Запомнить раскладку для текущего приложения. */
    private fun rememberLayout() {
        if (!st.perAppLayout) return
        val pkg = currentPackage ?: return
        KbSettings.setAppLayout(this, pkg, layoutName())
    }

    // ================================================================ поле ввода

    private fun profileFor(info: EditorInfo): FieldProfile {
        val t = info.inputType
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        val flags = t and InputType.TYPE_MASK_FLAGS
        val opts = info.imeOptions
        val noLearning = (opts and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val enterAction = if ((opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) 0 else action
        val multiLine = (flags and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)) != 0
        fun plain(kind: FieldProfile.Kind) = FieldProfile(kind, false, false, false, 0, false, enterAction, false)
        return when (cls) {
            InputType.TYPE_CLASS_NUMBER -> plain(FieldProfile.Kind.NUMBER)
            InputType.TYPE_CLASS_PHONE -> plain(FieldProfile.Kind.PHONE)
            InputType.TYPE_CLASS_DATETIME -> plain(FieldProfile.Kind.DATETIME)
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ->
                    FieldProfile(FieldProfile.Kind.PASSWORD, false, false, false, 0, false, enterAction, multiLine)
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ->
                    FieldProfile(FieldProfile.Kind.EMAIL, false, false, false, 0, false, enterAction, multiLine)
                InputType.TYPE_TEXT_VARIATION_URI ->
                    FieldProfile(FieldProfile.Kind.URI, false, false, false, 0, false, enterAction, multiLine)
                else -> {
                    val noSuggestions = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
                    val noAutocorrect = noSuggestions ||
                        variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
                        variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME ||
                        variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
                    val caps = t and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                    FieldProfile(
                        FieldProfile.Kind.TEXT,
                        suggestions = !noSuggestions,
                        autocorrect = !noAutocorrect,
                        learning = !noLearning && !noSuggestions,
                        capsModes = caps,
                        composing = !noSuggestions,
                        enterAction = enterAction,
                        multiLine = multiLine
                    )
                }
            }
            else -> FieldProfile(FieldProfile.Kind.RAW, false, false, false, 0, false, 0, false)
        }
    }

    private inner class IcBridge : EditorBridge {
        private val ic get() = currentInputConnection
        override fun textBeforeCursor(n: Int): CharSequence? = ic?.getTextBeforeCursor(n, 0)
        override fun textAfterCursor(n: Int): CharSequence? = ic?.getTextAfterCursor(n, 0)
        override fun setComposingText(text: CharSequence) { ic?.setComposingText(text, 1) }
        override fun finishComposingText() { ic?.finishComposingText() }
        override fun commitText(text: CharSequence) { ic?.commitText(text, 1) }
        override fun deleteBefore(n: Int) { ic?.deleteSurroundingText(n, 0) }
        override fun setSelection(start: Int, end: Int) { ic?.setSelection(start, end) }
        override fun sendKey(keyCode: Int) { if (ic != null) sendDownUpKeyEvents(keyCode) }
        override fun performEditorAction(action: Int) { ic?.performEditorAction(action) }
        override fun cursorCapsMode(reqModes: Int): Int = ic?.getCursorCapsMode(reqModes) ?: 0
        override fun beginBatch() { ic?.beginBatchEdit() }
        override fun endBatch() { ic?.endBatchEdit() }
        override fun selectedText(): CharSequence? = ic?.getSelectedText(0)
    }

    // ================================================================ раскладки

    private fun applySettingsToEngine() {
        val es = EngineSettings(
            autocorrect = st.autocorrect,
            suggestions = st.suggestionsEnabled,
            learn = st.learnWords,
            autoCaps = st.autoCaps,
            doubleSpacePeriod = st.doubleSpacePeriod,
            layoutFix = st.layoutFix
        )
        TranslitMode.customTable = TranslitMode.parseCustom(st.customTable)
        for (e in listOfNotNull(mainEngine, live?.engine)) {
            e.settings = es
            e.actionTranslit = st.translitMode
        }
    }

    private fun ruLettersDef(): KeyboardLayoutManager.LayoutDef =
        if (st.ruLayout == KbSettings.RU_YAVERTY) KeyboardLayoutManager.RU_PHONETIC else KeyboardLayoutManager.RU_LETTERS

    private fun currentLettersDef(): KeyboardLayoutManager.LayoutDef = when (layout) {
        Layout.TRANSLIT, Layout.CYRILLIC -> ruLettersDef()
        Layout.LATIN, Layout.REVERSE -> KeyboardLayoutManager.EN_LETTERS
        Layout.CUSTOM -> KeyboardLayoutManager.customLayouts(this).getOrNull(customLayoutIndex)
            ?: KeyboardLayoutManager.RU_LETTERS
    }

    /** Куда переключаться, когда слово оказалось набрано по-русски в английской раскладке. */
    private fun russianLayout(): Layout =
        if (st.cycleCyrillic || !st.cycleTranslit) Layout.CYRILLIC else Layout.TRANSLIT

    private fun applyLayoutToEngine() {
        if (layout == Layout.CUSTOM && KeyboardLayoutManager.customLayouts(this).getOrNull(customLayoutIndex) == null) {
            layout = Layout.TRANSLIT
        }
        val def = currentLettersDef()
        lettersDef = def
        val lang = when (layout) {
            Layout.TRANSLIT, Layout.CYRILLIC, Layout.REVERSE -> "ru"
            Layout.LATIN -> "en"
            Layout.CUSTOM -> KeyboardLayoutManager.languageOf(def)
        }
        val prox = proximityCache.getOrPut(def.id + "/" + def.rows.hashCode()) { KeyboardLayoutManager.proximity(def) }
        val yaverty = st.ruLayout == KbSettings.RU_YAVERTY
        // латиница → русский — только по ЙЦУКЕН (в ЯВЕРТЫ «privet» — это и есть «привет» транслитом)
        val fixMode = when (layout) {
            Layout.TRANSLIT, Layout.CYRILLIC -> if (yaverty) 2 else 1
            Layout.LATIN, Layout.REVERSE -> if (yaverty) 0 else 1
            Layout.CUSTOM -> 0
        }
        val langChanged = lang != layoutLang
        layoutLang = lang
        mainEngine.layoutFixMode = fixMode
        mainEngine.fixRuRender = if (russianLayout() == Layout.TRANSLIT) st.translitMode else null
        mainEngine.configure(
            lang, if (layout == Layout.TRANSLIT) st.translitMode else null, prox,
            reverse = layout == Layout.REVERSE
        )
        live?.let { l ->
            // в строке перевода — всегда кириллица (транслит не нужен: в поле уйдёт перевод)
            l.engine.layoutFixMode = fixMode
            l.engine.fixRuRender = null
            l.engine.configure(lang, null, prox, reverse = layout == Layout.REVERSE)
            if (langChanged) {
                l.retranslate()
                panel?.takeIf { it.mode == TranslatePanel.Mode.LIVE }?.showLive(liveTitle(), liveHint())
            }
        }
    }

    /** Исправление раскладки просит переключиться на язык слова (или вернуть прежнюю раскладку). */
    private fun switchForFix(target: String) {
        if (target == InputEngine.SWITCH_BACK) {
            val back = layoutBeforeFix ?: return
            layoutBeforeFix = null
            layout = back.first
            customLayoutIndex = back.second
        } else {
            layoutBeforeFix = layout to customLayoutIndex
            layout = if (target == "en") Layout.LATIN else russianLayout()
        }
        applyLayoutToEngine()
        rebuildKeys()
        onEngineChanged()
        rememberLayout()
    }

    /** Раскладки на 🌐 по порядку: транслит, русская, свои, обратный транслит, английская. */
    private fun cycleOrder(): List<Pair<Layout, Int>> {
        val order = ArrayList<Pair<Layout, Int>>()
        if (st.cycleTranslit) order.add(Layout.TRANSLIT to 0)
        if (st.cycleCyrillic) order.add(Layout.CYRILLIC to 0)
        for (i in KeyboardLayoutManager.customLayouts(this).indices) order.add(Layout.CUSTOM to i)
        if (st.cycleReverse) order.add(Layout.REVERSE to 0)
        if (st.cycleLatin) order.add(Layout.LATIN to 0)
        if (order.isEmpty()) order.add(Layout.TRANSLIT to 0)
        return order
    }

    private fun cycleLayout() {
        val order = cycleOrder()
        val cur = order.indexOfFirst { it.first == layout && (layout != Layout.CUSTOM || it.second == customLayoutIndex) }
        val next = order[(cur + 1) % order.size]
        layout = next.first
        customLayoutIndex = next.second
        layoutBeforeFix = null
        applyLayoutToEngine()
        rebuildKeys()
        onEngineChanged()
        rememberLayout()
    }

    private fun spaceLabel(): String = if (live != null) {
        layoutLang.uppercase() + " → " + st.translateTarget.uppercase()
    } else when (layout) {
        Layout.TRANSLIT -> st.translitMode.spaceLabel
        Layout.CYRILLIC -> "русский"
        Layout.REVERSE -> "lat → рус"
        Layout.LATIN -> "english"
        Layout.CUSTOM -> lettersDef.name
    }

    private fun enterIcon(): Int = when (profile.enterAction) {
        EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
        EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
        EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_arrow_forward
        EditorInfo.IME_ACTION_DONE -> R.drawable.ic_done
        else -> R.drawable.ic_enter
    }

    private fun rebuildKeys() {
        val kv = keyboard ?: return
        val lettersLabel = if (KeyboardLayoutManager.languageOf(lettersDef) == "en") "ABC" else "АБВ"
        fun bottom() = KeyboardModels.bottomRow(
            KeyboardModels.BottomSpec(page, lettersLabel, profile.kind, enterIcon())
        )
        val rows = when (page) {
            Page.LETTERS -> KeyboardModels.letters(lettersDef, bottom())
            Page.SYMBOLS1, Page.SYMBOLS2 -> KeyboardModels.symbols(page, bottom())
            Page.EMOJI -> KeyboardModels.emoji(bottom())
            Page.NUMBER, Page.PHONE -> KeyboardModels.numpad(page, enterIcon())
        }
        kv.spaceLabel = spaceLabel()
        kv.setRows(rows)
        kv.rowHeight = rowHeightPx(rows.size)
    }

    private fun applyTheme() {
        window?.window?.let { SystemBars.style(it, pal.bg) }
        root?.setBackgroundColor(pal.bg)
        column?.setBackgroundColor(pal.bg)
        strip?.applyTheme(pal, st.fontSize)
        panel?.applyTheme(pal, st.fontSize)
        keyboard?.let {
            it.palette = pal
            it.fontScale = st.fontSize
            it.rowHeight = rowHeightPx(it.rows.size)
        }
    }

    /**
     * Высота ряда клавиш из настроек, но клавиатура целиком — не выше половины экрана
     * (важно в альбомной ориентации), чтобы переписка оставалась видна.
     */
    private fun rowHeightPx(rows: Int): Int {
        val want = dp(31f + 22f * st.keyHeight)
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val budget = screenH * 0.5f - dp(44f) - dp(6f)
        val cap = budget / maxOf(rows, 4)
        return minOf(want, cap).coerceAtLeast(dp(30f)).toInt()
    }

    /** Без полноэкранного режима ввода: приложение (чат) всегда видно над клавиатурой. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun onEngineChanged() {
        val e = engine
        keyboard?.shiftState = e.shift
        strip?.setChips(e.chips, showTranslate = profile.kind == FieldProfile.Kind.TEXT, translateActive = panel != null)
        val l = live ?: return
        val p = panel ?: return
        if (p.mode != TranslatePanel.Mode.LIVE) return
        val b = l.buffer
        p.setLiveText(b.text.toString(), b.selS, b.compS, b.compE)
        p.setLiveStatus(liveStatus(l), l.state == LiveTranslation.State.ERROR, l.missing)
    }

    // ================================================================ клавиши

    override fun onKeyPress(key: KeyboardView.Key) = haptic(key.type)

    override fun onKey(key: KeyboardView.Key) {
        when (key.type) {
            KeyboardView.Type.CHAR -> {
                val t = key.text
                if (t.length == 1 && t[0].isLetter()) engine.onLetter(t[0]) else engine.onText(t)
            }
            KeyboardView.Type.SPACE -> engine.onSpace()
            // в режиме перевода ⏎ вставляет перевод; когда строка пуста — обычный ⏎ (отправить)
            KeyboardView.Type.ENTER -> if (live?.commit() != true) mainEngine.onEnter()
            KeyboardView.Type.BACKSPACE -> editEngine().onBackspace()
            KeyboardView.Type.MODE -> {
                page = Page.values()[key.target]
                rebuildKeys()
            }
            KeyboardView.Type.EMOJI -> {
                page = if (page == Page.EMOJI) Page.LETTERS else Page.EMOJI
                rebuildKeys()
            }
            KeyboardView.Type.GLOBE -> cycleLayout()
            KeyboardView.Type.SHIFT -> Unit
        }
    }

    override fun onVariant(key: KeyboardView.Key, text: String) {
        haptic(null)
        engine.onText(text)
    }

    override fun onShiftDown() = engine.onShiftTap()
    override fun onShiftLongPress() = engine.onShiftLongPress()

    /** ⌫ и курсор: в строке перевода, пока в ней есть текст, иначе — в поле. */
    private fun editEngine(): InputEngine = live?.takeIf { !it.isEmpty }?.engine ?: mainEngine

    private var swipeEngine: InputEngine? = null

    override fun onBackspaceRepeat(count: Int) {
        val e = editEngine()
        if (count <= 20) e.onBackspace() else e.deleteWord()
    }

    override fun onBackspaceSwipeStart(): Boolean {
        val e = editEngine()
        swipeEngine = e
        return e.backspaceSwipeStart()
    }

    override fun onBackspaceSwipe(steps: Int) = (swipeEngine ?: editEngine()).backspaceSwipe(steps)

    override fun onBackspaceSwipeEnd() {
        (swipeEngine ?: editEngine()).backspaceSwipeEnd()
        swipeEngine = null
    }

    override fun onCursorSwipe(steps: Int) = editEngine().moveCursor(steps)

    override fun onSystemLongPress(key: KeyboardView.Key) {
        haptic(null)
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    override fun previewText(key: KeyboardView.Key): String? {
        if (key.type != KeyboardView.Type.CHAR) return null
        val l = key.label
        if (l.isEmpty() || l.length > 3) return null
        val upper = engine.upperCase
        if (layout == Layout.TRANSLIT && live == null && page == Page.LETTERS && l.length == 1 && l[0].isLetter()) {
            return st.translitMode.translit(l[0], upper, engine.shift == InputEngine.Shift.LOCKED).ifEmpty { l }
        }
        return if (upper && l.length == 1 && l[0].isLetter()) l.uppercase() else l
    }

    private fun openSettings() {
        closeTranslate()
        mainEngine.finishComposing()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        requestHideSelf(0)
    }

    // ================================================================ отклик

    private fun haptic(type: KeyboardView.Type?) {
        if (st.vibrate) {
            try {
                val v = vibrator ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(VIBRATOR_SERVICE) as Vibrator
                }).also { vibrator = it }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(12)
                }
            } catch (_: Exception) {
            }
        }
        if (st.sound) {
            try {
                val am = audio ?: (getSystemService(AUDIO_SERVICE) as AudioManager).also { audio = it }
                val fx = when (type) {
                    KeyboardView.Type.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                    KeyboardView.Type.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                    KeyboardView.Type.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                    else -> AudioManager.FX_KEYPRESS_STANDARD
                }
                am.playSoundEffect(fx, 0.4f)
            } catch (_: Exception) {
            }
        }
    }

    // ================================================================ перевод

    private enum class Source { SELECTION, FIELD, CLIPBOARD }

    /** Разовый перевод: выделенного, текста поля или скопированного. */
    private class OneShot(
        val source: Source,
        /** Текст как в поле — чтобы заменять, только если он не изменился. */
        val original: String,
        /** Что переводим (транслит уже переведён в кириллицу). */
        var input: String,
        val before: Int,
        val after: Int
    ) {
        var from: String? = null
        var target = "en"
        var result: TranslationManager.Result? = null
        var gen = 0
    }

    private var oneShot: OneShot? = null

    private val liveField = object : LiveTranslation.Field {
        override fun textBefore(n: Int): String? = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString()

        override fun replace(oldLen: Int, text: String, composing: Boolean) {
            val c = currentInputConnection ?: return
            c.beginBatchEdit()
            c.finishComposingText()
            if (oldLen > 0) c.deleteSurroundingText(oldLen, 0)
            if (text.isNotEmpty()) {
                if (composing) c.setComposingText(text, 1) else c.commitText(text, 1)
            }
            c.endBatchEdit()
        }

        override fun finishComposing() {
            currentInputConnection?.finishComposingText()
        }
    }

    private val liveScheduler = object : LiveTranslation.Scheduler {
        override fun post(delayMs: Long, r: Runnable) {
            handler.postDelayed(r, delayMs)
        }

        override fun cancel(r: Runnable) = handler.removeCallbacks(r)
    }

    private val panelHost = object : TranslatePanel.Host {
        override fun onClose() { haptic(null); closeTranslate() }
        override fun onLanguageButton() { haptic(null); toggleLanguagePicker() }
        override fun onPickLanguage(code: String) { haptic(null); pickLanguage(code) }
        override fun onLive() { haptic(null); startLive() }
        override fun onTranslateField() { haptic(null); translateField() }
        override fun onTranslateClipboard() { haptic(null); translateClipboard() }
        override fun onApply() { haptic(null); applyOneShot() }
        override fun onCopy() { haptic(null); copyOneShot() }
        override fun onDownload(langs: List<String>) { haptic(null); downloadModels(langs) }
    }

    private fun langName(code: String?): String = if (code.isNullOrEmpty()) "Авто" else TranslationManager.name(code)

    /** Значок перевода: выделен текст — перевести его, иначе — набор с переводом. */
    private fun toggleTranslate() {
        if (panel != null) {
            closeTranslate()
            return
        }
        val sel = currentInputConnection?.getSelectedText(0)?.toString()
        if (!sel.isNullOrBlank()) translateText(sel, Source.SELECTION) else startLive()
    }

    private fun openPanel(): TranslatePanel? {
        panel?.let { return it }
        val col = column ?: return null
        mainEngine.finishComposing()
        mainEngine.offerClipboard(false)
        val p = TranslatePanel(this, panelHost)
        p.applyTheme(pal, st.fontSize)
        col.addView(p, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel = p
        return p
    }

    private fun closeTranslate() {
        stopLive()
        oneShot = null
        panel?.let { column?.removeView(it) }
        panel = null
        strip?.visibility = View.VISIBLE
        onEngineChanged()
    }

    // ---------------------------------------------------------------- набор с переводом

    private fun liveSource(): String? = (live?.engine?.lang ?: layoutLang).ifEmpty { null }

    private fun liveTitle() = langName(liveSource()) + " → " + langName(st.translateTarget)

    private fun liveHint() = "Пишите здесь — в поле сразу появится перевод (${langName(st.translateTarget)})"

    private fun startLive() {
        val p = openPanel() ?: return
        oneShot = null
        if (live == null) {
            mainEngine.finishComposing()
            val l = LiveTranslation(
                liveField,
                { text, done ->
                    TranslationManager.translateAsync(
                        this, text, st.translateTarget, st.translationMode, liveSource(), persist = false, done = done
                    )
                },
                liveScheduler,
                LiveTranslation.LIVE_PROFILE.copy(learning = profile.learning)
            )
            l.engine.onSwitchLanguage = { target -> switchForFix(target) }
            l.onChange = { onEngineChanged() }
            live = l
            applySettingsToEngine()
            applyLayoutToEngine()
        }
        p.showLive(liveTitle(), liveHint())
        strip?.visibility = View.VISIBLE
        rebuildKeys()
        onEngineChanged()
    }

    private fun stopLive() {
        val l = live ?: return
        live = null
        l.close()
        rebuildKeys()
        onEngineChanged()
    }

    private fun liveStatus(l: LiveTranslation): String {
        if (liveSource() == st.translateTarget) return "Язык набора и перевода совпадают — выберите язык ▾"
        return when (l.state) {
            LiveTranslation.State.IDLE ->
                if (l.committed) "Перевод вставлен · ⏎ — отправить" else "⏎ — вставить перевод в поле"
            LiveTranslation.State.TYPING -> "…"
            LiveTranslation.State.TRANSLATING -> if (l.commitPending) "Перевожу и вставляю…" else "Перевожу…"
            LiveTranslation.State.DONE -> engineName(l.lastResult) + " · ⏎ — вставить"
            LiveTranslation.State.ERROR -> "Не перевелось: " + (l.error ?: "ошибка")
        }
    }

    private fun engineName(r: TranslationManager.Result?): String = when (r?.engine) {
        null, "" -> "Без перевода"
        TranslationManager.ENGINE_OFFLINE -> "Без интернета"
        else -> r.engine + ", онлайн"
    }

    // ---------------------------------------------------------------- разовый перевод

    private fun translateField() {
        val c = currentInputConnection ?: return
        stopLive()
        mainEngine.finishComposing()
        val sel = c.getSelectedText(0)?.toString()
        if (!sel.isNullOrBlank()) {
            translateText(sel, Source.SELECTION)
            return
        }
        val before = c.getTextBeforeCursor(MAX_FIELD_TEXT, 0)?.toString() ?: ""
        val after = c.getTextAfterCursor(MAX_FIELD_TEXT, 0)?.toString() ?: ""
        translateText(before + after, Source.FIELD, before.length, after.length)
    }

    private fun translateClipboard() {
        val text = try {
            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) {
            null
        }
        translateText(text ?: "", Source.CLIPBOARD)
    }

    private fun hasCyrillic(s: String) = s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }

    private fun translateText(text: String, source: Source, before: Int = 0, after: Int = 0) {
        val p = openPanel() ?: return
        stopLive()
        strip?.visibility = View.GONE
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            oneShot = null
            val msg = if (source == Source.CLIPBOARD) "Скопированного текста нет" else "В поле нет текста"
            p.showResult(langName(null) + " → " + langName(st.translateTarget), "", null, msg, true, null)
            return
        }
        // латиница в своём поле при транслите — скорее всего транслит (privet → привет),
        // но английский текст так и остаётся английским: это решают словари и ML Kit
        val preferTranslit = source != Source.CLIPBOARD && layout == Layout.TRANSLIT && !hasCyrillic(trimmed)
        val shot = OneShot(source, text, trimmed, before, after)
        oneShot = shot
        p.showResult("…", shot.input, null, "Определяю язык…", false, null)
        TranslationManager.detectAsync(trimmed, preferTranslit) { d, input ->
            if (oneShot === shot) {
                shot.input = input
                chooseTarget(shot, d)
            }
        }
    }

    /** Своё — на язык перевода, чужое (скопированное, на языке перевода) — на русский. */
    private fun chooseTarget(shot: OneShot, d: TranslationManager.Detected) {
        val out = st.translateTarget
        val other = if (out == "ru") "en" else "ru"
        shot.from = if (d.reliable) d.lang else null
        shot.target = when (shot.source) {
            Source.CLIPBOARD -> if (d.lang != "ru") "ru" else if (out == "ru") "en" else out
            else -> if (d.lang == out) other else out
        }
        runOneShot(shot)
    }

    private fun oneShotTitle(shot: OneShot) = langName(shot.from) + " → " + langName(shot.target)

    private fun applyLabel(shot: OneShot) = when (shot.source) {
        Source.SELECTION -> "Заменить"
        Source.FIELD -> "Заменить всё"
        Source.CLIPBOARD -> "Вставить"
    }

    private fun resultNote(r: TranslationManager.Result): String {
        val base = when (r.engine) {
            "" -> "Текст уже на этом языке"
            TranslationManager.ENGINE_OFFLINE -> "Переведено без интернета"
            else -> "Переведено: ${r.engine}, онлайн"
        }
        if (r.offlineMissing.isEmpty()) return base
        return "$base · чтобы без интернета, скачайте: " + r.offlineMissing.joinToString { langName(it) } + " (~30 МБ)"
    }

    private fun runOneShot(shot: OneShot) {
        val p = panel ?: return
        val g = ++shot.gen
        shot.result = null
        p.showResult(oneShotTitle(shot), shot.input, null, "Перевожу…", false, null)
        TranslationManager.translateAsync(this, shot.input, shot.target, st.translationMode, shot.from) { r, e ->
            if (oneShot !== shot || shot.gen != g) return@translateAsync
            shot.result = r
            if (r != null && r.source.isNotEmpty()) shot.from = r.source
            showOneShot(shot, e)
        }
    }

    private fun showOneShot(shot: OneShot, e: Exception? = null, status: String? = null) {
        val p = panel ?: return
        val r = shot.result
        if (r != null) {
            p.showResult(oneShotTitle(shot), shot.input, r.text, status ?: resultNote(r), false, applyLabel(shot), r.offlineMissing)
        } else {
            p.showResult(
                oneShotTitle(shot), shot.input, null, status ?: ("Не перевелось: " + (e?.message ?: "ошибка")), true, null,
                (e as? TranslationManager.Failure)?.missing ?: emptyList()
            )
        }
    }

    private fun applyOneShot() {
        val shot = oneShot ?: return
        val r = shot.result ?: return
        val c = currentInputConnection ?: return
        mainEngine.finishComposing()
        c.beginBatchEdit()
        when (shot.source) {
            Source.SELECTION -> {
                if (c.getSelectedText(0)?.toString() == shot.original) c.commitText(r.text, 1) else insertAtCursor(c, r.text)
            }
            Source.FIELD -> {
                val b = c.getTextBeforeCursor(shot.before, 0)?.toString() ?: ""
                val a = c.getTextAfterCursor(shot.after, 0)?.toString() ?: ""
                if (b + a == shot.original) {
                    c.deleteSurroundingText(shot.before, shot.after)
                    c.commitText(r.text, 1)
                } else {
                    insertAtCursor(c, r.text)
                }
            }
            Source.CLIPBOARD -> insertAtCursor(c, r.text)
        }
        c.endBatchEdit()
        closeTranslate()
    }

    private fun insertAtCursor(c: InputConnection, text: String) {
        val before = c.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val sep = if (before.isNotEmpty() && !before[0].isWhitespace() && text.firstOrNull()?.isLetterOrDigit() == true) " " else ""
        c.commitText(sep + text, 1)
    }

    private fun copyOneShot() {
        val shot = oneShot ?: return
        val r = shot.result ?: return
        try {
            ownClip = true
            clipboard?.setPrimaryClip(ClipData.newPlainText("Перевод", r.text))
        } catch (_: Exception) {
            ownClip = false
        }
        showOneShot(shot, status = "Скопировано")
    }

    // ---------------------------------------------------------------- языки

    private fun toggleLanguagePicker() {
        val p = panel ?: return
        if (p.mode == TranslatePanel.Mode.PICKER) {
            val shot = oneShot
            if (live == null && shot != null) showOneShot(shot) else startLive()
            return
        }
        val shot = oneShot
        strip?.visibility = View.GONE
        if (live == null && shot != null) p.showPicker(oneShotTitle(shot), shot.target)
        else p.showPicker(liveTitle(), st.translateTarget)
    }

    private fun pickLanguage(code: String) {
        val shot = oneShot
        if (live != null || shot == null) {
            KbSettings.update(this) { it.copy(translateTarget = code) }
            st = KbSettings.get(this)
            startLive()
            live?.retranslate()
            return
        }
        // своё — запоминаем язык перевода; перевод скопированного на другой язык — только сейчас
        if (shot.source != Source.CLIPBOARD && code != "ru") {
            KbSettings.update(this) { it.copy(translateTarget = code) }
            st = KbSettings.get(this)
        }
        shot.target = code
        runOneShot(shot)
    }

    private fun downloadModels(langs: List<String>) {
        if (langs.isEmpty()) return
        val wifi = st.translateWifiOnly
        val names = langs.joinToString { langName(it) }
        setTranslateStatus(
            if (wifi && !TranslationManager.onWifi(this)) "Скачаю $names (~30 МБ), как только будет Wi‑Fi…"
            else "Скачиваю $names (~30 МБ)…",
            false
        )
        TranslationManager.background({ TranslationManager.offline.download(langs, wifi) }) { err ->
            if (err != null) {
                setTranslateStatus("Не скачалось: $err", true)
                return@background
            }
            TranslationManager.clearMemoryCache()
            val shot = oneShot
            when {
                live != null -> live?.retranslate()
                shot != null -> runOneShot(shot)
            }
        }
    }

    private fun setTranslateStatus(msg: String, error: Boolean) {
        val p = panel ?: return
        val shot = oneShot
        when {
            live != null && p.mode == TranslatePanel.Mode.LIVE -> p.setLiveStatus(msg, error, emptyList())
            shot != null && p.mode == TranslatePanel.Mode.RESULT -> showOneShot(shot, status = msg)
        }
    }

    // ---------------------------------------------------------------- скопированное

    private var clipboard: ClipboardManager? = null
    private var ownClip = false
    private var clipChangedAt = 0L
    private var lastOfferedClip = 0L

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        clipChangedAt = System.currentTimeMillis()
        if (ownClip) {
            // своё копирование («Копировать» в панели) не предлагаем переводить
            ownClip = false
            lastOfferedClip = clipStamp()
            return@OnPrimaryClipChangedListener
        }
        if (isInputViewShown) maybeOfferClip()
    }

    /** Когда скопировано (содержимое не читаем — Android покажет «вставлено из буфера» только по нажатию). */
    private fun clipStamp(): Long {
        val d = try {
            clipboard?.primaryClipDescription
        } catch (_: Exception) {
            null
        } ?: return 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // время копирования от системы; если оно странное (сбитые часы) — время уведомления
            val ts = d.timestamp
            if (ts > 0 && kotlin.math.abs(System.currentTimeMillis() - ts) < DAY_MS) return ts
        }
        return clipChangedAt
    }

    /** Только что скопировали текст — предложить перевести его (один раз). */
    private fun maybeOfferClip() {
        var offer = false
        if (st.clipTranslate && profile.kind == FieldProfile.Kind.TEXT && panel == null) {
            val d = try {
                clipboard?.primaryClipDescription
            } catch (_: Exception) {
                null
            }
            if (d != null && (d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) {
                val stamp = clipStamp()
                val age = System.currentTimeMillis() - stamp
                if (stamp > 0 && stamp != lastOfferedClip && age in 0..CLIP_OFFER_MS) {
                    lastOfferedClip = stamp
                    offer = true
                }
            }
        }
        mainEngine.offerClipboard(offer)
    }

    private companion object {
        const val MAX_FIELD_TEXT = 2000
        const val CLIP_OFFER_MS = 3 * 60_000L
        const val DAY_MS = 24 * 3600_000L
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
