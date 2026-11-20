package com.controlx

import android.app.Application

class ControlXApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildString {
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
                filesDir.mkdirs()
                java.io.File(filesDir, "last_crash.txt")
                    .writeText(report, Charsets.UTF_8)
            } catch (_: Exception) {
                // Preserve the original Android crash handler even if storage fails.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun readLastCrash(application: Application): String? =
            runCatching {
                java.io.File(application.filesDir, "last_crash.txt")
                    .takeIf(java.io.File::exists)
                    ?.readText(Charsets.UTF_8)
            }.getOrNull()

        fun clearLastCrash(application: Application) =
            runCatching {
                java.io.File(application.filesDir, "last_crash.txt").delete()
            }.getOrElse { false }
    }
}
