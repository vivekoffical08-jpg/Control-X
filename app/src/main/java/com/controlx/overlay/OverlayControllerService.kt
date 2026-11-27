package com.controlx.overlay

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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.controlx.accessibility.ControlAccessibilityService
import com.controlx.security.PaymentSafeModeMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayControllerService : Service() {
    companion object {
        const val ACTION_SHOW = "com.controlx.overlay.SHOW"
        const val ACTION_HIDE = "com.controlx.overlay.HIDE"
        const val ACTION_DISABLE_ACCESSIBILITY = "com.controlx.overlay.DISABLE_ACCESSIBILITY"
        private const val CHANNEL_ID = "controlx_assistant"
        private const val NOTIFICATION_ID = 1701

        fun start(context: Context) {
            val intent = Intent(context, OverlayControllerService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayControllerService::class.java).apply {
                    action = ACTION_HIDE
                },
            )
        }
    }

    private var controller: OverlayController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification(PaymentSafeModeMonitor.shared.state.value.accessibilityAutoDisabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        serviceScope.launch {
            PaymentSafeModeMonitor.shared.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(
                        NOTIFICATION_ID,
                        buildNotification(state.accessibilityAutoDisabled || state.active),
                    )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                controller?.hide()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISABLE_ACCESSIBILITY -> {
                ControlAccessibilityService.disableForPaymentSafety()
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(paymentSafeMode = true))
                return START_STICKY
            }
        }
        controller?.hide()
        controller = OverlayController(applicationContext).also { it.show() }
        return START_STICKY
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Control X Assistant",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the Control X assistant overlay available."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(paymentSafeMode: Boolean): Notification {
        val accessibilityActive = ControlAccessibilityService.isActive()
        val settingsIntent = PendingIntent.getActivity(
            this,
            1710,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = when {
            paymentSafeMode && !accessibilityActive ->
                "Payment Safe Mode: overlay paused and Accessibility turned off for UPI/banking safety."
            paymentSafeMode ->
                "Payment Safe Mode: overlay paused. Tap Disable Accessibility or open Settings if blocked."
            accessibilityActive ->
                "Assistant & Screen Agent active. Disable Accessibility before opening Google Pay/UPI."
            else ->
                "Assistant ready (Accessibility OFF — Google Pay / UPI safe)."
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Control X is active")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (accessibilityActive) {
            val disableIntent = PendingIntent.getService(
                this,
                1711,
                Intent(this, OverlayControllerService::class.java).apply {
                    action = ACTION_DISABLE_ACCESSIBILITY
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_lock_power_off,
                "Disable Accessibility",
                disableIntent,
            )
        }

        return builder.addAction(
            android.R.drawable.ic_menu_manage,
            "Accessibility settings",
            settingsIntent,
        ).build()
    }
}
