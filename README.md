<div align="center">

# clipsyncd

**Bidirectional clipboard synchronisation between macOS and Android over a local network.**

Two daemons, one TCP port, and no cloud service, account, or third-party server in the path.

<p>
  <img alt="Python" src="https://img.shields.io/badge/Python-3-1c1c1e?style=flat-square&logo=python&logoColor=3776AB" />
  <img alt="macOS" src="https://img.shields.io/badge/macOS-launchd-1c1c1e?style=flat-square&logo=apple&logoColor=white" />
  <img alt="Android" src="https://img.shields.io/badge/Android-Kotlin%20%2B%20Shizuku-1c1c1e?style=flat-square&logo=android&logoColor=3DDC84" />
  <img alt="Dependencies" src="https://img.shields.io/badge/mac%20side-stdlib%20only-1c1c1e?style=flat-square" />
</p>

</div>

---

## Overview

Clipboard synchronisation typically requires an account and routes your data through a third party. clipsyncd keeps the exchange entirely on your own network: one device opens a TCP connection to the other, transmits a single framed message, and closes it. The Mac side is 156 lines of standard-library Python; the Android side is a small native Kotlin app (`android-app/`) — it used to be a Termux script, but that approach turned out to be fundamentally incompatible with how Android gates background clipboard access (see [Architecture](#architecture)).

Copy a URL on a laptop and paste it on a phone half a second later. Copy a one-time code on a phone and paste it on a laptop.

| Condition | Behaviour |
|---|---|
| Copy on either device | Available on the other within approximately 0.5s |
| Mac's IP address changes | The phone re-resolves it over mDNS on the next failure |
| Phone's IP address changes | The Mac relearns it from the next inbound connection, within 30s |
| Mac reboots | `launchd` restarts the daemon |
| Phone reboots | `SyncService` restarts automatically; Shizuku needs one tap to restart (see [Android](#3-android)) |
| VPN enabled | Unaffected — LAN traffic does not enter the tunnel |
| Identical text copied twice | No transmission; nothing changed |

## Requirements

**macOS** — Python 3 (the system interpreter is sufficient).

**Android** — [Shizuku](https://github.com/RikkaApps/Shizuku), installed once and paired once (see below). No root required.

## Installation

### 1. Generate a shared secret

Optional but recommended. Without one, any host on the local network can write to your clipboard.

```sh
python3 -c "import secrets; print(secrets.token_hex(32))"
```

This value must be set as `CLIPSYNCD_SECRET` on both devices. When present, every message carries an HMAC-SHA256 tag and unverified messages are discarded.

### 2. macOS

```sh
sudo cp clipsyncd_mac.py /usr/local/bin/clipsyncd.py
sudo chmod 755 /usr/local/bin/clipsyncd.py
```

Create `~/Library/LaunchAgents/com.user.clipsyncd.plist` with `RunAtLoad` and `KeepAlive` enabled, and an `EnvironmentVariables` dictionary containing the secret. Then:

```sh
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.user.clipsyncd.plist
hostname     # required for the Android configuration
```

### 3. Android

**Install Shizuku** from its [GitHub releases](https://github.com/RikkaApps/Shizuku/releases) (or F-Droid), then pair it once:

1. Settings → Developer options → enable **Wireless debugging**.
2. Open Shizuku → **Start via Wireless debugging** → **Pairing**, follow the on-device flow.
3. Tap **Start**. Shizuku now shows "running" and stays pairable after future reboots — no computer needed, just reopen Shizuku and tap Start again.
4. In Shizuku, grant clipsyncd's permission request when the app asks for it (see step 6).

**Build and install the app** (needs the Android SDK — command-line tools are enough, Android Studio not required):

```sh
cd android-app
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

5. Open the app, enter the Mac's LAN IP (`ifconfig`/`ipconfig getifaddr en0` on the Mac — not the `.local` hostname; Android's plain DNS resolver does not do mDNS, see [Architecture](#architecture)) and the shared secret, tap **Save**.
6. Tap **Grant Shizuku permission**, allow it.
7. Tap **Start sync service**.

A static IP only holds if the Mac's DHCP lease doesn't change; setting a DHCP reservation for the Mac's MAC address on your router avoids that.

## Architecture

Both sides run the same structure: a watcher polling the clipboard every 0.5s, and a server accepting connections on port `59876`. On a local change, the watcher opens a connection, writes one length-prefixed frame, and closes it. There is no persistent connection and no session state.

**Android blocks clipboard reads from apps without UI focus, and this is why the Android side is a native app rather than a Termux script.** Since Android 10, `ClipboardManager.getPrimaryClip()` returns nothing to a caller that isn't the focused app or the default IME — confirmed directly against this project's Termux daemon (`termux-clipboard-get` worked when Termux was on-screen, returned nothing every time it was polled from the background). No background daemon — Termux-based or otherwise — can read a clipboard change made in another app under this restriction. The fix isn't a permission the app can request from the user; it requires routing the read through [Shizuku](https://github.com/RikkaApps/Shizuku), which brokers calls with adb-shell-level privilege (no root) that isn't subject to the focus check. `ShizukuClipboard.kt` does this via reflection against `IClipboard` — a hidden, non-SDK interface, so the calling app also needs a [hidden-API exemption](https://github.com/LSPosed/AndroidHiddenApiBypass) to reflect into it at all. `ClipboardManager.OnPrimaryClipChangedListener`, the normal event-driven API, doesn't fire for a backgrounded app either, so the Android watcher polls every 0.5s through the Shizuku-brokered read, same as the Mac side polls `pbpaste`.

Discovery is asymmetric, and that asymmetry is the central design decision.

**The phone locates the Mac by a configured IP, not mDNS.** The original design resolved `your-mac.local` over mDNS on the phone, the same way it resolves via `dns-sd`/Bonjour on macOS. That doesn't work on stock Android: there's no `nss-mdns`-equivalent wired into the platform's plain DNS resolver, so `.local` names never resolve from an Android app or from Termux. The Mac's LAN IP is configured directly in the app instead; pin it against DHCP churn with a router-side address reservation for the Mac's MAC address.

**The Mac does not locate the phone.** Android publishes no stable mDNS name, so the Mac instead records the address of the most recent inbound connection. This is why the phone transmits a zero-length keepalive every 30 seconds — not to demonstrate liveness, but to keep the Mac's record current, so that the first Mac-to-phone transmission after a reboot is not sent to a stale address.

**Clipboard access on macOS requires `launchctl asuser`.** A `launchd` agent runs outside the GUI session, where `pbcopy` writes to a pasteboard that is not visible to the user — silently, and with exit status 0. All clipboard operations are invoked through `launchctl asuser $UID` so they reach the active session.

**Echo suppression uses a timestamp rather than a flag.** For 1.5 seconds following a remote write, local changes are ignored. Without this, a write triggers the local watcher, which transmits back to the origin, which triggers its watcher — and the value circulates indefinitely. Both sides implement this the same way.

## Configuration

The Mac daemon reads its configuration from the environment.

| Variable | Default | Description |
|---|---|---|
| `CLIPSYNCD_SECRET` | unset | Shared HMAC key. If unset, the daemon runs in plaintext and logs a warning |

Constants at the top of `clipsyncd_mac.py`: `PORT` (59876), `POLL_INTERVAL` (0.5s), `REMOTE_SET_COOLDOWN` (1.5s), `MAX_MESSAGE_BYTES` (10 MB). The Android app's equivalents live in `Protocol.kt`, and its Mac-IP/secret configuration is entered in-app (persisted to `SharedPreferences`), not read from the environment.

## Operation

```sh
# macOS
launchctl list | grep clipsyncd
tail -f /tmp/clipsyncd.log
launchctl kickstart -k gui/$(id -u)/com.user.clipsyncd
```

```sh
# Android
adb logcat -s ClipsyncdService:* ShizukuClipboard:*
```

Or check in-app: the main screen shows Shizuku permission status and whether the service is running.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Android→Mac stops after a reboot | Shizuku needs a manual "Start" tap after every phone reboot (Android limitation, not fixable without root) — open Shizuku, tap Start |
| Mac receives nothing from phone | `sudo lsof -i :59876` on the Mac — no listener means the daemon failed to start. On the phone, check Shizuku shows "running" and the app shows "permission granted" |
| `readText failed` in Android logs | Shizuku isn't running, or its permission wasn't granted to clipsyncd |
| Phone cannot reach the Mac | Devices on different networks, guest Wi-Fi with client isolation, or the Mac's IP changed (see the DHCP reservation note above) |
| `HMAC verification failed` in the log | The configured secrets differ between devices |
| Values circulate between devices | Increase `REMOTE_SET_COOLDOWN` / `Protocol.REMOTE_SET_COOLDOWN_MS` |

## Limitations

**Text only.** Both `pbpaste` and the Android clipboard API operate on strings; images and files are not synchronised.

**Authenticated, not encrypted.** The HMAC prevents an unauthorised host from injecting into your clipboard. It does not provide confidentiality — payloads are transmitted in cleartext and are readable by anyone capturing traffic on that network.

**Two devices.** The Mac tracks a single phone address: the most recent to connect.

**No queue.** If the peer is unreachable, that clipboard entry is not delivered. The next change synchronises normally.

**Shizuku dependency, no root.** The Android side needs Shizuku running and one manual restart after each phone reboot. This is a consequence of Android's background-clipboard-access restriction (see Architecture) — there is no way to avoid this without either root or making clipsyncd the device's default keyboard, which was rejected as a worse tradeoff.

## Resource usage

| Resource | Usage |
|---|---|
| CPU | Negligible — the process sleeps between 0.5s polls |
| Memory | Approximately 15 MB per device |
| Battery | Minimal: no GPS, no independent radio wakeups |
| Network | LAN only, peer to peer, one short-lived connection per change |

## Contributors

| | |
|---|---|
| [chakri192](https://github.com/chakri192) | Author |
| [aider](https://github.com/Aider-AI/aider) | AI pair programmer |
