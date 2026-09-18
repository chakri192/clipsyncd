package com.chakri.clipsyncd

import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire protocol shared with clipsyncd_mac.py:
 * [4-byte big-endian length][32-byte HMAC-SHA256 tag if secret set and length>0][UTF-8 payload]
 * A zero-length frame is a keepalive and never carries a tag.
 */
object Protocol {
    const val PORT = 59876
    const val MAX_MESSAGE_BYTES = 10 * 1024 * 1024
    const val HMAC_LEN = 32
    const val REMOTE_SET_COOLDOWN_MS = 1500L
    const val KEEPALIVE_INTERVAL_MS = 30_000L
    const val POLL_INTERVAL_MS = 500L
    const val SOCKET_TIMEOUT_MS = 5000

    private fun hmacSha256(secret: String, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun frame(secret: String?, data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(4).putInt(data.size).array()
        if (!secret.isNullOrEmpty() && data.isNotEmpty()) {
            val tag = hmacSha256(secret, data)
            return header + tag + data
        }
        return header + data
    }

    private fun recvExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = input.read(buf, off, n - off)
            if (read < 0) throw java.io.EOFException("connection closed")
            off += read
        }
        return buf
    }

    /** Returns the verified payload text, or null for a keepalive (zero-length) frame. */
    fun readFrame(input: InputStream, secret: String?): String? {
        val lengthBytes = recvExact(input, 4)
        val length = ByteBuffer.wrap(lengthBytes).int
        if (length == 0) return null
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw java.io.IOException("rejecting oversized message: $length bytes")
        }
        val hasSecret = !secret.isNullOrEmpty()
        val raw = if (hasSecret) recvExact(input, length + HMAC_LEN) else recvExact(input, length)
        val payload: ByteArray
        if (hasSecret) {
            val tag = raw.copyOfRange(0, HMAC_LEN)
            payload = raw.copyOfRange(HMAC_LEN, raw.size)
            val expected = hmacSha256(secret!!, payload)
            if (!MessageDigest.isEqual(tag, expected)) {
                throw java.io.IOException("HMAC verification failed")
            }
        } else {
            payload = raw
        }
        return String(payload, Charsets.UTF_8)
    }
}
