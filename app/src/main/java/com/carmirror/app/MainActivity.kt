package com.carmirror.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Permission onboarding (4 steps) and mirroring start/stop.
 * Launched from the car with [EXTRA_REQUEST_PROJECTION] to show the consent dialog.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REQUEST_PROJECTION = "request_projection"
    }

    private lateinit var statusNotifications: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusMirror: TextView
    private lateinit var btnNotifications: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
            } else {
                Toast.makeText(this, R.string.consent_denied, Toast.LENGTH_SHORT).show()
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusNotifications = findViewById(R.id.statusNotifications)
        statusOverlay = findViewById(R.id.statusOverlay)
        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusMirror = findViewById(R.id.statusMirror)
        btnNotifications = findViewById(R.id.btnNotifications)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        btnStart.setOnClickListener { requestProjection() }
        btnStop.setOnClickListener { ScreenCaptureService.stop() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorController.projectionActive.collect { refresh() }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh() // the user may be returning from a Settings screen
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_PROJECTION, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_PROJECTION)
            if (!MirrorController.projectionActive.value) requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: pre-select "Entire screen" rather than single-app sharing.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    private fun refresh() {
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = isAccessibilityServiceEnabled()
        val running = MirrorController.projectionActive.value

        statusNotifications.text = line(R.string.step_notifications, notifOk)
        statusOverlay.text = line(R.string.step_overlay, overlayOk)
        statusAccessibility.text = line(R.string.step_accessibility, a11yOk)
        statusMirror.text = "${getString(R.string.step_mirror)}  " +
            getString(if (running) R.string.status_running else R.string.status_stopped)

        btnNotifications.isEnabled = !notifOk
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun line(titleRes: Int, ok: Boolean) =
        "${getString(titleRes)}  ${getString(if (ok) R.string.status_ok else R.string.status_missing)}"

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, InputInjectorService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
}
