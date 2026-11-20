package com.controlx

import android.app.Application

class ControlXApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            getSharedPreferences("controlx_crash", MODE_PRIVATE)
                .edit()
                .putString(
                    "last_crash",
                    buildString {
                        append(thread.name).append('\n')
                        append(throwable.javaClass.name).append(": ")
                        append(throwable.message)
                        append('\n')
                        throwable.stackTrace.take(12).forEach { frame ->
                            append(frame.toString()).append('\n')
                        }
                    },
                )
                .commit()
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun readLastCrash(application: Application): String? =
            application.getSharedPreferences("controlx_crash", MODE_PRIVATE)
                .getString("last_crash", null)
    }
}
