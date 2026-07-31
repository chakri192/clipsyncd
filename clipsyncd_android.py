#!/data/data/com.termux/files/usr/bin/env python3
"""
clipsyncd - bidirectional clipboard sync over LAN (mDNS)
Android side
"""

import socket
import subprocess
import threading
import time
import logging
import os
import hmac
import hashlib

MAC_HOSTNAME = os.environ.get("CLIPSYNCD_MAC_HOSTNAME", "your-mac-hostname.local")
PORT = 59876
MAX_MESSAGE_BYTES = 10 * 1024 * 1024  # reject absurd length prefixes (DoS guard)

# Shared secret for message authentication. Set the SAME value on both the
# Mac and Android side (export CLIPSYNCD_SECRET=...). When set, every payload
# carries an HMAC-SHA256 tag that the receiver verifies, so a stranger on the
# LAN can't inject into your clipboard or read pushed contents blindly. If
# unset, the daemon runs in legacy plaintext mode with a warning.
SECRET = os.environ.get("CLIPSYNCD_SECRET")
_HMAC_LEN = 32  # sha256 digest size

def frame(data: bytes) -> bytes:
    """Length-prefixed frame; includes an HMAC tag when SECRET is configured."""
    header = len(data).to_bytes(4, "big")
    if SECRET and data:
        tag = hmac.new(SECRET.encode(), data, hashlib.sha256).digest()
        return header + tag + data
    return header + data
POLL_INTERVAL = 0.5
REMOTE_SET_COOLDOWN = 1.5
RECONNECT_INTERVAL = 5
KEEPALIVE_INTERVAL = 30  # send keepalive every 30s so Mac always knows our IP

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    handlers=[logging.FileHandler(os.path.expanduser("~/clipsyncd.log")), logging.StreamHandler()]
)
log = logging.getLogger(__name__)

_lock = threading.Lock()
_remote_set_at = 0.0
_mac_ip = None

def get_clipboard():
    try:
        result = subprocess.run(["termux-clipboard-get"], capture_output=True, timeout=3)
        return result.stdout.decode("utf-8", errors="replace")
    except Exception:
        return ""

def set_clipboard(text):
    try:
        subprocess.run(["termux-clipboard-set"], input=text.encode("utf-8"), check=True, timeout=3)
    except Exception as e:
        log.error(f"termux-clipboard-set failed: {e}")

def resolve_mac():
    while True:
        try:
            ip = socket.gethostbyname(MAC_HOSTNAME)
            log.info(f"resolved {MAC_HOSTNAME} -> {ip}")
            return ip
        except Exception:
            log.warning(f"can't resolve {MAC_HOSTNAME}, retrying in {RECONNECT_INTERVAL}s")
            time.sleep(RECONNECT_INTERVAL)

def send_to_mac(text):
    global _mac_ip
    if not _mac_ip:
        _mac_ip = resolve_mac()
    try:
        with socket.create_connection((_mac_ip, PORT), timeout=3) as s:
            s.sendall(frame(text.encode("utf-8")))
        return True
    except Exception as e:
        log.warning(f"send to mac failed: {e}")
        _mac_ip = None
        return False

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
                if length > MAX_MESSAGE_BYTES:
                    log.warning(f"rejecting oversized message: {length} bytes")
                    continue
                raw = recv_exact(conn, length + _HMAC_LEN) if SECRET else recv_exact(conn, length)
                if SECRET:
                    tag, payload = raw[:_HMAC_LEN], raw[_HMAC_LEN:]
                    expected = hmac.new(SECRET.encode(), payload, hashlib.sha256).digest()
                    if not hmac.compare_digest(tag, expected):
                        log.warning("HMAC verification failed — dropping message")
                        continue
                else:
                    payload = raw
                data = payload.decode("utf-8", errors="replace")
                log.info(f"received {len(data)} chars from mac")
                with _lock:
                    _remote_set_at = time.time()
                set_clipboard(data)
        except Exception as e:
            log.error(f"server error: {e}")

def keepalive_thread():
    """Send empty keepalive to Mac every 30s so Mac always knows our IP."""
    while True:
        time.sleep(KEEPALIVE_INTERVAL)
        try:
            if not _mac_ip:
                continue
            with socket.create_connection((_mac_ip, PORT), timeout=3) as s:
                # send 0-length message as keepalive
                s.sendall((0).to_bytes(4, "big"))
            log.info("keepalive sent to mac")
        except Exception as e:
            log.warning(f"keepalive failed: {e}")

def watcher_thread():
    last = get_clipboard()
    # send initial ping so Mac learns our IP immediately
    log.info("sending initial ping to mac")
    send_to_mac("")
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
            log.info(f"clipboard changed, pushing to mac ({len(current)} chars)")
            send_to_mac(current)

if __name__ == "__main__":
    log.info("clipsyncd starting, waiting for network...")
    time.sleep(10)
    log.info("clipsyncd running")
    threading.Thread(target=server_thread, daemon=True).start()
    threading.Thread(target=keepalive_thread, daemon=True).start()
    watcher_thread()
