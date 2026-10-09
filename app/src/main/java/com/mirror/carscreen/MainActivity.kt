package com.mirror.carscreen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Phone-side onboarding: permissions, accessibility, start/stop mirroring. */
class MainActivity : AppCompatActivity() {

    private lateinit var statusAccessibility: TextView
    private lateinit var statusMirror: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refreshListener: () -> Unit = { refresh() }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
                handler.postDelayed({ refresh() }, 500)
            } else {
                Toast.makeText(this, "لم يتم السماح بتسجيل الشاشة", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusMirror = findViewById(R.id.statusMirror)

        findViewById<Button>(R.id.btnNotifications).setOnClickListener { requestNotifications() }

        findViewById<Button>(R.id.btnRestricted).setOnClickListener {
            // Android 13+ blocks accessibility for sideloaded apps until
            // "Allow restricted settings" is chosen from this screen's ⋮ menu.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "اختر «مرآة السيارة» وفعّلها", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startMirroring() }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            ScreenCaptureService.stop(this)
            handler.postDelayed({ refresh() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        MirrorBridge.addListener(refreshListener)
        refresh()
    }

    override fun onPause() {
        MirrorBridge.removeListener(refreshListener)
        super.onPause()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Toast.makeText(this, "الإشعارات مسموحة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMirroring() {
        if (!InputInjectorService.isEnabled(this)) {
            Toast.makeText(this, "تنبيه: التحكم باللمس غير مفعّل بعد (الخطوة ٣)", Toast.LENGTH_LONG).show()
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Forces "entire screen" (no single-app option) on Android 14+.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun refresh() {
        val touchOn = InputInjectorService.isEnabled(this)
        statusAccessibility.text =
            if (touchOn) "✅ التحكم باللمس: مفعّل" else "❌ التحكم باللمس: غير مفعّل"
        statusMirror.text =
            if (ScreenCaptureService.isMirroring) "✅ البث: يعمل" else "⏸ البث: متوقف"
    }
}
