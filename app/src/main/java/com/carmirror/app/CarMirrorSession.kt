package com.carmirror.app

import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

private const val TAG = "CarMirror"

/**
 * Android Auto entry point. Registered in the NAVIGATION category because that is
 * the only category whose apps receive a raw drawing Surface from the host.
 */
class CarMirrorAppService : CarAppService() {
    // Sideloaded build: accept any host (Android Auto with "Unknown sources", DHU).
    // For a store build, use HostValidator.Builder(...).addAllowedHosts(R.array.hosts_allowlist).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session = CarMirrorSession()
}

/**
 * One connection to the head unit. Receives the car Surface and car touch events.
 *
 * Output: the Surface is handed to ScreenCaptureService, whose VirtualDisplay renders
 * the mirrored phone screen straight into it.
 * Input:  SurfaceCallback gestures → GestureEngine → InputBackend → phone.
 */
class CarMirrorSession : Session() {

    private lateinit var engine: GestureEngine
    private var surface: Surface? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val s = container.surface ?: return
            surface = s
            engine.mapper.surfaceWidth = container.width
            engine.mapper.surfaceHeight = container.height
            Log.i(TAG, "Car surface ${container.width}x${container.height} @${container.dpi}dpi")
            MirrorController.attachCarSurface(
                MirrorController.CarSurface(s, container.width, container.height, container.dpi)
            )
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            // Synchronous: the VirtualDisplay lets go before the host frees the buffers.
            surface?.let(MirrorController::detachCarSurface)
            surface = null
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            Log.d(TAG, "Visible area $visibleArea")
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            Log.d(TAG, "Stable area $stableArea")
        }

        // Touch input from the car display (all coordinates in surface pixels).
        override fun onClick(x: Float, y: Float) = engine.submit(CarTouch.Click(x, y))
        override fun onScroll(distanceX: Float, distanceY: Float) = engine.submit(CarTouch.Scroll(distanceX, distanceY))
        override fun onFling(velocityX: Float, velocityY: Float) = engine.submit(CarTouch.Fling(velocityX, velocityY))
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) =
            engine.submit(CarTouch.Scale(focusX, focusY, scaleFactor))
    }

    override fun onCreateScreen(intent: Intent): Screen {
        engine = GestureEngine(carContext, lifecycleScope)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                surface?.let(MirrorController::detachCarSurface)
                surface = null
            }
        })
        return MirrorScreen(carContext)
    }
}

/** Shows the mirror (NavigationTemplate over the surface) or a consent prompt. */
class MirrorScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorController.projectionActive.collect { invalidate() }
            }
        }
        // Opening the app in the car while not mirroring: bring the consent dialog up
        // on the phone straight away (needs "Display over other apps" for the launch).
        if (!MirrorController.projectionActive.value && Settings.canDrawOverlays(carContext)) {
            launchPhoneConsent()
        }
    }

    override fun onGetTemplate(): Template {
        if (!MirrorController.projectionActive.value) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_need_consent))
                .setTitle(carContext.getString(R.string.app_name))
                .setHeaderAction(Action.APP_ICON)
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.car_open_phone))
                        .setOnClickListener { launchPhoneConsent() }
                        .build()
                )
                .build()
        }

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.stop))
                            .setOnClickListener { ScreenCaptureService.stop() }
                            .build()
                    )
                    .build()
            )
            // PAN lets the host forward drag gestures (onScroll/onFling) to the surface.
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { inPanMode -> Log.d(TAG, "Pan mode: $inPanMode") }
            .build()
    }

    private fun launchPhoneConsent() {
        val intent = Intent(carContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_REQUEST_PROJECTION, true)
        try {
            carContext.applicationContext.startActivity(intent)
            CarToast.makeText(carContext, carContext.getString(R.string.car_check_phone), CarToast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not open phone activity", e)
        }
    }
}
