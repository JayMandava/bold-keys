package ai.boldkeys

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/** Semantics for painted controls, never for the editor's contents. */
internal data class AccessibleControl(
    val id: Int, val bounds: Rect, val label: String,
    val state: String? = null, val checked: Boolean? = null, val sensitive: Boolean = false,
    val actions: Map<Int, String> = mapOf(AccessibilityNodeInfo.ACTION_CLICK to "Activate"),
    val perform: (Int) -> Boolean,
)

internal class AccessibleControls(private val host: View, private val controls: () -> List<AccessibleControl>) : ExploreByTouchHelper(host) {
    private var active = emptyMap<Int, AccessibleControl>()
    private var signature: List<List<Any?>> = emptyList()
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF2f8fe8.toInt(); strokeWidth = host.resources.displayMetrics.density * 2
    }

    fun install() { host.isFocusableInTouchMode = true; host.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE; ViewCompat.setAccessibilityDelegate(host, this); refresh() }

    fun refresh() {
        val next = controls().filter { !it.bounds.isEmpty }.associateBy { it.id }
        val nextSignature = next.values.map { listOf(it.id, it.bounds, it.label, it.state, it.checked, it.sensitive, it.actions) }
        active = next
        if (signature == nextSignature) return
        signature = nextSignature
        if (keyboardFocusedVirtualViewId != INVALID_ID && keyboardFocusedVirtualViewId !in active) clearKeyboardFocusForVirtualView(keyboardFocusedVirtualViewId)
        val focused = accessibilityFocusedVirtualViewId
        if (focused != INVALID_ID && focused !in active) {
            getAccessibilityNodeProvider(host)?.performAction(focused, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        }
        // A generic subtree event contains no candidate/editor text. No live-region or announcement events.
        invalidateRoot()
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int = active.values.firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }?.id ?: INVALID_ID
    override fun getVisibleVirtualViews(ids: MutableList<Int>) { ids.addAll(active.keys) }
    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
        val c = active[id]
        node.className = "android.widget.Button"
        node.contentDescription = c?.label ?: "Unavailable"
        node.setBoundsInParent(c?.bounds ?: Rect(0, 0, 1, 1))
        node.isEnabled = c != null && host.isEnabled && c.actions.isNotEmpty()
        node.isFocusable = c != null
        node.isClickable = c?.actions?.containsKey(AccessibilityNodeInfo.ACTION_CLICK) == true
        node.isScrollable = c?.actions?.keys?.any { it == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } == true
        node.isLongClickable = c?.actions?.containsKey(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
        node.stateDescription = c?.state
        c?.checked?.let { node.isCheckable = true; node.isChecked = it }
        if (Build.VERSION.SDK_INT >= 34) node.unwrap().isAccessibilityDataSensitive = c?.sensitive == true
        c?.actions?.forEach { (action, label) -> node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(action, label)) }
    }

    override fun onPopulateEventForVirtualView(id: Int, event: AccessibilityEvent) {
        if (Build.VERSION.SDK_INT >= 34) event.isAccessibilityDataSensitive = active[id]?.sensitive == true
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
        // Removed/replaced nodes cannot activate a different candidate/key in a later editor or layout.
        val c = active[id] ?: return false
        if (!host.isShown || !host.isEnabled || action !in c.actions) return false
        val handled = c.perform(action)
        refresh()
        host.invalidate()
        // Editing feedback belongs to the host editor/service. Do not announce inserted text here.
        return handled
    }

    override fun onVirtualViewKeyboardFocusChanged(id: Int, hasFocus: Boolean) { host.invalidate() }
    fun hover(event: MotionEvent) = dispatchHoverEvent(event)
    fun key(event: KeyEvent) = dispatchKeyEvent(event)
    fun focus(gain: Boolean, direction: Int, rect: Rect?) = onFocusChanged(gain, direction, rect)
    fun drawFocus(canvas: Canvas) {
        val id = if (accessibilityFocusedVirtualViewId != INVALID_ID) accessibilityFocusedVirtualViewId else if (!host.isInTouchMode) keyboardFocusedVirtualViewId else INVALID_ID
        active[id]?.let { canvas.drawRect(it.bounds, focusPaint) }
    }
}
