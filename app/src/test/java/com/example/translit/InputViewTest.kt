package com.example.translit

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Окно клавиатуры: занимает только свою высоту и не закрывает приложение. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class InputViewTest {

    private fun text() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun density(f: ImeFixture) = f.svc.resources.displayMetrics.density

    @Test
    fun keyboardKeepsItsOwnHeight() {
        val f = ImeFixture(text())
        val h = f.root.measuredHeight
        assertTrue("высота окна $h из ${f.screenH}", h < f.screenH / 2)
        // окно = панель подсказок + клавиши + отступ под навигацию, без пустоты сверху
        val keys = f.kv.measuredHeight
        assertTrue("клавиши $keys, окно $h", h - keys < (44 + 48 + 8) * density(f))
        val col = (f.root as android.view.ViewGroup).getChildAt(0)
        assertEquals(0, col.top)
        assertEquals(h, col.measuredHeight)
        assertFalse(f.svc.onEvaluateFullscreenMode())
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun landscapeLeavesRoomForChat() {
        val f = ImeFixture(text())
        val h = f.root.measuredHeight
        assertTrue("высота окна $h из ${f.screenH}", h < f.screenH * 0.75)
        assertFalse(f.svc.onEvaluateFullscreenMode())
    }

    @Test
    fun numberPadKeepsItsOwnHeight() {
        val f = ImeFixture(EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER })
        assertTrue(f.root.measuredHeight < f.screenH / 2)
    }
}
