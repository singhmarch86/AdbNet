# mobile-shield

A defensive Android safety app, built for **your own device, or a child's with
their knowledge**. It is not built to covertly monitor another adult.

## Modules

1. **Content filter** — an on-device DNS filter (Android `VpnService`, local
   only, no traffic leaves the phone) that blocks adult/unwanted domains,
   forces SafeSearch, and blocks known DNS-over-HTTPS bootstrap hosts that apps
   use to dodge DNS filtering.
2. **UPI scam protection** — inspects a UPI link / QR *before* you act on it and
   warns about collect (pull-money) requests, malformed or unknown payee
   handles, high amounts, scam-bait notes, and tampered links.
3. **Device-hygiene warnings** — flag screen-sharing/remote-access apps, apps
   with an enabled accessibility service, and sideloaded apps holding SMS /
   overlay / device-admin / notification-listener / installer powers.
4. **Downloaded-APK scanner** — warn about APKs in Downloads before you
   sideload them: disguised `document.pdf.apk` names, unsigned packages,
   dangerous requested permissions, tiny droppers, MIME mismatches, brand
   impersonation, and unknown download sources.

## What it honestly cannot do

- See inside sites/apps it allows (Reddit, X, Telegram).
- Filter apps that ship their own encrypted DNS it can't intercept.
- Block individual images system-wide (that needs an on-device classifier).
- Stop a UPI payment or read inside a UPI app — that is the bank's and NPCI's
  domain. mobile-shield only warns *before* the user acts.
- On a normal phone the user can turn the VPN off; locking that down on a
  child's phone needs Family Link or device-owner mode.

## Layout

- `core/` — a **Kotlin Multiplatform** module holding all shared logic with no
  platform dependency: the domain blocklist matcher, the UPI link analyzer, and
  the small pure-Kotlin helpers (IDNA/punycode, decimal, percent-decode) that
  replace their JVM-only equivalents so the code compiles for both Android and
  iOS. Fully unit-tested via `commonTest`; the JVM target runs the tests in CI
  on any host. Apple (`iosX64`/`iosArm64`/`iosSimulatorArm64`) targets are
  declared on a macOS host, where the iOS framework is produced.
- `androidApp/` — the Android shell: a local `VpnService` DNS filter wired to
  the shared matcher, a Compose UI (filter toggle + UPI checker), and share-to-
  check for UPI links. Kotlin.
- `iosApp/` — the iOS shell: a SwiftUI app and a NetworkExtension DNS proxy,
  both calling the shared `core` framework. Swift.

## Principles

- Own device, or a child's with their knowledge. No covert tracking.
- No third-party blocklists bundled until their licenses are checked.
- Android SDK components are only downloaded with the maintainer's go-ahead.

## Build

**Shared logic (any host — Linux, macOS, Windows):**

```
cd mobile-shield
./gradlew :core:jvmTest
```

**Android app** (needs the Android SDK and access to Google's Maven, i.e.
`dl.google.com`). It is kept out of the default build so the shared module
stays buildable in restricted/CI environments. To build it:

1. Uncomment `include(":androidApp")` in `settings.gradle.kts`.
2. Point `ANDROID_HOME` / `local.properties` at your SDK.
3. `./gradlew :androidApp:assembleDebug -Pmobileshield.android=true`

The `-Pmobileshield.android=true` flag switches on the shared module's Android
target (see `core/build.gradle.kts`).

**iOS app** (macOS + Xcode only) — see `iosApp/README.md`. The shared `core`
builds to `MobileShieldCore.framework` via
`./gradlew :core:linkDebugFrameworkIosArm64` and related tasks on a Mac.
