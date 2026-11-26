package com.controlx.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.Executors

data class CatalogApp(
    val packageName: String,
    val label: String,
    val launchable: Boolean,
)

object AppCatalogManager {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var catalog: List<CatalogApp> = emptyList()

    fun snapshot(): List<CatalogApp> = catalog

    fun findLaunchable(query: String): CatalogApp? =
        LocalCatalogMatcher.uniqueBestMatch(
            query = query,
            candidates = catalog.filter(CatalogApp::launchable),
            label = CatalogApp::label,
        )

    fun refreshAsync(context: Context, onComplete: () -> Unit = {}) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                refresh(appContext)
            } finally {
                onComplete()
            }
        }
    }

    @Synchronized
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }
        val launchablePackages = launchers.mapNotNull {
            it.activityInfo?.packageName
        }.toSet()
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
        }

        catalog = installed.mapNotNull { applicationInfo ->
            val packageName = applicationInfo.packageName ?: return@mapNotNull null
            if (packageName == appContext.packageName) return@mapNotNull null
            val label = runCatching {
                packageManager.getApplicationLabel(applicationInfo).toString()
            }.getOrDefault(packageName)
            CatalogApp(packageName, label, launchable = packageName in launchablePackages)
        }.distinctBy(CatalogApp::packageName)
            .sortedBy { it.packageName }
    }
}
