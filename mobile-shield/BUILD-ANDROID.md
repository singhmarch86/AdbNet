# Building the Mobile Shield Android app on your own computer

The shared `core` logic is built and unit-tested in CI. The Android app
(`androidApp/`) is kept out of the default build because its Android Gradle
Plugin + SDK come from Google's servers, which the cloud sandbox blocks. On
your own machine those are reachable, so you can build and run it.

> Heads-up: the Android code has not been through a compiler yet (it was
> written where the Android SDK isn't reachable). Expect a few compile errors
> on the first build — paste them to Claude and they're quick to fix.

## 1. Prerequisites

- **Android Studio** (latest stable — Koala/Ladybug or newer). It bundles the
  Android SDK and a JDK. This is the easiest path.
  - Or, command line: **JDK 17+** and the **Android command-line tools**, with
    `ANDROID_HOME` set.
- A phone with **USB debugging** on, or an **emulator** (Android 8.0 / API 26 or
  newer — the app's `minSdk`).

## 2. Get the code

```bash
git clone https://github.com/singhmarch86/AdbNet.git
cd AdbNet
git checkout claude/wizardly-newton-c6ff9l
cd mobile-shield
```

## 3. Turn the Android module on

Open `mobile-shield/settings.gradle.kts` and **uncomment** the last line:

```kotlin
include(":androidApp")
```

(Building with `-Pmobileshield.android=true`, below, switches on the shared
`core` module's Android target so the app can consume it.)

## 4a. Build & run from Android Studio (recommended)

1. **Open** the `mobile-shield/` folder in Android Studio (open the folder that
   contains `settings.gradle.kts`, not the repo root).
2. Add the property: create/edit `mobile-shield/gradle.properties` and add a
   line `mobileshield.android=true` (so Studio always builds with it).
3. Let Gradle sync. Fix any sync/compile errors (or send them to Claude).
4. Pick your device/emulator and press **Run** ▶.

## 4b. Build from the command line

```bash
# from mobile-shield/
./gradlew :core:jvmTest                                   # shared logic tests
./gradlew :androidApp:assembleDebug -Pmobileshield.android=true
# APK output:
#   androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## 5. Using the app (grant the permissions it asks for)

- **Protect tab**
  - *Content filter* → "Turn on filter" → approve the **VPN** connection prompt.
  - *UPI checker* → paste a `upi://pay?...` link (or share one to the app) → Check.
  - *Payment protection (beta)* → grant **usage access**, then **display over
    other apps**; optionally enable the **accessibility** service for stronger
    detection.
- **Checkup tab** → "Scan this device" → (on Android ≤ 12 it asks for storage
  to scan Downloads).

## 6. If the build fails

Copy the full error (the `> Task :androidApp:...` block, or the Gradle sync
error) and send it to Claude. Common first-build fixes are version bumps
(AGP/Compose/androidx) or small API tweaks — fast to resolve.

## Notes / known limits (by design)

- SafeSearch rewrite and DNS blocking work via a **local** VPN; no traffic
  leaves the device except forwarded DNS for allowed names.
- The app can **warn** about risky APKs and remote-access scams; Android does
  not let a normal app **block** an install or another app, or record call
  audio — those are OS-vendor only.
- Locking the filter so a child cannot disable it needs **Family Link** /
  device-owner mode; this build does not set that up.
