package org.zotero.android.screens.reader.web

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.textclassifier.TextClassifier
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.hypot

class ReaderCustomWebView(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {

    init {
        // After a long-press drag or a selection handle drag, WebView asks the TextClassifier about
        // the selection before showing the selection menu again. For selections starting at the
        // beginning of a PDF text line, that menu never comes back and stays hidden until the
        // document is scrolled. Smart text selection isn't needed for creating annotations, so
        // skip it and always show the menu right away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTextClassifier(TextClassifier.NO_OP)
        }
    }

    var selectionActionModeDelegate: ReaderSelectionActionModeDelegate? = null

    private var activeSelectionActionMode: ActionMode? = null

    var onUserGestureDetected: (() -> Unit)? = null
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureAlreadyReportedThisTouch = false
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    /**
     * Swipes from a screen edge are usually system navigation gestures (back, home, recents,
     * notification shade). Some systems (e.g. Boox) let the app receive the whole swipe as well, so
     * the document would pan along with it. A finger touch that starts at a screen edge is therefore
     * held back: if it moves, the whole gesture is dropped; if it lifts without moving (e.g. an edge
     * tap to turn the page), gets a second finger or is held down long enough for a long press, the
     * held events are replayed and the gesture continues normally.
     */
    private val edgeZonePx by lazy { EDGE_ZONE_DP * resources.displayMetrics.density }
    private var heldEdgeDown: MotionEvent? = null
    private var ignoringEdgeSwipe = false
    private val releaseHeldEdgeDown = Runnable { releaseHeldEdgeDown() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            dropHeldEdgeDown()
            ignoringEdgeSwipe = false
            if (startsAtScreenEdge(ev)) {
                heldEdgeDown = MotionEvent.obtain(ev)
                postDelayed(releaseHeldEdgeDown, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
        }

        val heldDown = heldEdgeDown
        if (heldDown != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(ev.x - heldDown.x, ev.y - heldDown.y) > touchSlop) {
                        dropHeldEdgeDown()
                        ignoringEdgeSwipe = true
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dropHeldEdgeDown()
                    return true
                }

                else -> releaseHeldEdgeDown()
            }
        }

        if (ignoringEdgeSwipe) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                ignoringEdgeSwipe = false
            }
            return true
        }

        return dispatchTouchEventToWebView(ev)
    }

    private fun startsAtScreenEdge(ev: MotionEvent): Boolean {
        if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) {
            return false
        }
        val window = rootView
        val windowLocation = IntArray(2)
        window.getLocationOnScreen(windowLocation)
        val x = ev.rawX - windowLocation[0]
        val y = ev.rawY - windowLocation[1]
        return x < edgeZonePx || y < edgeZonePx ||
                x > window.width - edgeZonePx || y > window.height - edgeZonePx
    }

    private fun releaseHeldEdgeDown() {
        val heldDown = heldEdgeDown ?: return
        removeCallbacks(releaseHeldEdgeDown)
        heldEdgeDown = null
        dispatchTouchEventToWebView(heldDown)
        heldDown.recycle()
    }

    private fun dropHeldEdgeDown() {
        removeCallbacks(releaseHeldEdgeDown)
        heldEdgeDown?.recycle()
        heldEdgeDown = null
    }

    override fun onDetachedFromWindow() {
        dropHeldEdgeDown()
        super.onDetachedFromWindow()
    }

    private fun dispatchTouchEventToWebView(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = ev.x
                gestureDownY = ev.y
                gestureAlreadyReportedThisTouch = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!gestureAlreadyReportedThisTouch) {
                    gestureAlreadyReportedThisTouch = true
                    onUserGestureDetected?.invoke()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureAlreadyReportedThisTouch &&
                    (abs(ev.x - gestureDownX) > touchSlop || abs(ev.y - gestureDownY) > touchSlop)
                ) {
                    gestureAlreadyReportedThisTouch = true
                    onUserGestureDetected?.invoke()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun invalidateSelectionActionMode() {
        activeSelectionActionMode?.invalidate()
    }

    override fun startActionModeForChild(
        originalView: View,
        callback: ActionMode.Callback
    ): ActionMode {
        return super.startActionModeForChild(originalView, callback)
    }

    override fun startActionModeForChild(
        originalView: View,
        callback: ActionMode.Callback,
        type: Int
    ): ActionMode {
        return super.startActionModeForChild(originalView, callback, type)
    }

    public override fun startActionMode(callback: ActionMode.Callback): ActionMode {
        val actionMode = startActionModeForChild(this, wrapSelectionCallback(callback))
        activeSelectionActionMode = actionMode
        return actionMode
    }

    public override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode {
        val actionMode = startActionModeForChild(this, wrapSelectionCallback(callback), type)
        activeSelectionActionMode = actionMode
        return actionMode
    }

    private fun wrapSelectionCallback(callback: ActionMode.Callback): ActionMode.Callback {
        return ReaderWevViewSelectActionModeCallback(
            context = context,
            original = callback,
            delegate = { selectionActionModeDelegate },
            onDestroyed = { activeSelectionActionMode = null }
        )
    }

    override fun showContextMenu(): Boolean {
        return super.showContextMenu()
    }

    private companion object {
        const val EDGE_ZONE_DP = 32f
    }
}
