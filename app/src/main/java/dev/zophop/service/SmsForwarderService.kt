package dev.zophop.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.zophop.R
import dev.zophop.network.ConnectivityMonitor
import dev.zophop.ui.MainActivity
import javax.inject.Inject

@AndroidEntryPoint
class SmsForwarderService : Service() {
    @Inject
    lateinit var connectivityMonitor: ConnectivityMonitor

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityMonitor.register()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "ZopHop Active"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Monitoring incoming SMS and forwarding OTPs"
        val notification = createNotification(title, text)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            START_STICKY
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Unable to promote SmsForwarderService to foreground: ${e.message}")
            stopSelf(startId)
            START_NOT_STICKY
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException starting foreground SmsForwarderService: ${e.message}")
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        connectivityMonitor.unregister()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(
        title: String,
        text: String,
    ): Notification {
        val openAppIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "SMS Forwarder Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows status while SMS OTP forwarding is enabled"
            }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SmsForwarderService"
        const val CHANNEL_ID = "zophop_forwarder_channel"
        const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "extra_notification_title"
        private const val EXTRA_TEXT = "extra_notification_text"

        fun startService(context: Context): Boolean {
            val intent = Intent(context, SmsForwarderService::class.java)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Deferred foreground service start (background restriction): ${e.message}")
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "Deferred foreground service start (security restriction): ${e.message}")
                false
            }
        }

        fun updateNotificationStatus(
            context: Context,
            title: String,
            text: String,
        ) {
            val manager =
                context.getSystemService(NotificationManager::class.java)
                    ?: return

            val openAppIntent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()

            try {
                manager.notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }

        fun resetToDefaultStatus(context: Context) {
            updateNotificationStatus(
                context,
                "ZopHop Active",
                "Monitoring incoming SMS and forwarding OTPs",
            )
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SmsForwarderService::class.java)
            context.stopService(intent)
        }
    }
}
