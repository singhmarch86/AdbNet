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
3. *(later)* Device-hygiene warnings — flag sideloaded apps holding SMS /
   overlay / accessibility permissions, and active screen-sharing/remote tools.

## What it honestly cannot do

- See inside sites/apps it allows (Reddit, X, Telegram).
- Filter apps that ship their own encrypted DNS it can't intercept.
- Block individual images system-wide (that needs an on-device classifier).
- Stop a UPI payment or read inside a UPI app — that is the bank's and NPCI's
  domain. mobile-shield only warns *before* the user acts.
- On a normal phone the user can turn the VPN off; locking that down on a
  child's phone needs Family Link or device-owner mode.

## Layout

- `core/` — pure Kotlin/JVM logic, no Android dependency, fully unit-tested:
  the domain blocklist matcher and the UPI link analyzer. This is the part that
  can be verified in CI on any machine.
- *(later)* `app/` — the Android shell: the `VpnService`, QR scanning, and UI.

## Principles

- Own device, or a child's with their knowledge. No covert tracking.
- No third-party blocklists bundled until their licenses are checked.
- Android SDK components are only downloaded with the maintainer's go-ahead.

## Build

```
cd mobile-shield
./gradlew :core:test
```
