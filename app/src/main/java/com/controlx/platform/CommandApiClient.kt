package com.controlx.platform

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.controlx.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonParser
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

data class AuthorizedHttpResponse(
    val code: Int,
    val body: String,
    val isSuccessful: Boolean,
    val errorMessage: String? = null,
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val authLock = Any()

    @Volatile
    private var cachedDeviceId: String? = null

    fun currentDeviceId(): String = synchronized(authLock) {
        cachedDeviceId?.let { return@synchronized it }
        val existing = preferences.getString(KEY_DEVICE_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = existing ?: UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(KEY_DEVICE_ID, generated).commit()
        }
        cachedDeviceId = resolved
        resolved
    }

    fun configure(baseUrl: String, apiToken: String = ""): Boolean =
        configureServerUrl(baseUrl, apiToken)

    fun configureServerUrl(baseUrl: String, optionalAdminToken: String = ""): Boolean {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isNotEmpty() &&
            !normalized.startsWith("https://") &&
            !normalized.startsWith("http://")
        ) {
            return false
        }
        val previousUrl = getEffectiveBaseUrl()
        val editor = preferences.edit()
        if (normalized.isEmpty()) {
            editor.remove(KEY_BASE_URL)
        } else {
            editor.putString(KEY_BASE_URL, normalized)
        }
        if (optionalAdminToken.trim().isNotEmpty()) {
            editor.putString(KEY_API_TOKEN, optionalAdminToken.trim())
        } else {
            editor.remove(KEY_API_TOKEN)
        }
        if (normalized != previousUrl) {
            editor.remove(KEY_DEVICE_TOKEN)
        }
        return editor.commit()
    }

    fun clearConfiguration(): Boolean =
        synchronized(authLock) {
            cachedDeviceId = null
            preferences.edit()
                .remove(KEY_BASE_URL)
                .remove(KEY_API_TOKEN)
                .remove(KEY_DEVICE_TOKEN)
                .commit()
        }

    fun getEffectiveBaseUrl(): String {
        val saved = preferences.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/').orEmpty()
        if (saved.isNotEmpty()) return saved
        return BuildConfig.DEFAULT_BACKEND_URL.trim().trimEnd('/')
    }

    fun hasDeviceToken(): Boolean =
        !preferences.getString(KEY_DEVICE_TOKEN, null).isNullOrBlank() ||
            !preferences.getString(KEY_API_TOKEN, null).isNullOrBlank()

    fun getConfiguration(): ApiConfiguration =
        ApiConfiguration(
            baseUrl = getEffectiveBaseUrl(),
            apiToken = preferences.getString(KEY_DEVICE_TOKEN, null)
                ?.ifBlank { null }
                ?: preferences.getString(KEY_API_TOKEN, "").orEmpty(),
        )

    fun isConfigured(): Boolean = getEffectiveBaseUrl().isNotBlank()

    fun autoDisableAccessibilityOnPayment(): Boolean =
        preferences.getBoolean(KEY_AUTO_DISABLE_PAYMENT_ACCESSIBILITY, true)

    fun setAutoDisableAccessibilityOnPayment(enabled: Boolean): Boolean =
        preferences.edit()
            .putBoolean(KEY_AUTO_DISABLE_PAYMENT_ACCESSIBILITY, enabled)
            .commit()

    fun ensureDeviceToken(forceReset: Boolean = false): String? = synchronized(authLock) {
        val baseUrl = getEffectiveBaseUrl()
        if (baseUrl.isBlank()) return@synchronized null

        val legacyAdmin = preferences.getString(KEY_API_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (legacyAdmin != null && !forceReset) return@synchronized legacyAdmin

        val existingToken = if (forceReset) {
            null
        } else {
            preferences.getString(KEY_DEVICE_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (existingToken != null) return@synchronized existingToken

        if (forceReset) {
            preferences.edit().remove(KEY_DEVICE_TOKEN).commit()
        }

        repeat(2) { attempt ->
            val deviceId = if (forceReset && attempt == 0 || attempt > 0) {
                UUID.randomUUID().toString().also { freshId ->
                    cachedDeviceId = freshId
                    preferences.edit().putString(KEY_DEVICE_ID, freshId).commit()
                }
            } else {
                currentDeviceId()
            }
            val payload = gson.toJson(
                mapOf(
                    "deviceId" to deviceId,
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                ),
            )
            val request = Request.Builder()
                .url("$baseUrl/api/v1/devices/register")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val registeredToken = runCatching {
                client.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string().orEmpty()
                    if (response.code == 409) {
                        return@use null
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Device registration failed (HTTP ${response.code}).")
                    }
                    JsonParser.parseString(rawBody)
                        .takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("deviceToken")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            }.getOrElse { error ->
                if (attempt == 1) throw error
                null
            }
            if (registeredToken != null) {
                preferences.edit().putString(KEY_DEVICE_TOKEN, registeredToken).commit()
                return@synchronized registeredToken
            }
        }
        null
    }

    fun postAuthorizedJson(
        path: String,
        bodyBuilder: (deviceId: String) -> String,
    ): AuthorizedHttpResponse {
        val baseUrl = getEffectiveBaseUrl()
        if (baseUrl.isBlank()) {
            throw IllegalStateException("Backend server URL is not configured.")
        }
        var token = ensureDeviceToken(forceReset = false)
            ?: throw IllegalStateException("Could not register this device with the backend.")

        repeat(2) { attempt ->
            val deviceId = currentDeviceId()
            val request = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(bodyBuilder(deviceId).toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (response.code == 401 && attempt == 0) {
                    token = ensureDeviceToken(forceReset = true)
                        ?: return AuthorizedHttpResponse(
                            code = 401,
                            body = rawBody,
                            isSuccessful = false,
                            errorMessage = extractErrorMessage(rawBody) ?: "Unauthorized device session.",
                        )
                    return@use
                }
                return AuthorizedHttpResponse(
                    code = response.code,
                    body = rawBody,
                    isSuccessful = response.isSuccessful,
                    errorMessage = if (response.isSuccessful) null else extractErrorMessage(rawBody),
                )
            }
        }
        return AuthorizedHttpResponse(
            code = 401,
            body = "",
            isSuccessful = false,
            errorMessage = "Device authentication failed.",
        )
    }

    fun scanInstalledApps(): List<AppInventoryRecord> {
        val deviceId = currentDeviceId()
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
        val launchers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        val inventory = scanInstalledApps()
        return runCatching {
            postAuthorizedJson("/api/v1/app-inventory") { deviceId ->
                gson.toJson(
                    mapOf(
                        "deviceId" to deviceId,
                        "apps" to inventory.map { it.copy(deviceId = deviceId) },
                    ),
                )
            }.isSuccessful
        }.getOrElse { false }
    }

    fun saveCommand(
        commandText: String,
        source: String,
        resultStatus: String,
        resultMessage: String?,
        latencyMs: Long?,
    ): Boolean {
        if (!isConfigured()) return false
        return runCatching {
            postAuthorizedJson("/api/v1/commands") { deviceId ->
                gson.toJson(
                    CommandHistoryRecord(
                        deviceId = deviceId,
                        commandText = commandText,
                        source = source,
                        resultStatus = resultStatus,
                        resultMessage = resultMessage,
                        latencyMs = latencyMs,
                    ),
                )
            }.isSuccessful
        }.getOrElse { false }
    }

    private fun extractErrorMessage(rawBody: String): String? =
        runCatching {
            JsonParser.parseString(rawBody)
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("error")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.take(160)
        }.getOrNull()

    data class ApiConfiguration(
        val baseUrl: String,
        val apiToken: String,
    )

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_INVENTORY_UPLOAD = "inventory_upload"
        private const val KEY_AUTO_DISABLE_PAYMENT_ACCESSIBILITY = "auto_disable_payment_accessibility"
    }
}
