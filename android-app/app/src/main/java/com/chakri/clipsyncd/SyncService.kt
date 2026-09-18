package com.chakri.clipsyncd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Direct port of clipsyncd_mac.py's daemon logic. The clipboard-change
 * listener (ClipboardManager.OnPrimaryClipChangedListener) does not fire for
 * a backgrounded app on this Android version — confirmed empirically — so
 * local changes are detected the same way the original Python daemon did:
 * polling, via ShizukuClipboard's privileged read (a plain ClipboardManager
 * read is denied without UI focus; the Shizuku-brokered one isn't).
 */
class SyncService : Service() {

    private lateinit var clipboardManager: ClipboardManager
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var keepaliveThread: Thread? = null
    private var watcherThread: Thread? = null
    @Volatile private var remoteSetAt: Long = 0L
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            isRunning = true
            startForegroundWithNotification()
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            startServer()
            startKeepalive()
            startWatcher()
            Log.i(TAG, "sending initial ping to mac")
            sendToMac("")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        serverSocket?.close()
        serverThread?.interrupt()
        keepaliveThread?.interrupt()
        watcherThread?.interrupt()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val channelId = "clipsyncd_service"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "clipsyncd sync", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("clipsyncd running")
            .setContentText("Syncing clipboard with Mac")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setClipboardText(text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("clipsyncd", text))
    }

    private fun startWatcher() {
        watcherThread = Thread {
            var last: String? = ShizukuClipboard.readText()
            while (running) {
                try {
                    Thread.sleep(Protocol.POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (!ShizukuClipboard.isReady()) continue
                val current = ShizukuClipboard.readText()
                if (current == last) continue
                last = current
                if (current.isNullOrEmpty()) continue
                val since = System.currentTimeMillis() - remoteSetAt
                if (since < Protocol.REMOTE_SET_COOLDOWN_MS) {
                    Log.i(TAG, "ignoring echo (remote set ${since}ms ago)")
                    continue
                }
                Log.i(TAG, "clipboard changed, pushing to mac (${current.length} chars)")
                sendToMac(current)
            }
        }
        watcherThread?.start()
    }

    private fun startServer() {
        serverThread = Thread {
            try {
                val srv = ServerSocket(Protocol.PORT)
                serverSocket = srv
                Log.i(TAG, "listening on 0.0.0.0:${Protocol.PORT}")
                while (running) {
                    val conn = try {
                        srv.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept failed: ${e.message}")
                        break
                    }
                    handleConnection(conn)
                }
            } catch (e: Exception) {
                Log.e(TAG, "server error: ${e.message}")
            }
        }
        serverThread?.start()
    }

    private fun handleConnection(conn: Socket) {
        try {
            conn.soTimeout = Protocol.SOCKET_TIMEOUT_MS
            conn.use {
                val text = Protocol.readFrame(it.getInputStream(), Prefs.getSecret(this))
                if (text != null) {
                    Log.i(TAG, "received ${text.length} chars from mac")
                    remoteSetAt = System.currentTimeMillis()
                    setClipboardText(text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection error: ${e.message}")
        }
    }

    private fun sendToMac(text: String) {
        val macIp = Prefs.getMacIp(this)
        if (macIp.isNullOrEmpty()) {
            Log.w(TAG, "mac IP not configured, skipping push")
            return
        }
        Thread {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(macIp, Protocol.PORT), Protocol.SOCKET_TIMEOUT_MS)
                    socket.getOutputStream().write(
                        Protocol.frame(Prefs.getSecret(this), text.toByteArray(Charsets.UTF_8))
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "send to mac failed: ${e.message}")
            }
        }.start()
    }

    private fun startKeepalive() {
        keepaliveThread = Thread {
            while (running) {
                try {
                    Thread.sleep(Protocol.KEEPALIVE_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
                sendToMac("")
                Log.i(TAG, "keepalive sent to mac")
            }
        }
        keepaliveThread?.start()
    }

    companion object {
        private const val TAG = "ClipsyncdService"
        private const val NOTIFICATION_ID = 1
        @Volatile var isRunning: Boolean = false
            private set
    }
}
