package com.controlx.router

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.controlx.platform.AppCatalogManager

object AppLauncher {
    fun launch(context: Context, query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isEmpty()) return false

        val exact = context.packageManager.getLaunchIntentForPackage(normalized)
        if (exact != null) {
            context.startActivity(exact.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        if (AppCatalogManager.snapshot().isEmpty()) AppCatalogManager.refresh(context)
        val matched = AppCatalogManager.findLaunchable(normalized) ?: return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(matched.packageName)
            ?: return false
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
