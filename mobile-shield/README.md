# mobile-shield

[![core tests](https://github.com/singhmarch86/AdbNet/actions/workflows/mobile-shield-core.yml/badge.svg?branch=claude/wizardly-newton-c6ff9l)](https://github.com/singhmarch86/AdbNet/actions/workflows/mobile-shield-core.yml)

A privacy-first, on-device **Android safety app** — adult-content filtering, UPI
scam protection, and device-hygiene checks — built for **your own device, or a
child's with their knowledge**. It is not built to covertly monitor another
adult.

The scam/filter logic is a **Kotlin Multiplatform** core (shared by Android and
iOS) with **118 passing unit tests**; the Android app is a Jetpack Compose shell
around it.

## Status

| Area | State |
|---|---|
| Shared `core` logic (8 modules) | ✅ Built & unit-tested (118 tests, CI) |
| Android app builds & runs | ✅ Verified on emulator |
| DNS content filter (family resolver) | ✅ Runs; VPN establishes |
| UPI / QR / message scam checkers | ✅ Working in-app |
| Device Checkup (apps + APKs) | ✅ Working (with permission grants) |
| Live payment-moment guard | ⚙️ Wired; needs real-scenario device testing |
| In-app safe browser (NSFW image filter) | ⚙️ Pipeline built; **model supplied separately** (`tools/`) |
| Fraud reputation (Bloom filter) | ⚙️ Wired; activates when a fraud list is loaded |
| Device Owner enforcement | ⚙️ Wired; enforces only when provisioned as owner |
| iOS app | 📄 Swift source only (builds on macOS; not run) |
| Published to an app store | ❌ No — this is a working prototype |

## Modules (`core`)

1. **Content filter** — on-device DNS filter (`VpnService`) that forwards lookups
   to a **user-selectable family-filtering resolver** (Cloudflare for Families,
   CleanBrowsing Family, OpenDNS FamilyShield, or malware-only), so adult content
   and malware are blocked against a real, constantly-updated list. Also enforces
   SafeSearch (CNAME rewrite), applies local allow/block overrides, and blocks
   known DNS-over-HTTPS bootstrap hosts. Only DNS lookups are inspected; page
   content never leaves the device.
2. **UPI scam protection** — inspects a UPI link / QR *before* you act on it:
   collect (pull-money) requests, malformed or unknown payee handles, high
   amounts, scam-bait notes, and tampered links.
3. **QR payload analyzer** — classifies any decoded QR (UPI / URL / Wi-Fi / tel /
   SMS) and scores risk: UPI payloads reuse the UPI analyzer; URLs are checked
   for shorteners, raw-IP, `.apk`, punycode/homograph hosts, embedded credentials
   and brand-on-a-bad-domain.
4. **Smishing classifier** — scores an SMS for phishing, offline and multilingual
   (English + Hindi/Hinglish, romanized and Devanagari): risky links, urgency /
   reward bait, requests to share OTP/PIN/CVV, KYC bait, personal-number senders.
   Conservative about the OTP false positive.
5. **Device-hygiene** — flags screen-sharing / remote-access apps, enabled
   accessibility services, and sideloaded apps holding SMS / overlay /
   device-admin / notification-listener / installer powers.
6. **Downloaded-APK scanner** — warns about APKs in Downloads before sideloading:
   disguised `document.pdf.apk` names, unsigned packages, dangerous permissions,
   tiny droppers, MIME mismatches, brand impersonation, unknown sources.
7. **Scam-moment risk engine (`ScamGuard`)** — fuses UPI risk + device hygiene +
   live call + screen-share + first-time-payee + fraud reputation into one
   explainable ALLOW / WARN / BLOCK verdict at the moment of payment.
8. **Fraud reputation** — a serializable Bloom filter of reported-fraud VPAs /
   hosts, queried entirely on-device (no per-lookup network call).
9. **Enforcement policy (Device Owner)** — decides which OS-level lockdowns to
   apply from a protection profile (Standard / Child / High-security) plus live
   risk; the Android `DevicePolicyManager` layer executes them when provisioned.

## What it honestly cannot do

- See inside sites/apps it allows (Reddit, X, Telegram) — DNS-level only. The
  in-app safe browser adds pixel-level image filtering, but only within itself.
- Filter apps that ship their own encrypted DNS it can't intercept.
- Stop a UPI payment or read inside a UPI app — that is the bank's / NPCI's
  domain. mobile-shield only warns *before* the user acts.
- Enforce anything un-bypassably on a normal phone — that needs Device Owner /
  Family Link provisioning.
- Record call audio or block another app's install (OS-vendor only).

## Layout

- `core/` — **Kotlin Multiplatform** shared logic, no platform dependency, fully
  unit-tested (`commonTest`). JVM target runs in CI on any host; Apple targets
  (`iosX64`/`iosArm64`/`iosSimulatorArm64`) build the iOS framework on macOS.
- `androidApp/` — Jetpack Compose shell (Kotlin). Three tabs: **Protect** (filter
  toggle + resolver chooser + UPI checker + QR scanner + payment-protection),
  **Browser** (in-app safe browser with on-device NSFW image filtering), and
  **Checkup** (installed-app hygiene + APK scan + message checker). Plus the
  `VpnService`, the live payment guard, the SMS receiver, and the Device Owner
  admin.
- `iosApp/` — SwiftUI app + NetworkExtension DNS proxy + Message Filter
  extension, calling the shared `core` framework (Swift; builds on macOS).
- `tools/` — script + guide to convert a GantMan NSFW model to the TFLite format
  the safe browser uses.

## Principles

- Own device, or a child's with their knowledge. No covert tracking.
- No third-party blocklists or models bundled until their licenses are checked.
- Say plainly what is verified and what isn't.

## Build

**Shared logic (any host — this is what CI runs):**

```
cd mobile-shield
./gradlew :core:jvmTest
```

**Android app** (needs the Android SDK + access to Google's Maven). The app
module is gated behind a flag so the shared module stays buildable where Google
is blocked. Build it with:

```
./gradlew :androidApp:assembleDebug -Pmobileshield.android=true
```

For Android Studio, add `mobileshield.android=true` to `~/.gradle/gradle.properties`
so the app module is included on sync. Full guide: `BUILD-ANDROID.md`.

**iOS app** (macOS + Xcode only) — see `iosApp/README.md`.
