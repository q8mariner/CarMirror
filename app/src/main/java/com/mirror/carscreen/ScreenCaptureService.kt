package com.mirror.carscreen

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Holds the MediaProjection and renders the phone screen DIRECTLY into the
 * Android Auto surface through an auto-mirroring VirtualDisplay.
 *
 * No MediaCodec encode/decode step is needed: the car surface is a local
 * Surface handed to us by the Android Auto host, so the compositor writes into
 * it with zero copies and the lowest possible latency.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val ACTION_START = "com.mirror.carscreen.START"
        private const val ACTION_STOP = "com.mirror.carscreen.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        val isMirroring: Boolean
            get() = instance?.projection != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            if (instance == null) return
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Android 14+: must already be a mediaProjection foreground service
                // before calling getMediaProjection().
                startAsForeground()
                val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data == null) {
                    stopMirroring()
                } else {
                    startProjection(code, data)
                }
            }
            ACTION_STOP -> stopMirroring()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(code: Int, data: Intent) {
        releaseProjection()
        stopping = false
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            stopMirroring()
            return
        }
        // Android 14+ requires the callback to be registered before createVirtualDisplay().
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopMirroring() }
            }
        }, main)
        projection = p
        acquireWakeLock()
        MirrorBridge.carSurface?.let { attach(it) }
        MirrorBridge.notifyChanged()
    }

    /** Called by MirrorBridge when the car surface appears, changes or disappears. */
    fun onCarSurfaceChanged(surface: MirrorBridge.CarSurface?) {
        main.post {
            if (surface == null) {
                virtualDisplay?.surface = null
            } else {
                attach(surface)
            }
        }
    }

    private fun attach(s: MirrorBridge.CarSurface) {
        val p = projection ?: return
        val vd = virtualDisplay
        if (vd == null) {
            // One VirtualDisplay per projection (Android 14 rule) – created once,
            // then resized / re-targeted whenever the car surface changes.
            virtualDisplay = try {
                p.createVirtualDisplay(
                    "CarMirror",
                    s.width, s.height, s.dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    s.surface, null, main
                )
            } catch (e: Exception) {
                null
            }
        } else {
            vd.resize(s.width, s.height, s.dpi)
            vd.surface = s.surface
        }
    }

    private fun stopMirroring() {
        if (stopping) return
        stopping = true
        releaseProjection()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        MirrorBridge.notifyChanged()
    }

    private fun releaseProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) { }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopping = true
        releaseProjection()
        instance = null
        MirrorBridge.notifyChanged()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        // Keep the phone display on: if it turns off, the mirror goes black.
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CarMirror:screen"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.stop), stopPi)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }
}
