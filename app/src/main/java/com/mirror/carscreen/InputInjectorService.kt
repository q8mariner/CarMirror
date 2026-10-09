package com.mirror.carscreen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service that turns touches from the car screen into real
 * gestures on the phone (dispatchGesture). It does not read screen content.
 */
class InputInjectorService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: InputInjectorService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, InputInjectorService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MirrorBridge.notifyChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        MirrorBridge.notifyChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        MirrorBridge.notifyChanged()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- gestures (coordinates are phone screen pixels) ----

    fun tap(x: Float, y: Float) = press(x, y, 60L)

    fun longPress(x: Float, y: Float) = press(x, y, 700L)

    private fun press(x: Float, y: Float, duration: Long) {
        val (cx, cy) = clamp(x, y)
        val path = Path().apply { moveTo(cx, cy) }
        dispatch(GestureDescription.StrokeDescription(path, 0, duration))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val (sx, sy) = clamp(x1, y1)
        val (ex, ey) = clamp(x2, y2)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        dispatch(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1L)))
    }

    /** Two-finger pinch around (cx, cy), from startSpan to endSpan pixels apart. */
    fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, duration: Long = 300L) {
        val a = Path().apply {
            val (x1, y1) = clamp(cx - startSpan / 2f, cy)
            val (x2, y2) = clamp(cx - endSpan / 2f, cy)
            moveTo(x1, y1); lineTo(x2, y2)
        }
        val b = Path().apply {
            val (x1, y1) = clamp(cx + startSpan / 2f, cy)
            val (x2, y2) = clamp(cx + endSpan / 2f, cy)
            moveTo(x1, y1); lineTo(x2, y2)
        }
        dispatch(
            GestureDescription.StrokeDescription(a, 0, duration),
            GestureDescription.StrokeDescription(b, 0, duration)
        )
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun dispatch(vararg strokes: GestureDescription.StrokeDescription) {
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }
        try {
            dispatchGesture(builder.build(), null, null)
        } catch (_: Exception) {
            // invalid gesture (e.g. off-screen) – ignore
        }
    }

    private fun clamp(x: Float, y: Float): Pair<Float, Float> {
        val size = PhoneDisplay.realSize(this)
        return Pair(
            x.coerceIn(0f, (size.x - 1).toFloat()),
            y.coerceIn(0f, (size.y - 1).toFloat())
        )
    }
}
