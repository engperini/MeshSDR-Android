package com.engperini.meshsdr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps reception alive with the screen off: a foreground service stops
 * Android from freezing the process, and a partial wake lock keeps the CPU
 * running while IQ is streamed. Started on connect, stopped on disconnect.
 */
class ReceiverService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("LoRa reception", "Recepção LoRa"), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(tr("MeshSDR receiving", "MeshSDR recebendo"))
            .setContentText(intent?.getStringExtra(EXTRA_TEXT) ?: tr("Listening for LoRa via RTL-SDR", "Escutando LoRa pelo RTL-SDR"))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshSDR:receiver")
                .apply { acquire(MAX_HOLD_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TEXT = "text"
        /** Safety cap; a long session can simply reconnect. */
        private const val MAX_HOLD_MS = 12L * 60 * 60 * 1000

        fun start(context: Context, text: String) {
            context.startForegroundService(Intent(context, ReceiverService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}
