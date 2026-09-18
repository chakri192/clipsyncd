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
SEND_ATTEMPTS = 3
SEND_RETRY_DELAY = 1.0
PENDING_MAX_AGE = 900  # don't surprise the phone with a clipboard from long ago
MAX_MESSAGE_BYTES = 10 * 1024 * 1024  # reject absurd length prefixes (DoS guard)
BONJOUR_SERVICE_TYPE = "_clipsyncd._tcp"
BONJOUR_NAME = "clipsyncd"

# Shared secret for message authentication. Set the SAME value on both the
# Mac and Android side (export CLIPSYNCD_SECRET=...). When set, every payload
# carries an HMAC-SHA256 tag that the receiver verifies, so a stranger on the
# LAN can't inject into your clipboard or read pushed contents blindly. If
# unset, the daemon runs in legacy plaintext mode with a warning.
SECRET = os.environ.get("CLIPSYNCD_SECRET")
_HMAC_LEN = 32  # sha256 digest size

def frame(data: bytes) -> bytes:
    """Length-prefixed frame; includes an HMAC tag when SECRET is configured.
    Keepalives (empty data) are tagged too when SECRET is set — otherwise
    they'd be an unauthenticated way for any LAN host to get the Mac to
    learn its address and redirect the next push to itself."""
    header = len(data).to_bytes(4, "big")
    if SECRET:
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
_pending = None  # (text, timestamp) of a push the phone wasn't reachable for

_bonjour_process = None

def advertise_bonjour():
    """Register via Bonjour so the Android app can find this Mac on any
    network via NsdManager, instead of relying on a hardcoded IP."""
    global _bonjour_process
    try:
        _bonjour_process = subprocess.Popen(
            ["dns-sd", "-R", BONJOUR_NAME, BONJOUR_SERVICE_TYPE, "local.", str(PORT)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        log.info(f"advertising {BONJOUR_NAME}.{BONJOUR_SERVICE_TYPE} via Bonjour")
    except Exception as e:
        log.error(f"failed to start Bonjour advertisement: {e}")

def get_uid():
    return str(os.getuid())

def get_clipboard():
    """Returns None on failure so the watcher can skip the cycle instead of
    treating a transient pbpaste failure as a real clipboard change."""
    try:
        uid = get_uid()
        result = subprocess.run(
            ["launchctl", "asuser", uid, "pbpaste"],
            capture_output=True, timeout=3
        )
        if result.returncode != 0:
            log.error(f"pbpaste failed: {result.stderr.decode(errors='replace')}")
            return None
        return result.stdout.decode("utf-8", errors="replace")
    except Exception as e:
        log.error(f"get_clipboard failed: {e}")
        return None

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

def _hold_for_android(text, since=None):
    global _pending
    with _lock:
        _pending = (text, since or time.time())

def flush_pending():
    """Deliver the push that couldn't be sent while the phone was unreachable."""
    global _pending
    with _lock:
        held, _pending = _pending, None
    if held and time.time() - held[1] <= PENDING_MAX_AGE:
        log.info(f"phone is reachable again, delivering held push ({len(held[0])} chars)")
        send_to_android(held[0], held_since=held[1])

def send_to_android(text, held_since=None):
    global _pending
    ip = _android_ip
    if not ip:
        log.warning("android IP not known yet, holding push until the phone connects")
        _hold_for_android(text, held_since)
        return
    payload = frame(text.encode("utf-8"))
    for attempt in range(1, SEND_ATTEMPTS + 1):
        try:
            with socket.create_connection((ip, PORT), timeout=3) as s:
                s.sendall(payload)
            with _lock:
                _pending = None  # anything held is older than what just went out
            return
        except Exception as e:
            log.warning(f"send to android failed (attempt {attempt}/{SEND_ATTEMPTS}): {e}")
            if attempt < SEND_ATTEMPTS:
                time.sleep(SEND_RETRY_DELAY)
    # Deliberately keep _android_ip: a locked phone's Wi-Fi is asleep, so the
    # first connect fails while the radio wakes, and forgetting the address
    # here would silently drop every later push until the phone next spoke.
    # If the phone really moved, its next keepalive or push updates it.
    # The text itself is held and delivered on that next contact.
    log.warning("giving up for now, holding push until the phone reconnects")
    _hold_for_android(text, held_since)  # keep the original age so it can expire

def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf += chunk
    return buf

def server_thread():
    global _remote_set_at, _android_ip, _pending
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(5)
    log.info(f"listening on 0.0.0.0:{PORT}")
    while True:
        try:
            conn, addr = srv.accept()
            conn.settimeout(5)  # a stalled peer must not hang the whole daemon
            with conn:
                length = int.from_bytes(recv_exact(conn, 4), "big")
                if length > MAX_MESSAGE_BYTES:
                    log.warning(f"rejecting oversized message: {length} bytes")
                    continue
                if SECRET:
                    raw = recv_exact(conn, length + _HMAC_LEN)
                    tag, payload = raw[:_HMAC_LEN], raw[_HMAC_LEN:]
                    expected = hmac.new(SECRET.encode(), payload, hashlib.sha256).digest()
                    if not hmac.compare_digest(tag, expected):
                        log.warning(f"HMAC verification failed from {addr[0]} — dropping message")
                        continue
                else:
                    payload = recv_exact(conn, length)
                # Only trust the source address once the message is verified
                # (or SECRET is unset, the documented plaintext-mode tradeoff) —
                # otherwise any LAN host could redirect the next push to itself
                # just by opening a connection, verified or not.
                _android_ip = addr[0]
                log.info(f"android connected from {_android_ip}")
                if length == 0:
                    if _pending is not None:
                        # The phone just spoke, so it's awake: hand over what
                        # was held. Off-thread so we keep accepting meanwhile.
                        threading.Thread(target=flush_pending, daemon=True).start()
                    continue
                data = payload.decode("utf-8", errors="replace")
                log.info(f"received {len(data)} chars from android")
                with _lock:
                    _remote_set_at = time.time()
                    _pending = None  # the phone's copy is newer than anything held
                set_clipboard(data)
        except Exception as e:
            log.error(f"server error: {e}")

def watcher_thread():
    last = get_clipboard() or ""
    while True:
        time.sleep(POLL_INTERVAL)
        current = get_clipboard()
        if current is None:
            continue  # transient pbpaste failure — not a real change
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
    advertise_bonjour()
    threading.Thread(target=server_thread, daemon=True).start()
    watcher_thread()
