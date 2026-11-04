package com.example.translit

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.translit.KeyboardLayoutManager.Page
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class TranslitKeyboardService : InputMethodService(), KeyboardView.Listener {

    enum class Layout { TRANSLIT, CYRILLIC, LATIN, REVERSE, CUSTOM }

    private lateinit var st: KbSettings.State
    private lateinit var pal: KbSettings.Palette
    private lateinit var engine: InputEngine

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
    private var translatePanel: LinearLayout? = null
    private var translatePreview: TextView? = null
    private var translateThread: Thread? = null

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null

    // ================================================================ жизненный цикл

    override fun onCreate() {
        super.onCreate()
        st = KbSettings.get(this)
        pal = KbSettings.palette(st)
        engine = InputEngine(IcBridge())
        engine.listener = { onEngineChanged() }
        engine.onSwitchLanguage = { target -> switchForFix(target) }
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
            onTranslate = { haptic(null); openTranslate() },
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
        translatePanel = null
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
        engine.startInput(profile, info.initialSelStart, info.initialSelEnd)
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
        closeTranslate()
        applySettingsToEngine()
        applyLayoutToEngine()
        applyTheme()
        rebuildKeys()
        onEngineChanged()
        executor.execute { SuggestionEngine.loadExtraWords(this) }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        engine.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        engine.finishComposing()
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
        engine.settings = EngineSettings(
            autocorrect = st.autocorrect,
            suggestions = st.suggestionsEnabled,
            learn = st.learnWords,
            autoCaps = st.autoCaps,
            doubleSpacePeriod = st.doubleSpacePeriod,
            layoutFix = st.layoutFix
        )
        TranslitMode.customTable = TranslitMode.parseCustom(st.customTable)
        engine.actionTranslit = st.translitMode
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
        engine.layoutFixMode = when (layout) {
            Layout.TRANSLIT, Layout.CYRILLIC -> if (yaverty) 2 else 1
            Layout.LATIN, Layout.REVERSE -> if (yaverty) 0 else 1
            Layout.CUSTOM -> 0
        }
        engine.fixRuRender = if (russianLayout() == Layout.TRANSLIT) st.translitMode else null
        engine.configure(
            lang, if (layout == Layout.TRANSLIT) st.translitMode else null, prox,
            reverse = layout == Layout.REVERSE
        )
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

    private fun spaceLabel(): String = when (layout) {
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
        root?.setBackgroundColor(pal.bg)
        column?.setBackgroundColor(pal.bg)
        strip?.applyTheme(pal, st.fontSize)
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
        keyboard?.shiftState = engine.shift
        strip?.setChips(
            engine.chips,
            showTranslate = layout != Layout.TRANSLIT && profile.kind == FieldProfile.Kind.TEXT
        )
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
            KeyboardView.Type.ENTER -> engine.onEnter()
            KeyboardView.Type.BACKSPACE -> engine.onBackspace()
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

    override fun onBackspaceRepeat(count: Int) {
        if (count <= 20) engine.onBackspace() else engine.deleteWord()
    }

    override fun onBackspaceSwipeStart(): Boolean = engine.backspaceSwipeStart()
    override fun onBackspaceSwipe(steps: Int) = engine.backspaceSwipe(steps)
    override fun onBackspaceSwipeEnd() = engine.backspaceSwipeEnd()
    override fun onCursorSwipe(steps: Int) = engine.moveCursor(steps)

    override fun onSystemLongPress(key: KeyboardView.Key) {
        haptic(null)
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    override fun previewText(key: KeyboardView.Key): String? {
        if (key.type != KeyboardView.Type.CHAR) return null
        val l = key.label
        if (l.isEmpty() || l.length > 3) return null
        val upper = engine.upperCase
        if (layout == Layout.TRANSLIT && page == Page.LETTERS && l.length == 1 && l[0].isLetter()) {
            return st.translitMode.translit(l[0], upper, engine.shift == InputEngine.Shift.LOCKED).ifEmpty { l }
        }
        return if (upper && l.length == 1 && l[0].isLetter()) l.uppercase() else l
    }

    private fun openSettings() {
        engine.finishComposing()
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

    // ================================================================ панель перевода

    private fun openTranslate() {
        val col = column ?: return
        if (translatePanel != null) return
        engine.finishComposing()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        translatePanel = panel
        buildTranslatePanel(panel)
        strip?.visibility = View.GONE
        col.addView(panel, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun closeTranslate() {
        translateThread?.interrupt()
        translateThread = null
        translatePanel?.let { column?.removeView(it) }
        translatePanel = null
        translatePreview = null
        strip?.visibility = View.VISIBLE
    }

    private fun buildTranslatePanel(panel: LinearLayout) {
        panel.setBackgroundColor(pal.barBg)
        val title = TextView(this).apply {
            text = "Перевод (Google)"
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * st.fontSize)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8f).toInt(), dp(4f).toInt(), 0, dp(4f).toInt()) }
        }
        panel.addView(title)

        val preview = TextView(this).apply {
            text = "Наберите текст, выберите язык и нажмите на него"
            setTextColor(pal.dimText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * st.fontSize)
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8f).toInt(), 0, dp(8f).toInt(), dp(4f).toInt()) }
        }
        panel.addView(preview)
        translatePreview = preview

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110f).toInt())
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (chunk in TranslationManager.LANGS.chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((code, name) in chunk) {
                row.addView(Button(this).apply {
                    text = name
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * st.fontSize)
                    setTextColor(pal.text)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8f)
                        setColor(pal.keyTop)
                    }
                    val m = dp(2f).toInt()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(m, m, m, m) }
                    setOnClickListener { doTranslate(code, name) }
                })
            }
            grid.addView(row)
        }
        scroll.addView(grid)
        panel.addView(scroll)

        panel.addView(Button(this).apply {
            text = "✕ Закрыть"
            isAllCaps = false
            setTextColor(pal.text)
            background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(pal.funcTop)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(2f).toInt()) }
            setOnClickListener { closeTranslate() }
        })
    }

    private fun doTranslate(target: String, targetName: String) {
        val pv = translatePreview ?: return
        val ic = currentInputConnection ?: return
        engine.finishComposing()
        val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        if (before.isBlank()) {
            pv.text = "Сначала наберите текст"
            return
        }
        TranslationManager.getCached(this, before.trim(), target)?.let { cached ->
            pv.text = cached
            pv.setTextColor(pal.accent)
            showInsertButton(cached, before.length)
            return
        }
        pv.text = "Перевожу на $targetName…"
        pv.setTextColor(pal.dimText)
        translateThread?.interrupt()
        translateThread = thread(start = true) {
            val result = try {
                TranslationManager.translate(this, before.trim(), target)
            } catch (e: Exception) {
                "Ошибка сети: ${e.message}"
            }
            handler.post {
                val p = translatePreview ?: return@post
                p.text = result
                if (result.startsWith("Ошибка")) {
                    p.setTextColor(Color.RED)
                } else {
                    p.setTextColor(pal.accent)
                    showInsertButton(result, before.length)
                }
            }
        }
    }

    private fun showInsertButton(result: String, sourceLen: Int) {
        val panel = translatePanel ?: return
        for (i in panel.childCount - 1 downTo 0) {
            if (panel.getChildAt(i).tag == "insert") panel.removeViewAt(i)
        }
        val btn = Button(this).apply {
            tag = "insert"
            text = "⤵ Вставить перевод"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(pal.accent)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6f).toInt(), dp(2f).toInt(), dp(6f).toInt(), dp(2f).toInt()) }
            setOnClickListener {
                val ic = currentInputConnection ?: return@setOnClickListener
                engine.finishComposing()
                ic.beginBatchEdit()
                val before = ic.getTextBeforeCursor(sourceLen, 0)?.toString() ?: ""
                if (before.length == sourceLen && sourceLen > 0) ic.deleteSurroundingText(sourceLen, 0)
                ic.commitText(result, 1)
                ic.endBatchEdit()
                closeTranslate()
            }
        }
        panel.addView(btn, panel.childCount - 1)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
