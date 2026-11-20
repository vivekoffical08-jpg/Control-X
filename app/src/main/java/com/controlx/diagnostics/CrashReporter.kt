package com.controlx.diagnostics

import android.content.Context
import java.io.File

object CrashReporter {
    private const val CRASH_FILE_NAME = "last_crash.txt"

    fun persistCrash(context: Context, report: String) {
        runCatching {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            val temporary = File(context.filesDir, "$CRASH_FILE_NAME.tmp")
            temporary.writeText(report, Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                file.writeText(report, Charsets.UTF_8)
            }
        }
    }

    fun readPendingCrash(context: Context): String? = runCatching {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        if (file.exists() && file.length() > 0) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    fun clearPendingCrash(context: Context): Boolean = runCatching {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        !file.exists() || file.delete()
    }.getOrDefault(false)
}
