package com.controlx.router

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object AppLauncher {
    fun launch(context: Context, query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isEmpty()) return false

        val exact = context.packageManager.getLaunchIntentForPackage(normalized)
        if (exact != null) {
            context.startActivity(exact.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        val matches = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_ALL,
            )
        }

        val resolved = matches.firstOrNull { resolve ->
            val label = resolve.loadLabel(context.packageManager).toString()
            label.equals(normalized, ignoreCase = true) ||
                label.contains(normalized, ignoreCase = true)
        } ?: return false
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
