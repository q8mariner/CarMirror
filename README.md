# Car Mirror

Mirrors a Samsung phone's full screen into Android Auto (wired USB) and sends car-screen
touches back to the phone.

```
Phone display ──MediaProjection──▶ VirtualDisplay(AUTO_MIRROR) ──▶ car Surface (Car App Library)
                                                                      │  Android Auto encodes + streams over USB
Car touchscreen ──SurfaceCallback──▶ GestureEngine ──CoordinateMapper──▶ AccessibilityService.dispatchGesture()
                                                                      └▶ (optional) ShellInputBackend via Shizuku/ADB
```

## Build

Requirements: JDK 17+, Android SDK with platform 34 and build-tools 34.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"      # macOS; Linux: $HOME/Android/Sdk
# or: echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Windows: `gradlew.bat assembleDebug`.

## Install

```bash
adb devices                                            # confirm the phone is listed
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Optional shortcuts (skip the Settings screens):
adb shell appops set com.carmirror.app SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.carmirror.app android.permission.POST_NOTIFICATIONS
# NOTE: this replaces any other enabled accessibility services
adb shell settings put secure enabled_accessibility_services com.carmirror.app/com.carmirror.app.InputInjectorService
adb shell settings put secure accessibility_enabled 1
```

## Phone setup

1. Open Car Mirror and complete the four steps.
2. Samsung / Android 13+ sideloaded apps: if the Accessibility toggle is greyed out,
   App info → ⋮ → **Allow restricted settings**.
3. Android Auto: Settings → tap **Version** 10× → ⋮ → **Developer settings** → enable
   **Unknown sources**.
4. Plug into the car, open **Car Mirror** from the car's launcher, accept the capture
   dialog on the phone (choose **Entire screen**).

Test without a car using the Desktop Head Unit (SDK Manager → Android Auto DHU):

```bash
# Android Auto → Developer settings → Start head unit server
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

## Known platform limits

- The Car App Library delivers gestures (tap, scroll, fling, pinch), not raw multi-touch
  pointers; arbitrary multi-finger gestures can't reach the phone through Android Auto.
- Tap (`onClick`) needs a Car API level 5 host; drags need the pan mode (PAN button).
- DRM / FLAG_SECURE content (Netflix, banking apps) mirrors as black.
- The phone must stay unlocked; a keep-awake overlay stops the screen timing out.
- Mirroring arbitrary apps is for use while parked; Google may change Android Auto so that
  sideloaded surface apps stop working.
