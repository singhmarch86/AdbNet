# mobile-shield

A defensive Android safety app, built for **your own device, or a child's with
their knowledge**. It is not built to covertly monitor another adult.

## Modules

1. **Content filter** — an on-device DNS filter (Android `VpnService`) that
   forwards lookups to a **family-filtering upstream resolver** (Cloudflare for
   Families `1.1.1.3`), so adult content and malware are blocked against a real,
   constantly-updated list rather than a hardcoded one. It also enforces
   SafeSearch (CNAME rewrite), applies local allow/block overrides, and blocks
   known DNS-over-HTTPS bootstrap hosts that apps use to dodge DNS filtering.
   Only DNS lookups are inspected; page content never leaves the device. Being
   DNS-level, it can't filter adult content *within* an allowed general site
   (Reddit/X/etc.) — that needs the in-app NSFW-classifier browser (planned).
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
6. **QR payload analyzer** — classify any decoded QR (UPI / URL / Wi-Fi / tel /
   SMS / …) and score scam risk: UPI payloads reuse the UPI analyzer; URLs are
   checked for shorteners, raw-IP, `.apk`, punycode/homograph hosts, embedded
   credentials and brand-on-a-bad-domain; Wi-Fi / tel / SMS get targeted checks.
5. **Smishing classifier** — score an SMS / notification for phishing signs,
   offline and multilingual (English + Hindi/Hinglish, romanized and
   Devanagari): shortened / raw-IP / APK links, urgency or reward bait,
   requests to share OTP/PIN/CVV, KYC bait, and personal-number senders.
   Conservative about the OTP false positive — a genuine "your OTP is …, do
   not share" is not flagged.
7. **Fraud reputation (privacy-preserving)** — a serializable Bloom filter of
   reported-fraud VPAs / hosts that is downloaded once and queried entirely
   on-device, so a payee or website is checked without ever being sent to a
   server. No false negatives; a tunable false-positive rate; stable hashing so
   a filter built anywhere matches everywhere.
8. **Enforcement policy (Device Owner)** — decides which OS-level lockdowns to
   apply from a protection profile (Standard / Child / High-security) plus live
   risk: force-VPN-always-on, block sideloading, lock SafeSearch, disallow
   screen capture / debugging / safe-boot, remove remote-access apps. The
   Android `DevicePolicyManager` layer executes these when the app is device
   owner; without that, every action degrades honestly to a warning.

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
  the shared matcher, and a Compose UI with two tabs — **Protect** (filter
  toggle + UPI checker, plus share-to-check for UPI links) and **Checkup**,
  which collects installed apps and downloaded APKs via `PackageManager` /
  `MediaStore` and runs them through the shared hygiene + APK analyzers. It also
  includes **live payment protection**: a foreground-service monitor that, when
  a UPI/banking app opens, feeds live context (active call, screen-share
  heuristic, device-hygiene risk) into the shared `ScamGuard` and warns via an
  overlay — with an optional, opt-in accessibility service for better detection.
  Also a **message checker** (paste an SMS, no permission needed) and an
  optional incoming-SMS auto-scan receiver, both using the shared
  `SmishingClassifier`. For managed/child devices, a **Device Owner** layer
  (`DeviceAdminReceiver` + `DevicePolicyManager`) executes the shared
  `EnforcementPolicyEngine`'s plan — locking the filter on, blocking
  sideloading, etc. — degrading to warnings when not provisioned as owner.
  Kotlin.
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
