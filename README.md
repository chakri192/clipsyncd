<div align="center">

<img src="docs/signal.svg" width="840" alt="" />

# clipsyncd

**Copy on your Mac, paste on your phone. And the other way round.**

Two small daemons, one LAN port, no cloud account and no third-party server anywhere in the path.

<p>
  <img alt="Python" src="https://img.shields.io/badge/Python-3-1c1c1e?style=flat-square&logo=python&logoColor=3776AB" />
  <img alt="macOS" src="https://img.shields.io/badge/macOS-launchd-1c1c1e?style=flat-square&logo=apple&logoColor=white" />
  <img alt="Android" src="https://img.shields.io/badge/Android-Termux-1c1c1e?style=flat-square&logo=android&logoColor=3DDC84" />
  <img alt="Dependencies" src="https://img.shields.io/badge/dependencies-stdlib%20only-1c1c1e?style=flat-square" />
</p>

</div>

---

326 lines of standard-library Python that keeps the whole exchange on your own network. Copy a URL on your laptop, paste it on your phone half a second later. Copy an OTP on your phone, paste it on your laptop.

It survives the things that actually break this kind of tool: your Mac's IP changing, your phone rebooting, a VPN switching on, the terminal window being closed.

| Situation | What happens |
|---|---|
| Copy on either device | On the other within ~0.5s |
| Mac's IP changes | The phone re-resolves over mDNS on the next failure |
| Phone's IP changes | The Mac relearns it from the next inbound connection — at worst 30s |
| Either reboots | `launchd` and Termux:Boot restart them |
| VPN switched on | Still works; LAN traffic doesn't enter the tunnel |

## How it works

Both sides run the same structure: a watcher polling every 0.5s, and a server on port `59876`. On a change it opens a connection, writes one length-prefixed frame, and closes. No persistent connection, no session state.

Discovery is the interesting part, and it isn't symmetric.

**The phone finds the Mac by name** — `your-mac.local` over mDNS, re-resolved on every failure, so a new DHCP lease costs one retry rather than a config edit.

**The Mac never looks up the phone.** It can't; Android publishes no stable mDNS name. It remembers whoever last connected. That's the real reason the phone sends a zero-length keepalive every 30 seconds — not to prove liveness, but to keep the Mac's idea of where the phone is fresh, so the first push after a reboot doesn't go to a stale address.

**`pbcopy` doesn't behave the way you'd expect from a daemon.** A `launchd` agent runs outside the GUI session, where plain `pbcopy` writes to a pasteboard nobody can see — silently, with exit status 0. Every clipboard call goes through `launchctl asuser $UID`. That one detail is the difference between "works when I run it in Terminal" and "works".

**Echo suppression is a timestamp, not a flag.** After a remote write, local changes are ignored for 1.5 seconds — otherwise the Mac's own write trips its watcher, which pushes back, and the string bounces forever.

## Setting it up

**1. A shared secret.** Optional, but do it — without one, anything on your LAN can write to your clipboard.

```zsh
python3 -c "import secrets; print(secrets.token_hex(32))"
```

Set it as `CLIPSYNCD_SECRET` on **both** devices. Every frame then carries an HMAC-SHA256 tag and unverified frames are dropped.

**2. Mac.** Copy `clipsyncd_mac.py` to `/usr/local/bin/clipsyncd.py`, then create `~/Library/LaunchAgents/com.user.clipsyncd.plist` with `RunAtLoad`, `KeepAlive`, and an `EnvironmentVariables` dict holding your secret:

```zsh
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.user.clipsyncd.plist
hostname     # the phone needs this
```

**3. Android.** Termux, Termux:API and Termux:Boot, **from F-Droid** — the Play Store builds are frozen and missing what this needs.

```bash
pkg install termux-api python
cp clipsyncd_android.py ~/clipsyncd.py
```

Export `CLIPSYNCD_MAC_HOSTNAME` and `CLIPSYNCD_SECRET` in `~/.bashrc` and in `~/.termux/boot/clipsyncd.sh` (with `termux-wake-lock`). Then set **Termux → Battery → Unrestricted**, or Android kills it after a few hours.

## Configuration

| Variable | Default |
|---|---|
| `CLIPSYNCD_SECRET` | unset — plaintext, with a warning in the log |
| `CLIPSYNCD_MAC_HOSTNAME` | `your-mac-hostname.local` (Android side) |

Constants at the top of either script: `PORT` 59876, `POLL_INTERVAL` 0.5s, `REMOTE_SET_COOLDOWN` 1.5s, `KEEPALIVE_INTERVAL` 30s, `MAX_MESSAGE_BYTES` 10 MB.

## When it misbehaves

| Symptom | Cause |
|---|---|
| Stops after a few hours on Android | Battery optimisation |
| Mac never receives | `sudo lsof -i :59876` — nothing listening means the agent didn't start |
| Phone can't resolve the Mac | Different networks, or guest Wi-Fi with client isolation |
| `HMAC verification failed` | The two secrets differ |
| Text ping-pongs | Raise `REMOTE_SET_COOLDOWN` |

Logs: `/tmp/clipsyncd.log` on the Mac, `~/clipsyncd.log` on the phone.

## Scope, honestly

**Text only** — `pbpaste` and `termux-clipboard-get` both deal in strings.

**Authenticated, not encrypted.** The HMAC stops a stranger injecting into your clipboard. It does not hide anything: payloads travel in the clear. Don't run it somewhere that matters.

**Two devices**, and **no queue** — if the other end is asleep, that copy is missed.

## Contributors

[chakri192](https://github.com/chakri192) · [aider](https://github.com/Aider-AI/aider)
