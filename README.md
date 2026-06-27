# clipsyncd

Bidirectional clipboard sync between Mac and Android over LAN. Copy on one device, paste on the other — no BLE, no cloud, no Tailscale, no third-party servers.

## How it works

| Scenario | Behavior |
|---|---|
| Copy on Mac | Clipboard syncs to Android instantly |
| Copy on Android | Clipboard syncs to Mac instantly |
| VPN active | Works — LAN traffic bypasses VPN tunnel |
| Terminal closed | Keeps running — launchd on Mac, nohup on Android |
| Phone reboot | Auto-restarts via Termux:Boot |
| IP changed | Android re-resolves Mac via mDNS on failure |
| Mac restarted | Android sends keepalive every 30s, Mac re-learns Android IP |

---

## How it works internally

1. Android resolves Mac via `<hostname>.local` mDNS — no hardcoded IPs
2. On startup, Android sends an initial ping so Mac immediately learns Android's IP
3. Android sends a keepalive every 30s so Mac never loses track of Android's IP
4. Each side runs a TCP server on port `59876` and polls clipboard every 0.5s
5. On change, pushes to the other device via length-prefixed TCP message
6. A 1.5s cooldown suppresses echo loops after receiving from remote
7. Mac uses `launchctl asuser <uid> pbcopy/pbpaste` — works correctly from launchd daemons

---

## Requirements

**Mac**
- Python 3 (system `/usr/bin/python3`)

**Android**
- [Termux](https://f-droid.org/repo/com.termux_118.apk) — F-Droid only, not Play Store
- [Termux:API](https://f-droid.org/repo/com.termux.api_51.apk) — F-Droid
- [Termux:Boot](https://f-droid.org/repo/com.termux.boot_7.apk) — F-Droid

> Play Store versions of Termux are outdated. Use F-Droid.

---

## Installation

### 1. Mac setup

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

### 2. Update Mac hostname in Android script

Edit `clipsyncd_android.py` and set:

```python
MAC_HOSTNAME = "your-mac-hostname.local"
```

Get your hostname with `hostname` on Mac.

### 3. Android setup (Termux)

```bash
pkg install termux-api python
cp clipsyncd_android.py ~/clipsyncd.py
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

Disable battery optimization: Settings > Apps > Termux > Battery > Unrestricted

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

## Troubleshooting

| Problem | Fix |
|---|---|
| Android script stops after a while | Settings > Apps > Termux > Battery > Unrestricted |
| Mac not receiving from Android | Check `sudo lsof -i :59876` — port must be listening |
| Android can't resolve Mac hostname | Make sure both devices are on same WiFi |
| Works on WiFi but not with VPN | Add Termux to VPN split tunnel bypass list on Android |
| Echo loop | Cooldown is 1.5s — increase `REMOTE_SET_COOLDOWN` if needed |

---

## Resource usage

| Resource | Usage |
|---|---|
| CPU | Near zero — sleeps 0.5s between polls |
| RAM | ~15MB |
| Battery | Minimal — no GPS, pure TCP |
| Network | LAN only, direct peer-to-peer |

---

## License

MIT

## Contributors

| Contributor | Role |
|---|---|
| [chakri192](https://github.com/chakri192) | Author |
| Claude (Anthropic) | AI pair programmer |
