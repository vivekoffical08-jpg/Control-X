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
import com.controlx.platform.CommandApiClient
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
        const val ACTION_ENABLE_BUBBLE = "com.controlx.overlay.ENABLE_BUBBLE"
        const val ACTION_DISABLE_BUBBLE = "com.controlx.overlay.DISABLE_BUBBLE"
        private const val CHANNEL_ID = "controlx_assistant"
        private const val NOTIFICATION_ID = 1701

        fun start(context: Context) {
            val intent = Intent(context, OverlayControllerService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OverlayControllerService::class.java).apply {
                        action = ACTION_HIDE
                    },
                )
            }
        }

        fun enableBubble(context: Context) {
            val intent = Intent(context, OverlayControllerService::class.java).apply {
                action = ACTION_ENABLE_BUBBLE
            }
            context.startForegroundService(intent)
        }

        fun disableBubble(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OverlayControllerService::class.java).apply {
                        action = ACTION_DISABLE_BUBBLE
                    },
                )
            }
        }
    }

    private val commandApiClient by lazy { CommandApiClient(applicationContext) }
    private var controller: OverlayController? = null
    private var floatingTrigger: FloatingTriggerManager? = null
    private var overlayVisible = false
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

        floatingTrigger = FloatingTriggerManager(applicationContext).apply {
            onActivateRequested = { showOverlay() }
            onHideRequested = {
                commandApiClient.setFloatingBubbleEnabled(false)
                hide()
            }
            if (commandApiClient.floatingBubbleEnabled()) {
                setSuppressed(PaymentSafeModeMonitor.shared.isActive || overlayVisible)
                show()
            }
        }

        serviceScope.launch {
            PaymentSafeModeMonitor.shared.state.collect { state ->
                floatingTrigger?.setSuppressed(state.active || overlayVisible)
                updateNotification(state.accessibilityAutoDisabled || state.active)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                controller?.hide()
                floatingTrigger?.hide()
                overlayVisible = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISABLE_ACCESSIBILITY -> {
                ControlAccessibilityService.disableForPaymentSafety()
                updateNotification(paymentSafeMode = true)
                return START_STICKY
            }
            ACTION_ENABLE_BUBBLE -> {
                commandApiClient.setFloatingBubbleEnabled(true)
                floatingTrigger?.setSuppressed(PaymentSafeModeMonitor.shared.isActive || overlayVisible)
                floatingTrigger?.show()
                updateNotification(PaymentSafeModeMonitor.shared.isActive)
                return START_STICKY
            }
            ACTION_DISABLE_BUBBLE -> {
                commandApiClient.setFloatingBubbleEnabled(false)
                floatingTrigger?.hide()
                updateNotification(PaymentSafeModeMonitor.shared.isActive)
                return START_STICKY
            }
        }

        if (commandApiClient.floatingBubbleEnabled()) {
            floatingTrigger?.setSuppressed(PaymentSafeModeMonitor.shared.isActive || overlayVisible)
            floatingTrigger?.show()
        }

        if (intent != null) {
            showOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        floatingTrigger?.hide()
        floatingTrigger = null
        controller?.destroy()
        controller = null
        overlayVisible = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        controller?.hide()
        val next = OverlayController(applicationContext).apply {
            onOverlayVisibilityChanged = { visible ->
                overlayVisible = visible
                floatingTrigger?.setSuppressed(visible || PaymentSafeModeMonitor.shared.isActive)
            }
        }
        controller = next
        if (!next.show()) {
            overlayVisible = false
            floatingTrigger?.setSuppressed(PaymentSafeModeMonitor.shared.isActive)
        }
    }

    private fun updateNotification(paymentSafeMode: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(paymentSafeMode))
    }

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
        val askIntent = PendingIntent.getForegroundService(
            this,
            1709,
            Intent(this, OverlayControllerService::class.java).apply {
                action = ACTION_SHOW
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
                "Payment Safe Mode: assistant paused while payment app is open."
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
            .setContentIntent(askIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                "Ask Control X",
                askIntent,
            )

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
