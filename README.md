<div align="center">

# clipsyncd

**Bidirectional clipboard synchronisation between macOS and Android over a local network.**

Two daemons, one TCP port, and no cloud service, account, or third-party server in the path.

<p>
  <img alt="Python" src="https://img.shields.io/badge/Python-3-1c1c1e?style=flat-square&logo=python&logoColor=3776AB" />
  <img alt="macOS" src="https://img.shields.io/badge/macOS-launchd-1c1c1e?style=flat-square&logo=apple&logoColor=white" />
  <img alt="Android" src="https://img.shields.io/badge/Android-Termux-1c1c1e?style=flat-square&logo=android&logoColor=3DDC84" />
  <img alt="Dependencies" src="https://img.shields.io/badge/dependencies-stdlib%20only-1c1c1e?style=flat-square" />
</p>

</div>

---

## Overview

Clipboard synchronisation typically requires an account and routes your data through a third party. clipsyncd is 326 lines of standard-library Python that keeps the exchange entirely on your own network: one device opens a TCP connection to the other, transmits a single framed message, and closes it.

Copy a URL on a laptop and paste it on a phone half a second later. Copy a one-time code on a phone and paste it on a laptop.

| Condition | Behaviour |
|---|---|
| Copy on either device | Available on the other within approximately 0.5s |
| Mac's IP address changes | The phone re-resolves it over mDNS on the next failure |
| Phone's IP address changes | The Mac relearns it from the next inbound connection, within 30s |
| Either device reboots | `launchd` and Termux:Boot restart the daemons |
| VPN enabled | Unaffected — LAN traffic does not enter the tunnel |
| Identical text copied twice | No transmission; nothing changed |

## Requirements

**macOS** — Python 3 (the system interpreter is sufficient).

**Android** — [Termux](https://f-droid.org/repo/com.termux_118.apk), [Termux:API](https://f-droid.org/repo/com.termux.api_51.apk), and [Termux:Boot](https://f-droid.org/repo/com.termux.boot_7.apk), installed **from F-Droid**. The Play Store builds are frozen at an older version and lack the required functionality.

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

```sh
pkg install termux-api python
cp clipsyncd_android.py ~/clipsyncd.py
```

Export `CLIPSYNCD_MAC_HOSTNAME` and `CLIPSYNCD_SECRET` in `~/.bashrc`, and again in `~/.termux/boot/clipsyncd.sh` alongside `termux-wake-lock` so the daemon survives a reboot.

Set **Settings → Apps → Termux → Battery → Unrestricted**. Without this, Android terminates the daemon after several hours.

## Architecture

Both devices run the same structure: a watcher thread polling the clipboard every 0.5s, and a server thread accepting connections on port `59876`. On a local change, the watcher opens a connection, writes one length-prefixed frame, and closes it. There is no persistent connection and no session state.

Discovery is asymmetric, and that asymmetry is the central design decision.

**The phone locates the Mac by name.** `your-mac.local` resolves over mDNS, which macOS publishes by default. It is re-resolved on every failure, so a new DHCP lease costs one retry rather than a configuration change.

**The Mac does not locate the phone.** Android publishes no stable mDNS name, so the Mac instead records the address of the most recent inbound connection. This is why the phone transmits a zero-length keepalive every 30 seconds — not to demonstrate liveness, but to keep the Mac's record current, so that the first Mac-to-phone transmission after a reboot is not sent to a stale address.

**Clipboard access on macOS requires `launchctl asuser`.** A `launchd` agent runs outside the GUI session, where `pbcopy` writes to a pasteboard that is not visible to the user — silently, and with exit status 0. All clipboard operations are invoked through `launchctl asuser $UID` so they reach the active session.

**Echo suppression uses a timestamp rather than a flag.** For 1.5 seconds following a remote write, local changes are ignored. Without this, a write triggers the local watcher, which transmits back to the origin, which triggers its watcher — and the value circulates indefinitely.

## Configuration

Both daemons read their configuration from the environment.

| Variable | Default | Description |
|---|---|---|
| `CLIPSYNCD_SECRET` | unset | Shared HMAC key. If unset, the daemon runs in plaintext and logs a warning |
| `CLIPSYNCD_MAC_HOSTNAME` | `your-mac-hostname.local` | Android only — the Mac's mDNS name |

Constants at the top of either script: `PORT` (59876), `POLL_INTERVAL` (0.5s), `REMOTE_SET_COOLDOWN` (1.5s), `KEEPALIVE_INTERVAL` (30s), `MAX_MESSAGE_BYTES` (10 MB).

## Operation

```sh
# macOS
launchctl list | grep clipsyncd
tail -f /tmp/clipsyncd.log
launchctl kickstart -k gui/$(id -u)/com.user.clipsyncd
```

```sh
# Android
pgrep -f clipsyncd.py && echo running || echo stopped
tail -f ~/clipsyncd.log
```

## Troubleshooting

| Symptom | Cause |
|---|---|
| Stops after several hours on Android | Battery optimisation is enabled for Termux |
| Mac receives nothing | `sudo lsof -i :59876` — no listener means the agent failed to start |
| Phone cannot resolve the Mac | Devices on different networks, or guest Wi-Fi with client isolation |
| `HMAC verification failed` in the log | The configured secrets differ between devices |
| Values circulate between devices | Increase `REMOTE_SET_COOLDOWN` |

## Limitations

**Text only.** Both `pbpaste` and `termux-clipboard-get` operate on strings; images and files are not synchronised.

**Authenticated, not encrypted.** The HMAC prevents an unauthorised host from injecting into your clipboard. It does not provide confidentiality — payloads are transmitted in cleartext and are readable by anyone capturing traffic on that network.

**Two devices.** The Mac tracks a single phone address: the most recent to connect.

**No queue.** If the peer is unreachable, that clipboard entry is not delivered. The next change synchronises normally.

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
