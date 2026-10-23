# iosApp — iOS shell for mobile-shield

This is the iOS counterpart to `androidApp/`. It reuses the **same shared
`core`** (domain matcher, UPI analyzer, DNS parsing) via a Kotlin/Native
framework, and adds the two iOS-specific pieces that have no Android analogue:
a SwiftUI app and a **NetworkExtension** DNS proxy.

> These Swift sources are kept here as the iOS shell. They are **not built in
> CI** — Apple frameworks and apps can only be compiled on macOS with Xcode.
> On Linux the shared logic is still fully validated through `:core:jvmTest`.

## Building the shared framework (on macOS)

```
cd mobile-shield
./gradlew :core:linkDebugFrameworkIosArm64          # device
./gradlew :core:linkDebugFrameworkIosSimulatorArm64 # simulator (Apple silicon)
```

This produces `MobileShieldCore.framework`. In Xcode, either:
- add a "Run Script" build phase that calls the Gradle task and embeds the
  framework, or
- use the KMP `embedAndSignAppleFrameworkForXcode` task (recommended) wired
  into the app target's build phases.

Then `import MobileShieldCore` from Swift. Kotlin maps to Swift as:
- `UpiLinkAnalyzer()` → `UpiLinkAnalyzer()` (defaults supplied)
- `DomainBlocklistMatcher(...)` → same, named args
- `object DnsMessage` → `DnsMessage.shared`
- enums (`RiskLevel`, `FilterAction`) → Swift enums

## Xcode project layout to create

Create an Xcode project `MobileShield` with two targets:

1. **MobileShield** (SwiftUI app) — uses `MobileShield/` sources here.
2. **DnsFilterExtension** (App Extension, *Network Extension* → *DNS Proxy
   Provider*) — uses `DnsFilterExtension/` sources here.

Capabilities / entitlements:
- App + extension: **Network Extensions** → *DNS Proxy* (`com.apple.networkextension.dns-proxy`).
- Install the proxy via `NEDNSProxyManager` (the app saves a configuration the
  user approves in Settings).

## What iOS can and can't do (same honesty as the rest of the project)
- A per-device DNS proxy works for the user's own phone. **Locking it down on a
  child's device** (so it can't be turned off) requires Screen Time or a
  supervised/MDM profile — the iOS analogue of Android's Family Link.
- UPI link checking works the same way via the shared analyzer, triggered from
  the app or a Share Extension.
