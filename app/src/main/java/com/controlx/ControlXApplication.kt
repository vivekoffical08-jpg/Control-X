package com.controlx

import android.app.Application
import android.os.Build
import com.controlx.diagnostics.CrashReporter
import com.controlx.platform.AppCatalogManager
import com.controlx.platform.ContactCatalogManager

class ControlXApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCatalogManager.refreshAsync(this)
        ContactCatalogManager.refreshAsync(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildString {
                    append("CRASH TIMESTAMP: ").append(System.currentTimeMillis()).append('\n')
                    append("DEVICE: ").append(Build.MANUFACTURER).append(' ')
                        .append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
                    append("ANDROID OS: ").append(Build.VERSION.RELEASE)
                        .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Thread: ").append(thread.name).append('\n')
                    append("Exception: ").append(throwable.javaClass.name).append('\n')
                    append("Message: ").append(throwable.message).append('\n').append('\n')
                    append(throwable.stackTraceToString())
                    var cause = throwable.cause
                    while (cause != null) {
                        append("\nCaused by: ").append(cause.stackTraceToString())
                        cause = cause.cause
                    }
                }
                CrashReporter.persistCrash(this, report)
            } catch (_: Exception) {
                // Preserve the original Android crash handler even if storage fails.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun readLastCrash(application: Application): String? =
            CrashReporter.readPendingCrash(application)

        fun clearLastCrash(application: Application) =
            CrashReporter.clearPendingCrash(application)
    }
}
