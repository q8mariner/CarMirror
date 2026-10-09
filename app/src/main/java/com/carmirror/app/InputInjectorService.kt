package com.carmirror.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min

private const val TAG = "InputInjector"

// =============================================================================
// 1. Accessibility service (the default injection path)
// =============================================================================

/**
 * Gesture-only AccessibilityService. It registers an [AccessibilityInputBackend]
 * with [MirrorController] while bound; it never reads window content.
 */
class InputInjectorService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        MirrorController.accessibilityBackend = AccessibilityInputBackend(this)
        Log.i(TAG, "Accessibility backend connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        MirrorController.accessibilityBackend = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        MirrorController.accessibilityBackend = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

// =============================================================================
// 2. Backend abstraction (Accessibility now, Shizuku/ADB as a drop-in fallback)
// =============================================================================

/** All coordinates are in the phone's real-display pixel space. */
interface InputBackend {
    val name: String
    suspend fun tap(x: Float, y: Float): Boolean
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean
    suspend fun dragStart(x: Float, y: Float): Boolean
    suspend fun dragMove(x: Float, y: Float): Boolean
    suspend fun dragEnd(x: Float, y: Float, durationMs: Long): Boolean
    suspend fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long): Boolean
}

class AccessibilityInputBackend(private val service: AccessibilityService) : InputBackend {
    override val name = "accessibility"

    // Continued-stroke state for real-time dragging (API 26+).
    private var stroke: GestureDescription.StrokeDescription? = null
    private val last = PointF()
    private var lastTime = 0L

    override suspend fun tap(x: Float, y: Float) =
        dispatch(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, TAP_MS))

    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        dispatch(
            GestureDescription.StrokeDescription(
                Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, durationMs.coerceAtLeast(1)
            )
        )

    override suspend fun dragStart(x: Float, y: Float): Boolean {
        val s = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1, true)
        stroke = s
        last.set(x, y)
        lastTime = SystemClock.uptimeMillis()
        return dispatch(s).also { if (!it) stroke = null }
    }

    override suspend fun dragMove(x: Float, y: Float): Boolean {
        val prev = stroke ?: return dragStart(x, y)
        val now = SystemClock.uptimeMillis()
        // Segment duration tracks real elapsed time so the app sees a realistic velocity.
        val dur = (now - lastTime).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        val path = Path().apply { moveTo(last.x, last.y); lineTo(x, y) }
        val s = prev.continueStroke(path, 0, dur, true)
        stroke = s
        last.set(x, y)
        lastTime = now
        return dispatch(s).also { if (!it) stroke = null }
    }

    override suspend fun dragEnd(x: Float, y: Float, durationMs: Long): Boolean {
        val prev = stroke ?: return false
        stroke = null
        val path = Path().apply { moveTo(last.x, last.y); lineTo(x, y) }
        return dispatch(prev.continueStroke(path, 0, durationMs.coerceAtLeast(1), false))
    }

    override suspend fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long): Boolean {
        val m = DisplayUtils.realMetrics(service)
        val maxX = m.widthPixels - 1f
        fun cl(v: Float) = v.coerceIn(0f, maxX)
        val left = Path().apply { moveTo(cl(cx - startSpan / 2), cy); lineTo(cl(cx - endSpan / 2), cy) }
        val right = Path().apply { moveTo(cl(cx + startSpan / 2), cy); lineTo(cl(cx + endSpan / 2), cy) }
        return dispatch(
            GestureDescription.StrokeDescription(left, 0, durationMs),
            GestureDescription.StrokeDescription(right, 0, durationMs)
        )
    }

    /** Suspends until the gesture completes; dispatches are therefore serialized. */
    private suspend fun dispatch(vararg strokes: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()
            val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }

    private companion object {
        const val TAP_MS = 40L
        const val MIN_SEGMENT_MS = 8L
        const val MAX_SEGMENT_MS = 50L
    }
}

/**
 * Fallback hook for higher responsiveness: injects through the shell `input` tool
 * (uid 2000), which bypasses Accessibility gesture scheduling.
 *
 * Wire [exec] to a privileged shell, e.g. a Shizuku UserService or adb-over-Wi-Fi,
 * then set `MirrorController.overrideBackend = ShellInputBackend(myExecutor)`.
 * `input motionevent` requires Android 11+. Multi-finger pinch is not possible
 * through the `input` tool; those calls return false.
 */
class ShellInputBackend(private val exec: ShellExecutor) : InputBackend {
    fun interface ShellExecutor { suspend fun exec(command: String): Int }

    override val name = "shell"
    private val last = PointF()

    private fun f(v: Float) = v.toInt().toString()

    override suspend fun tap(x: Float, y: Float) = exec.exec("input tap ${f(x)} ${f(y)}") == 0
    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        exec.exec("input swipe ${f(x1)} ${f(y1)} ${f(x2)} ${f(y2)} $durationMs") == 0
    override suspend fun dragStart(x: Float, y: Float): Boolean {
        last.set(x, y); return exec.exec("input motionevent DOWN ${f(x)} ${f(y)}") == 0
    }
    override suspend fun dragMove(x: Float, y: Float): Boolean {
        last.set(x, y); return exec.exec("input motionevent MOVE ${f(x)} ${f(y)}") == 0
    }
    override suspend fun dragEnd(x: Float, y: Float, durationMs: Long): Boolean {
        exec.exec("input motionevent MOVE ${f(x)} ${f(y)}")
        return exec.exec("input motionevent UP ${f(x)} ${f(y)}") == 0
    }
    override suspend fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long) = false
}

// =============================================================================
// 3. Coordinate mapping: car surface → phone display
// =============================================================================

/**
 * Mirrors the letterbox the system applies when an AUTO_MIRROR VirtualDisplay of a
 * different aspect ratio is rendered: the phone image is scaled uniformly to fit
 * and centred. Re-reads the phone size on every call, so rotation is handled.
 */
class CoordinateMapper(private val context: Context) {
    @Volatile var surfaceWidth = 0
    @Volatile var surfaceHeight = 0

    data class Viewport(val left: Float, val top: Float, val scale: Float, val phoneW: Int, val phoneH: Int)

    fun viewport(): Viewport? {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return null
        val m = DisplayUtils.realMetrics(context)
        val scale = min(surfaceWidth.toFloat() / m.widthPixels, surfaceHeight.toFloat() / m.heightPixels)
        val contentW = m.widthPixels * scale
        val contentH = m.heightPixels * scale
        return Viewport(
            left = (surfaceWidth - contentW) / 2f,
            top = (surfaceHeight - contentH) / 2f,
            scale = scale,
            phoneW = m.widthPixels,
            phoneH = m.heightPixels
        )
    }

    /** Car (x, y) → phone pixel, or null if the point is in the letterbox bars. */
    fun toPhone(x: Float, y: Float): PointF? {
        val v = viewport() ?: return null
        val px = (x - v.left) / v.scale
        val py = (y - v.top) / v.scale
        if (px < 0f || py < 0f || px > v.phoneW || py > v.phoneH) return null
        return PointF(px.coerceIn(0f, v.phoneW - 1f), py.coerceIn(0f, v.phoneH - 1f))
    }

    /** Car-pixel distance → phone-pixel distance. */
    fun lengthToPhone(d: Float): Float = viewport()?.let { d / it.scale } ?: d

    fun phoneCenter(): PointF {
        val m = DisplayUtils.realMetrics(context)
        return PointF(m.widthPixels / 2f, m.heightPixels / 2f)
    }

    fun clampToPhone(p: PointF): PointF {
        val m = DisplayUtils.realMetrics(context)
        return PointF(p.x.coerceIn(0f, m.widthPixels - 1f), p.y.coerceIn(0f, m.heightPixels - 1f))
    }

    fun isInsidePhone(x: Float, y: Float): Boolean {
        val m = DisplayUtils.realMetrics(context)
        return x >= 0f && y >= 0f && x < m.widthPixels && y < m.heightPixels
    }
}

// =============================================================================
// 4. Gesture engine: car events → phone gestures
// =============================================================================

sealed interface CarTouch {
    data class Click(val x: Float, val y: Float) : CarTouch
    /** GestureDetector convention: distance = previous − current position. */
    data class Scroll(val distanceX: Float, val distanceY: Float) : CarTouch
    /** Pixels per second in car-surface space. */
    data class Fling(val velocityX: Float, val velocityY: Float) : CarTouch
    data class Scale(val focusX: Float, val focusY: Float, val scaleFactor: Float) : CarTouch
}

/**
 * Serializes car input into phone gestures on a single coroutine.
 *
 * - Bursts of Scroll events are coalesced and streamed as one continued stroke, so
 *   the finger stays "down" across events (real dragging, not a series of flicks).
 * - The drag is lifted after [DRAG_IDLE_MS] without new scroll events, or by a Fling
 *   (which ends the stroke with a fast final segment so the app sees fling velocity).
 * - Scroll events carry no start point, so drags begin at the last tapped point (if
 *   recent) or the phone centre, and re-anchor at the centre if they hit an edge.
 */
class GestureEngine(context: Context, scope: CoroutineScope) {

    val mapper = CoordinateMapper(context.applicationContext)
    private val events = Channel<CarTouch>(Channel.UNLIMITED)

    private var lastTap: PointF? = null
    private var lastTapTime = 0L
    private var dragging = false
    private var dragPos = PointF()

    init {
        scope.launch { loop() }
    }

    fun submit(event: CarTouch) {
        events.trySend(event)
    }

    private suspend fun loop() {
        var pending: CarTouch? = null
        while (true) {
            val event: CarTouch
            val queued = pending
            if (queued != null) {
                event = queued
                pending = null
            } else if (dragging) {
                val next = withTimeoutOrNull(DRAG_IDLE_MS) { events.receive() }
                if (next == null) {
                    endDrag(0f, 0f)
                    continue
                }
                event = next
            } else {
                event = events.receive()
            }
            pending = try {
                handle(event)
            } catch (e: Exception) {
                Log.w(TAG, "Gesture failed: $event", e); null
            }
        }
    }

    /** Returns an event pulled while coalescing that still needs handling. */
    private suspend fun handle(event: CarTouch): CarTouch? {
        val backend = MirrorController.activeBackend ?: run {
            Log.w(TAG, "No input backend: enable the Accessibility service"); return null
        }
        return when (event) {
            is CarTouch.Click -> {
                if (dragging) endDrag(0f, 0f)
                val p = mapper.toPhone(event.x, event.y) ?: return null
                lastTap = p
                lastTapTime = SystemClock.uptimeMillis()
                backend.tap(p.x, p.y)
                null
            }

            is CarTouch.Scroll -> {
                var dx = event.distanceX
                var dy = event.distanceY
                var leftover: CarTouch? = null
                while (true) {
                    val n = events.tryReceive().getOrNull() ?: break
                    if (n is CarTouch.Scroll) { dx += n.distanceX; dy += n.distanceY } else { leftover = n; break }
                }
                if (!dragging) {
                    dragPos = dragOrigin()
                    if (!backend.dragStart(dragPos.x, dragPos.y)) return leftover
                    dragging = true
                }
                // Finger moves opposite to the reported scroll distance.
                val nx = dragPos.x - mapper.lengthToPhone(dx)
                val ny = dragPos.y - mapper.lengthToPhone(dy)
                if (!mapper.isInsidePhone(nx, ny)) {
                    // Hit an edge: lift and re-grab from the centre to keep scrolling.
                    val edge = mapper.clampToPhone(PointF(nx, ny))
                    backend.dragEnd(edge.x, edge.y, 16)
                    dragging = false
                    lastTap = null
                    return leftover
                }
                dragPos = PointF(nx, ny)
                if (!backend.dragMove(nx, ny)) dragging = false
                leftover
            }

            is CarTouch.Fling -> {
                val vx = mapper.lengthToPhone(event.velocityX)
                val vy = mapper.lengthToPhone(event.velocityY)
                if (dragging) {
                    endDrag(vx, vy)
                } else {
                    val o = dragOrigin()
                    val end = mapper.clampToPhone(PointF(o.x + vx * 0.08f, o.y + vy * 0.08f))
                    backend.swipe(o.x, o.y, end.x, end.y, 80)
                }
                null
            }

            is CarTouch.Scale -> {
                if (dragging) endDrag(0f, 0f)
                var factor = event.scaleFactor
                var focus = PointF(event.focusX, event.focusY)
                var leftover: CarTouch? = null
                while (true) {
                    val n = events.tryReceive().getOrNull() ?: break
                    if (n is CarTouch.Scale) { factor *= n.scaleFactor; focus = PointF(n.focusX, n.focusY) }
                    else { leftover = n; break }
                }
                if (abs(factor - 1f) < 0.02f) return leftover
                val c = mapper.toPhone(focus.x, focus.y) ?: mapper.phoneCenter()
                val start = PINCH_BASE_SPAN
                val end = (PINCH_BASE_SPAN * factor).coerceIn(40f, PINCH_BASE_SPAN * 4)
                backend.pinch(c.x, c.y, start, end, 150)
                leftover
            }
        }
    }

    /** Lifts the finger, optionally with a final fast segment carrying fling velocity. */
    private suspend fun endDrag(vx: Float, vy: Float) {
        if (!dragging) return
        dragging = false
        val backend = MirrorController.activeBackend ?: return
        val end = mapper.clampToPhone(PointF(dragPos.x + vx * 0.03f, dragPos.y + vy * 0.03f))
        backend.dragEnd(end.x, end.y, if (vx == 0f && vy == 0f) 1 else 20)
    }

    private fun dragOrigin(): PointF {
        val tap = lastTap
        return if (tap != null && SystemClock.uptimeMillis() - lastTapTime < TAP_ANCHOR_MS) tap
        else mapper.phoneCenter()
    }

    private companion object {
        const val DRAG_IDLE_MS = 120L
        const val TAP_ANCHOR_MS = 4_000L
        const val PINCH_BASE_SPAN = 300f
    }
}
