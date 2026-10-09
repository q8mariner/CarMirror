package com.carmirror.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService

/**
 * Owns the MediaProjection session.
 *
 * Pipeline (zero-copy): phone display → VirtualDisplay(AUTO_MIRROR) → car Surface.
 * Android Auto itself hardware-encodes the car surface to H.264 and streams it over
 * USB, so no second encode is needed for the car path. When no car surface is
 * attached and the encoder is enabled, frames go to [LowLatencyEncoder] instead and
 * are published on [MirrorController.encodedFrames] (hook for custom transports).
 *
 * Android 14 rules respected here:
 *  - startForeground(type=MEDIA_PROJECTION) BEFORE getMediaProjection()
 *  - Callback registered BEFORE createVirtualDisplay()
 *  - createVirtualDisplay() called exactly once per token; later output changes use
 *    VirtualDisplay.setSurface()/resize().
 */
class ScreenCaptureService : LifecycleService() {

    companion object {
        private const val TAG = "ScreenCapture"
        const val ACTION_START = "com.carmirror.app.action.START"
        const val ACTION_STOP = "com.carmirror.app.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ENABLE_ENCODER = "enable_encoder"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIFICATION_ID = 42

        @Volatile private var instance: ScreenCaptureService? = null

        fun start(context: Context, resultCode: Int, data: Intent, enableEncoder: Boolean = false) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_ENABLE_ENCODER, enableEncoder)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Safe from any context (including the car session): no service start needed. */
        fun stop() {
            instance?.let { svc -> Handler(Looper.getMainLooper()).post { svc.stopMirroring() } }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: LowLatencyEncoder? = null
    private var encoderEnabled = false
    private var keepAwakeView: View? = null

    private val surfaceSink: (MirrorController.CarSurface?) -> Unit = { routeOutput(it) }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "Projection stopped by system/user")
            stopMirroring()
        }

        // API 34+: the captured content changed size (e.g. phone rotated).
        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.d(TAG, "Captured content resized to ${width}x$height")
            // Re-route so the encoder path picks up the new orientation.
            if (MirrorController.carSurface.value == null) routeOutput(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopMirroring()
            ACTION_START -> {
                startInForeground() // must precede getMediaProjection() on Android 14
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (code != Activity.RESULT_OK || data == null) {
                    stopMirroring()
                } else {
                    encoderEnabled = intent.getBooleanExtra(EXTRA_ENABLE_ENCODER, false)
                    startProjection(code, data)
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY // a projection token can't survive a process restart
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        teardownProjection() // a fresh consent replaces any previous session

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            Log.e(TAG, "getMediaProjection rejected", e); null
        } ?: run { stopMirroring(); return }

        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp

        val m = DisplayUtils.realMetrics(this)
        virtualDisplay = mp.createVirtualDisplay(
            "CarMirror",
            m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, // surface is attached later by routeOutput()
            null, mainHandler
        )

        MirrorController.setProjectionActive(true)
        MirrorController.addSurfaceSink(surfaceSink) // routes immediately if car is connected
        acquireKeepAwake()
        Log.i(TAG, "Projection started (${m.widthPixels}x${m.heightPixels} @${m.densityDpi}dpi)")
    }

    /**
     * Points the VirtualDisplay at the car surface, the encoder, or nothing.
     * Called on the main thread, synchronously from SurfaceCallback.
     */
    private fun routeOutput(car: MirrorController.CarSurface?) {
        val vd = virtualDisplay ?: return
        if (car != null) {
            // The system letterboxes the mirrored display into this size while
            // preserving aspect ratio; CoordinateMapper applies the same math.
            vd.resize(car.width, car.height, car.dpi)
            vd.surface = car.surface
            releaseEncoder()
            Log.i(TAG, "Output → car surface ${car.width}x${car.height} @${car.dpi}dpi")
            return
        }

        if (!encoderEnabled) {
            vd.surface = null
            releaseEncoder()
            return
        }

        val m = DisplayUtils.realMetrics(this)
        val enc = try {
            LowLatencyEncoder(m.widthPixels, m.heightPixels, onFrame = MirrorController::emitEncoded)
                .also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder start failed", e)
            vd.surface = null; releaseEncoder(); return
        }
        vd.resize(enc.width, enc.height, m.densityDpi)
        vd.surface = enc.inputSurface
        releaseEncoder()      // release the previous one, if any, after switching
        encoder = enc
        Log.i(TAG, "Output → H.264 encoder ${enc.width}x${enc.height}")
    }

    private fun releaseEncoder() {
        encoder?.release()
        encoder = null
    }

    private fun teardownProjection() {
        MirrorController.removeSurfaceSink(surfaceSink)
        virtualDisplay?.release()
        virtualDisplay = null
        releaseEncoder()
        projection?.let { mp ->
            projection = null // null first: stop() re-enters via onStop()
            mp.unregisterCallback(projectionCallback)
            mp.stop()
        }
    }

    fun stopMirroring() {
        teardownProjection()
        releaseKeepAwake()
        MirrorController.setProjectionActive(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopMirroring()
        instance = null
        super.onDestroy()
    }

    // ---- Foreground notification ------------------------------------------
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    // ---- Keep the phone panel on ------------------------------------------
    // If the phone display sleeps, the mirrored image goes black. A 1×1 invisible
    // overlay with FLAG_KEEP_SCREEN_ON prevents the timeout (it does not unlock).
    private fun acquireKeepAwake() {
        if (keepAwakeView != null || !Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java)
        val view = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, lp)
            keepAwakeView = view
        } catch (e: Exception) {
            Log.w(TAG, "Keep-awake overlay failed", e)
        }
    }

    private fun releaseKeepAwake() {
        keepAwakeView?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it) }
        }
        keepAwakeView = null
    }
}
