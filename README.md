# clipsyncd

Bidirectional clipboard sync between Mac and Android over Tailscale. Copy on one device, paste on the other — no BLE, no cloud, no third-party servers.

## How it works

| Scenario | Behavior |
|---|---|
| Copy on Mac | Clipboard syncs to Android instantly |
| Copy on Android | Clipboard syncs to Mac instantly |
| VPN active | Works — uses Tailscale private IP, not LAN |
| Terminal closed | Keeps running — launchd on Mac, nohup on Android |
| Phone reboot | Auto-restarts via Termux:Boot |
| Echo loop | Suppressed via 1.5s cooldown after remote set |

---

## Requirements

**Mac**
- Python 3 (system `/usr/bin/python3` is fine)
- Tailscale

**Android**
- [Termux](https://f-droid.org/repo/com.termux_118.apk) — F-Droid only, not Play Store
- [Termux:API](https://f-droid.org/repo/com.termux.api_51.apk) — F-Droid
- [Termux:Boot](https://f-droid.org/repo/com.termux.boot_7.apk) — F-Droid
- Tailscale

> Play Store versions of Termux are outdated. Use F-Droid.

---

## Installation

### 1. Set your IPs

Edit both scripts and update:

```python
MAC_IP = "your.mac.tailscale.ip"
ANDROID_IP = "your.android.tailscale.ip"
```

Get them with `tailscale ip` on Mac and the Tailscale app on Android.

### 2. Mac setup

```zsh
sudo cp clipsyncd_mac.py /usr/local/bin/clipsyncd.py
sudo chmod 644 /usr/local/bin/clipsyncd.py
```

Create `~/Library/LaunchAgents/com.user.clipsyncd.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.user.clipsyncd</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/python3</string>
        <string>/usr/local/bin/clipsyncd.py</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/clipsyncd.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/clipsyncd.log</string>
</dict>
</plist>
```

```zsh
launchctl load ~/Library/LaunchAgents/com.user.clipsyncd.plist
```

### 3. Android setup (Termux)

```bash
pkg install termux-api python

# transfer clipsyncd_android.py to your phone, then:
cp clipsyncd_android.py ~/clipsyncd.py

# run
nohup python3 ~/clipsyncd.py > ~/clipsyncd.log 2>&1 &
```

### 4. Auto-start on Android reboot

Open Termux:Boot app once to activate it, then:

```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/clipsyncd.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
nohup python3 ~/clipsyncd.py > ~/clipsyncd.log 2>&1 &
EOF
chmod +x ~/.termux/boot/clipsyncd.sh
```

Also disable battery optimization: Settings → Apps → Termux → Battery → Unrestricted

---

## Useful commands

**Mac**
```zsh
# check status
launchctl list | grep clipsyncd

# live log
tail -f /tmp/clipsyncd.log

# restart
launchctl unload ~/Library/LaunchAgents/com.user.clipsyncd.plist
launchctl load ~/Library/LaunchAgents/com.user.clipsyncd.plist
```

**Android (Termux)**
```bash
# check status
pgrep -f clipsyncd.py && echo running || echo stopped

# live log
tail -f ~/clipsyncd.log

# restart
pkill -f clipsyncd.py
nohup python3 ~/clipsyncd.py > ~/clipsyncd.log 2>&1 &
```

---

## How it works internally

1. Both devices run a TCP server on port `59876` over Tailscale
2. Each polls its own clipboard every 0.5s
3. On change, pushes to the other device via length-prefixed TCP message
4. A 1.5s cooldown suppresses echo loops after receiving from remote
5. Mac uses `osascript` instead of `pbcopy`/`pbpaste` — works correctly from launchd daemons without a window server session

---

## Resource usage

| Resource | Usage |
|---|---|
| CPU | Near zero — sleeps 0.5s between polls |
| RAM | ~15MB |
| Battery | Minimal — no GPS, pure TCP |
| Network | Tailscale only, direct peer-to-peer after first handshake |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Android script stops after a while | Settings → Apps → Termux → Battery → Unrestricted |
| Mac not receiving from Android | Check `sudo lsof -i :59876` — port must be listening |
| Tailscale slow on first connect | Run `tailscale ping <peer-ip>` once to force direct path |
| Echo loop (same text bouncing) | Cooldown is 1.5s — if still happening, increase `REMOTE_SET_COOLDOWN` |
| `pbcopy` not working from daemon | Script uses `osascript` — don't replace with `pbcopy` |

---

## License

MIT

## Contributors

| Contributor | Role |
|---|---|
| [chakri192](https://github.com/chakri192) | Author |
| Claude (Anthropic) | AI pair programmer |
