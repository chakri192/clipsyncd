#!/usr/bin/env python3
"""
clipsyncd - bidirectional clipboard sync over Tailscale
Mac side
"""

import socket
import subprocess
import threading
import time
import logging

MAC_IP = "100.120.25.47"
ANDROID_IP = "100.115.151.55"
PORT = 59876
POLL_INTERVAL = 0.5
REMOTE_SET_COOLDOWN = 1.5

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    handlers=[logging.FileHandler("/tmp/clipsyncd.log"), logging.StreamHandler()]
)
log = logging.getLogger(__name__)

_lock = threading.Lock()
_remote_set_at = 0.0

def get_clipboard():
    try:
        script = 'get the clipboard'
        result = subprocess.run(
            ["osascript", "-e", script],
            capture_output=True, timeout=3
        )
        return result.stdout.decode("utf-8", errors="replace").rstrip("\n")
    except Exception:
        return ""

def set_clipboard(text):
    try:
        # escape backslashes and quotes for AppleScript string
        escaped = text.replace("\\", "\\\\").replace('"', '\\"')
        script = f'set the clipboard to "{escaped}"'
        subprocess.run(
            ["osascript", "-e", script],
            check=True, timeout=3
        )
    except Exception as e:
        log.error(f"set_clipboard failed: {e}")

def send_to_android(text):
    try:
        with socket.create_connection((ANDROID_IP, PORT), timeout=3) as s:
            data = text.encode("utf-8")
            s.sendall(len(data).to_bytes(4, "big") + data)
    except Exception as e:
        log.warning(f"send to android failed: {e}")

def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf += chunk
    return buf

def server_thread():
    global _remote_set_at
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(5)
    log.info(f"listening on 0.0.0.0:{PORT}")
    while True:
        try:
            conn, addr = srv.accept()
            with conn:
                length = int.from_bytes(recv_exact(conn, 4), "big")
                if length == 0:
                    continue
                data = recv_exact(conn, length).decode("utf-8", errors="replace")
                log.info(f"received {len(data)} chars from android")
                with _lock:
                    _remote_set_at = time.time()
                set_clipboard(data)
        except Exception as e:
            log.error(f"server error: {e}")

def watcher_thread():
    global _remote_set_at
    last = get_clipboard()
    while True:
        time.sleep(POLL_INTERVAL)
        current = get_clipboard()
        if current != last:
            last = current
            if not current:
                continue
            with _lock:
                since = time.time() - _remote_set_at
            if since < REMOTE_SET_COOLDOWN:
                log.info(f"ignoring echo (remote set {since:.2f}s ago)")
                continue
            log.info(f"clipboard changed, pushing to android ({len(current)} chars)")
            send_to_android(current)

if __name__ == "__main__":
    log.info("clipsyncd starting")
    threading.Thread(target=server_thread, daemon=True).start()
    watcher_thread()
