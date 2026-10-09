package com.carmirror.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process hub wiring the three independent components together:
 *
 *   CarMirrorSession  --(car Surface)-->  ScreenCaptureService  (VirtualDisplay renders into it)
 *   CarMirrorSession  --(touch events)--> GestureEngine --> InputBackend (Accessibility / shell)
 *
 * The car Surface is delivered to sinks *synchronously* so that the VirtualDisplay
 * detaches before SurfaceCallback.onSurfaceDestroyed() returns and the host
 * releases the buffer queue.
 */
object MirrorController {

    data class CarSurface(val surface: Surface, val width: Int, val height: Int, val dpi: Int)

    // ---- Car output surface -------------------------------------------------
    private val _carSurface = MutableStateFlow<CarSurface?>(null)
    val carSurface: StateFlow<CarSurface?> = _carSurface.asStateFlow()

    private val surfaceSinks = CopyOnWriteArraySet<(CarSurface?) -> Unit>()

    fun attachCarSurface(s: CarSurface) {
        _carSurface.value = s
        surfaceSinks.forEach { it(s) }
    }

    fun detachCarSurface(surface: Surface) {
        if (_carSurface.value?.surface !== surface) return
        _carSurface.value = null
        surfaceSinks.forEach { it(null) }
    }

    /** Registers a sink and immediately delivers the current surface (if any). */
    fun addSurfaceSink(sink: (CarSurface?) -> Unit) {
        surfaceSinks += sink
        sink(_carSurface.value)
    }

    fun removeSurfaceSink(sink: (CarSurface?) -> Unit) {
        surfaceSinks -= sink
    }

    // ---- Projection state ---------------------------------------------------
    private val _projectionActive = MutableStateFlow(false)
    val projectionActive: StateFlow<Boolean> = _projectionActive.asStateFlow()
    internal fun setProjectionActive(active: Boolean) { _projectionActive.value = active }

    // ---- Encoded stream (optional; for custom transports) ------------------
    private val _encodedFrames = MutableSharedFlow<EncodedFrame>(
        extraBufferCapacity = 120,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val encodedFrames: SharedFlow<EncodedFrame> = _encodedFrames.asSharedFlow()
    internal fun emitEncoded(frame: EncodedFrame) { _encodedFrames.tryEmit(frame) }

    // ---- Input backends -----------------------------------------------------
    /** Set by InputInjectorService while it is bound. */
    @Volatile internal var accessibilityBackend: InputBackend? = null

    /**
     * Fallback hook: assign a [ShellInputBackend] (Shizuku / ADB) here to bypass
     * the AccessibilityService for lower latency. Takes precedence when non-null.
     */
    @Volatile var overrideBackend: InputBackend? = null

    val activeBackend: InputBackend?
        get() = overrideBackend ?: accessibilityBackend
}

class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
    val isCodecConfig: Boolean
)

object DisplayUtils {
    /** Physical (real) size of the phone's built-in display in its current rotation. */
    @Suppress("DEPRECATION")
    fun realMetrics(context: Context): DisplayMetrics {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }
}
