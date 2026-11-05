package com.example.translit

/** Поле ввода для тестов: [TextBuffer] с очередью отложенных onUpdateSelection, нажатыми клавишами и действиями. */
class FakeEditor : TextBuffer() {
    /** Очередь onUpdateSelection: oldS, oldE, newS, newE, candS, candE. */
    val updates = ArrayDeque<IntArray>()
    val actions = ArrayList<Int>()
    val keys = ArrayList<Int>()

    init {
        onUpdate = { updates.addLast(it) }
    }

    override fun sendKey(keyCode: Int) {
        keys.add(keyCode)
        super.sendKey(keyCode)
    }

    override fun performEditorAction(action: Int) {
        actions.add(action)
    }
}
