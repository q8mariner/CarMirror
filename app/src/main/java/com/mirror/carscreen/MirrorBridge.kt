package com.mirror.carscreen

import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.min

/**
 * Shared state between the Android Auto session (which owns the car surface)
 * and the screen-capture service (which owns the MediaProjection). Either side
 * can come up first; whichever arrives second completes the connection.
 */
object MirrorBridge {

    data class CarSurface(val surface: Surface, val width: Int, val height: Int, val dpi: Int)

    @Volatile
    var carSurface: CarSurface? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun setCarSurface(surface: CarSurface?) {
        carSurface = surface
        ScreenCaptureService.instance?.onCarSurfaceChanged(surface)
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}

/** Real pixel size of the phone's main display in its current rotation. */
object PhoneDisplay {
    fun realSize(context: Context): Point {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        return p
    }
}

/**
 * The mirrored phone image is scaled to fit the car surface with its aspect
 * ratio preserved and centred (letterboxed). These helpers convert between the two.
 */
object CoordinateMapper {

    fun scale(carW: Int, carH: Int, phoneW: Int, phoneH: Int): Float =
        min(carW.toFloat() / phoneW, carH.toFloat() / phoneH)

    fun carToPhone(x: Float, y: Float, carW: Int, carH: Int, phoneW: Int, phoneH: Int): PointF? {
        if (carW <= 0 || carH <= 0 || phoneW <= 0 || phoneH <= 0) return null
        val s = scale(carW, carH, phoneW, phoneH)
        val offX = (carW - phoneW * s) / 2f
        val offY = (carH - phoneH * s) / 2f
        val px = (x - offX) / s
        val py = (y - offY) / s
        if (px < 0f || py < 0f || px >= phoneW || py >= phoneH) return null // tap on black bars
        return PointF(px, py)
    }
}
