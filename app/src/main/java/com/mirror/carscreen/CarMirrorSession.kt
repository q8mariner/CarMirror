package com.mirror.carscreen

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Android Auto entry point (declared as a NAVIGATION app to get a full drawing surface). */
class CarMirrorService : CarAppService() {
    // Personal sideloaded app used with Android Auto developer mode / unknown sources.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarMirrorSession()
}

class CarMirrorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = MirrorScreen(carContext)
}

/**
 * The car screen. The surface we receive here is handed to ScreenCaptureService,
 * which renders the phone display into it. Touches on the car screen arrive as
 * SurfaceCallback events and are converted to phone coordinates and injected.
 */
class MirrorScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private val handler = Handler(Looper.getMainLooper())

    private var carW = 0
    private var carH = 0
    private var longPressArmed = false

    // swipe / scroll accumulation (Android Auto sends many small deltas)
    private var scrollDx = 0f
    private var scrollDy = 0f
    private val flushScroll = Runnable { performScroll(0f, 0f, fling = false) }

    // pinch accumulation
    private var scaleAccum = 1f
    private var scaleFocusX = 0f
    private var scaleFocusY = 0f
    private val flushScale = Runnable { performPinch() }

    private val bridgeListener: () -> Unit = { invalidate() }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                MirrorBridge.addListener(bridgeListener)
                if (!ScreenCaptureService.isMirroring) {
                    CarToast.makeText(
                        carContext,
                        "افتح «مرآة السيارة» في الجوال واضغط تشغيل البث",
                        CarToast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                MirrorBridge.removeListener(bridgeListener)
                handler.removeCallbacksAndMessages(null)
                MirrorBridge.setCarSurface(null)
            }
        })
    }

    // ---------------- template (on-screen buttons) ----------------

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_back) { InputInjectorService.instance?.back() })
            .addAction(iconAction(R.drawable.ic_home) { InputInjectorService.instance?.home() })
            .addAction(iconAction(R.drawable.ic_recents) { InputInjectorService.instance?.recents() })
            .addAction(
                iconAction(R.drawable.ic_touch, if (longPressArmed) CarColor.GREEN else null) {
                    longPressArmed = !longPressArmed
                    CarToast.makeText(
                        carContext,
                        if (longPressArmed) "اللمسة التالية ستكون ضغطًا مطوّلًا" else "تم إلغاء الضغط المطوّل",
                        CarToast.LENGTH_SHORT
                    ).show()
                    invalidate()
                }
            )
            .build()

        val builder = NavigationTemplate.Builder()
            .setActionStrip(strip)
            // PAN button: enables swipe / scroll events from the car screen
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { _ -> }

        val mirroring = ScreenCaptureService.isMirroring
        val touch = InputInjectorService.instance != null
        if (!mirroring || !touch) {
            val title = if (!mirroring) "البث متوقف" else "التحكم باللمس غير مفعّل"
            val text = if (!mirroring) {
                "افتح تطبيق «مرآة السيارة» على الجوال واضغط «تشغيل البث» واختر الشاشة بالكامل"
            } else {
                "فعّل خدمة «مرآة السيارة» من إعدادات إمكانية الوصول في الجوال"
            }
            builder.setNavigationInfo(MessageInfo.Builder(title).setText(text).build())
        }
        return builder.build()
    }

    private fun iconAction(res: Int, tint: CarColor? = null, onClick: () -> Unit): Action {
        val icon = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).apply {
            if (tint != null) setTint(tint)
        }.build()
        return Action.Builder()
            .setIcon(icon)
            .setOnClickListener { onClick() }
            .build()
    }

    // ---------------- surface ----------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        carW = surfaceContainer.width
        carH = surfaceContainer.height
        MirrorBridge.setCarSurface(
            MirrorBridge.CarSurface(surface, surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
        )
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        MirrorBridge.setCarSurface(null)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    // ---------------- touch input from the car ----------------

    /** Tap on the car screen (Car API level 5+). */
    override fun onClick(x: Float, y: Float) {
        val injector = InputInjectorService.instance ?: return
        val phone = PhoneDisplay.realSize(carContext)
        val p = CoordinateMapper.carToPhone(x, y, carW, carH, phone.x, phone.y) ?: return
        if (longPressArmed) {
            longPressArmed = false
            injector.longPress(p.x, p.y)
            invalidate()
        } else {
            injector.tap(p.x, p.y)
        }
    }

    /** Drag on the car screen. Deltas follow GestureDetector: previous - current. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        scrollDx += distanceX
        scrollDy += distanceY
        handler.removeCallbacks(flushScroll)
        handler.postDelayed(flushScroll, 70L)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        handler.removeCallbacks(flushScroll)
        performScroll(velocityX, velocityY, fling = true)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        scaleFocusX = focusX
        scaleFocusY = focusY
        scaleAccum *= scaleFactor
        handler.removeCallbacks(flushScale)
        handler.postDelayed(flushScale, 120L)
    }

    private fun performScroll(velocityX: Float, velocityY: Float, fling: Boolean) {
        val dx = scrollDx
        val dy = scrollDy
        scrollDx = 0f
        scrollDy = 0f
        val injector = InputInjectorService.instance ?: return
        val phone = PhoneDisplay.realSize(carContext)
        if (carW <= 0 || carH <= 0) return
        val s = CoordinateMapper.scale(carW, carH, phone.x, phone.y)

        // finger movement on the phone, in phone pixels
        var mx = -dx / s
        var my = -dy / s
        val duration: Long
        if (fling) {
            mx += velocityX / s * 0.08f
            my += velocityY / s * 0.08f
            duration = 90L
        } else {
            duration = (hypot(mx, my) / 1.2f).toLong().coerceIn(120L, 500L)
        }
        // keep the gesture on screen
        val maxX = phone.x * 0.9f
        val maxY = phone.y * 0.9f
        if (abs(mx) > maxX) mx = maxX * if (mx > 0) 1 else -1
        if (abs(my) > maxY) my = maxY * if (my > 0) 1 else -1
        if (hypot(mx, my) < 8f) return

        val sx = phone.x / 2f - mx / 2f
        val sy = phone.y / 2f - my / 2f
        injector.swipe(sx, sy, sx + mx, sy + my, duration)
    }

    private fun performPinch() {
        val factor = scaleAccum
        scaleAccum = 1f
        val injector = InputInjectorService.instance ?: return
        if (abs(factor - 1f) < 0.05f) return
        val phone = PhoneDisplay.realSize(carContext)
        val focus = CoordinateMapper.carToPhone(scaleFocusX, scaleFocusY, carW, carH, phone.x, phone.y)
        val cx = focus?.x ?: (phone.x / 2f)
        val cy = focus?.y ?: (phone.y / 2f)
        val maxSpan = min(phone.x, phone.y) * 0.9f
        val start = if (factor > 1f) maxSpan / 4f else maxSpan * 0.8f
        val end = (start * factor).coerceIn(40f, maxSpan)
        injector.pinch(cx, cy, start, end)
    }
}
