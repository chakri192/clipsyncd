package com.chakri.clipsyncd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

    private val nsdHelper by lazy {
        NsdHelper(
            this,
            onResolved = { host, port ->
                Log.i(TAG, "mac discovered via mDNS: $host:$port")
                discoveredMacHost = host
                discoveredMacPort = port
            },
            onLost = { discoveredMacHost = null }
        )
    }

    // While the screen is off the phone's Wi-Fi sleeps and the Mac's pushes can
    // miss it. The Mac holds what it couldn't deliver and hands it over the next
    // time we contact it, so ping the moment we wake instead of waiting for the
    // next 30s keepalive.
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "${intent.action}, pinging mac")
            sendToMac("")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            isRunning = true
            startForegroundWithNotification()
            ContextCompat.registerReceiver(
                this,
                wakeReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            nsdHelper.start()
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
        try {
            unregisterReceiver(wakeReceiver)
        } catch (e: IllegalArgumentException) {
            // never registered — service died before onStartCommand finished
        }
        nsdHelper.stop()
        discoveredMacHost = null
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
            .setContentTitle("clipsyncd active")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
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
        val host = discoveredMacHost ?: Prefs.getMacIp(this)
        val port = if (discoveredMacHost != null) discoveredMacPort else Protocol.PORT
        if (host.isNullOrEmpty()) {
            Log.w(TAG, "mac address not known (no mDNS discovery yet, no manual IP set), skipping push")
            return
        }
        Thread {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), Protocol.SOCKET_TIMEOUT_MS)
                    socket.getOutputStream().write(
                        Protocol.frame(Prefs.getSecret(this), text.toByteArray(Charsets.UTF_8))
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "send to mac failed: ${e.message}")
                if (host == discoveredMacHost) discoveredMacHost = null
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
        @Volatile var discoveredMacHost: String? = null
            private set
        @Volatile var discoveredMacPort: Int = Protocol.PORT
            private set
    }
}
