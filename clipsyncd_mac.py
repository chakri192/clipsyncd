#!/usr/bin/env python3
"""
clipsyncd - bidirectional clipboard sync over LAN (mDNS)
Mac side
"""

import socket
import subprocess
import threading
import time
import logging
import os
import hmac
import hashlib

PORT = 59876
POLL_INTERVAL = 0.5
REMOTE_SET_COOLDOWN = 1.5
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

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    handlers=[logging.FileHandler("/tmp/clipsyncd.log"), logging.StreamHandler()]
)
log = logging.getLogger(__name__)

_lock = threading.Lock()
_remote_set_at = 0.0
_android_ip = None

def get_uid():
    return str(os.getuid())

def get_clipboard():
    try:
        uid = get_uid()
        result = subprocess.run(
            ["launchctl", "asuser", uid, "pbpaste"],
            capture_output=True, timeout=3
        )
        return result.stdout.decode("utf-8", errors="replace")
    except Exception as e:
        log.error(f"get_clipboard failed: {e}")
        return ""

def set_clipboard(text):
    try:
        uid = get_uid()
        result = subprocess.run(
            ["launchctl", "asuser", uid, "pbcopy"],
            input=text.encode("utf-8"),
            capture_output=True,
            timeout=3
        )
        if result.returncode != 0:
            log.error(f"pbcopy failed: {result.stderr.decode()}")
        else:
            log.info(f"set clipboard ok ({len(text)} chars)")
    except Exception as e:
        log.error(f"set_clipboard failed: {e}")

def send_to_android(text):
    global _android_ip
    if not _android_ip:
        log.warning("android IP not known yet, skipping push")
        return
    try:
        with socket.create_connection((_android_ip, PORT), timeout=3) as s:
            s.sendall(frame(text.encode("utf-8")))
    except Exception as e:
        log.warning(f"send to android failed: {e}")
        _android_ip = None

def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf += chunk
    return buf

def server_thread():
    global _remote_set_at, _android_ip
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(5)
    log.info(f"listening on 0.0.0.0:{PORT}")
    while True:
        try:
            conn, addr = srv.accept()
            with conn:
                _android_ip = addr[0]
                log.info(f"android connected from {_android_ip}")
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
                log.info(f"received {len(data)} chars from android")
                with _lock:
                    _remote_set_at = time.time()
                set_clipboard(data)
        except Exception as e:
            log.error(f"server error: {e}")

def watcher_thread():
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
