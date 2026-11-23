package com.controlx.platform

import android.content.Context
import com.google.gson.Gson
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CommandHistoryRecord(
    val deviceId: String,
    val commandText: String,
    val source: String,
    val resultStatus: String,
    val resultMessage: String?,
    val latencyMs: Long?,
)

data class AppInventoryRecord(
    val deviceId: String,
    val packageName: String,
    val label: String,
)

class CommandApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    private val preferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "controlx_api",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val deviceId = preferences.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    fun configure(baseUrl: String, apiToken: String): Boolean {
        val normalized = baseUrl.trim().trimEnd('/')
        if (!normalized.startsWith("https://") &&
            !normalized.startsWith("http://")
        ) {
            return false
        }
        if (apiToken.trim().isBlank()) return false
        return preferences.edit()
            .putString(KEY_BASE_URL, normalized)
            .putString(KEY_API_TOKEN, apiToken.trim())
            .commit()
    }

    fun clearConfiguration(): Boolean =
        preferences.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_API_TOKEN)
            .commit()

    fun getConfiguration(): ApiConfiguration =
        ApiConfiguration(
            baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
            apiToken = preferences.getString(KEY_API_TOKEN, "").orEmpty(),
        )

    fun isConfigured(): Boolean =
        getConfiguration().let { it.baseUrl.isNotBlank() && it.apiToken.isNotBlank() }

    fun scanInstalledApps(): List<AppInventoryRecord> {
        val inventoryByPackage = linkedMapOf<String, AppInventoryRecord>()
        val packages = appContext.packageManager.getInstalledPackages(0)
        packages.forEach { info ->
            val packageName = info.packageName
            val label = runCatching {
                info.applicationInfo?.let {
                    appContext.packageManager.getApplicationLabel(it).toString()
                } ?: packageName
            }.getOrDefault(packageName)
            if (packageName != appContext.packageName) {
                inventoryByPackage[packageName] =
                    AppInventoryRecord(deviceId, packageName.take(256), label.take(256))
            }
        }

        val launcherIntent = android.content.Intent(
            android.content.Intent.ACTION_MAIN,
        ).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val launchers = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryIntentActivities(
                launcherIntent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(
                    android.content.pm.PackageManager.MATCH_ALL.toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentActivities(
                launcherIntent,
                android.content.pm.PackageManager.MATCH_ALL,
            )
        }
        launchers.forEach { resolved ->
            val packageName = resolved.activityInfo?.packageName ?: return@forEach
            if (packageName == appContext.packageName) return@forEach
            val label = runCatching {
                resolved.loadLabel(appContext.packageManager).toString()
            }.getOrDefault(packageName)
            inventoryByPackage.putIfAbsent(
                packageName,
                AppInventoryRecord(deviceId, packageName.take(256), label.take(256)),
            )
        }
        return inventoryByPackage.values.sortedBy { it.packageName }
    }

    fun inventoryUploadEnabled(): Boolean =
        preferences.getBoolean(KEY_INVENTORY_UPLOAD, false)

    fun setInventoryUploadEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_INVENTORY_UPLOAD, enabled)
            .commit()
    }

    fun syncAppInventory(): Boolean {
        if (!isConfigured() || !inventoryUploadEnabled()) return false
        val configuration = getConfiguration()
        val inventory = scanInstalledApps()
        val request = Request.Builder()
            .url("${configuration.baseUrl}/api/v1/app-inventory")
            .header("Authorization", "Bearer ${configuration.apiToken}")
            .header("Content-Type", "application/json")
            .post(
                gson.toJson(
                    mapOf(
                        "deviceId" to deviceId,
                        "apps" to inventory,
                    ),
                ).toRequestBody("application/json".toMediaType()),
            )
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse { false }
    }

    fun saveCommand(
        commandText: String,
        source: String,
        resultStatus: String,
        resultMessage: String?,
        latencyMs: Long?,
    ): Boolean {
        val configuration = getConfiguration()
        if (configuration.baseUrl.isBlank() || configuration.apiToken.isBlank()) {
            return false
        }
        val record = CommandHistoryRecord(
            deviceId = deviceId,
            commandText = commandText,
            source = source,
            resultStatus = resultStatus,
            resultMessage = resultMessage,
            latencyMs = latencyMs,
        )
        val request = Request.Builder()
            .url("${configuration.baseUrl}/api/v1/commands")
            .header("Authorization", "Bearer ${configuration.apiToken}")
            .header("Content-Type", "application/json")
            .post(
                gson.toJson(record)
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse { false }
    }

    data class ApiConfiguration(
        val baseUrl: String,
        val apiToken: String,
    )

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_INVENTORY_UPLOAD = "inventory_upload"
    }
}
