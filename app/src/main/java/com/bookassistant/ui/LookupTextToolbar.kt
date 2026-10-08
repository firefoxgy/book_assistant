package com.bookassistant.ui

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/** Adds a direct lookup action to Android's floating text-selection menu. */
class LookupTextToolbar(
    private val view: View,
    private val clipboard: ClipboardManager,
    private val lookup: (String) -> Unit
) : TextToolbar {
    private var actionMode: ActionMode? = null
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        hide()
        val menuCallback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (onCopyRequested != null) menu.add(0, LOOKUP, 0, "查词")
                if (onCopyRequested != null) menu.add(0, COPY, 1, "复制")
                if (onSelectAllRequested != null) menu.add(0, SELECT_ALL, 2, "全选")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    LOOKUP -> {
                        onCopyRequested?.invoke()
                        clipboard.getText()?.text?.trim()?.takeIf { it.isNotBlank() }?.let(lookup)
                    }
                    COPY -> onCopyRequested?.invoke()
                    SELECT_ALL -> onSelectAllRequested?.invoke()
                    else -> return false
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                status = TextToolbarStatus.Hidden
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
                outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            }
        }
        actionMode = view.startActionMode(menuCallback, ActionMode.TYPE_FLOATING)
        status = if (actionMode == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    }

    override fun hide() {
        actionMode?.finish()
        actionMode = null
        status = TextToolbarStatus.Hidden
    }

    private companion object {
        const val LOOKUP = 1
        const val COPY = 2
        const val SELECT_ALL = 3
    }
}
