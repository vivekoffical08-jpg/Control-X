package com.controlx.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.controlx.agent.NvidiaNimApiClient

data class NvidiaAiConfig(
    val apiKey: String,
    val model: String,
)

class NvidiaKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    private val preferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "controlx_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): String? =
        runCatching {
            preferences.getString(KEY_NVIDIA_API_KEY, null)?.trim()?.takeIf(String::isNotEmpty)
        }.getOrNull()

    fun loadModel(): String =
        runCatching {
            preferences.getString(KEY_NVIDIA_MODEL, null)?.trim()?.takeIf(String::isNotEmpty)
        }.getOrNull() ?: NvidiaNimApiClient.DEFAULT_MODEL

    fun loadConfig(): NvidiaAiConfig =
        NvidiaAiConfig(
            apiKey = load().orEmpty(),
            model = loadModel(),
        )

    fun save(
        key: String,
        model: String = NvidiaNimApiClient.DEFAULT_MODEL,
    ): Boolean {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) return false
        val resolvedModel = model.trim().ifEmpty { NvidiaNimApiClient.DEFAULT_MODEL }
        return runCatching {
            preferences.edit()
                .putString(KEY_NVIDIA_API_KEY, trimmedKey)
                .putString(KEY_NVIDIA_MODEL, resolvedModel)
                .commit()
        }.getOrElse { false }
    }

    fun clear(): Boolean =
        runCatching {
            preferences.edit()
                .remove(KEY_NVIDIA_API_KEY)
                .remove(KEY_NVIDIA_MODEL)
                .remove(LEGACY_KEY_GROQ)
                .commit()
        }.getOrElse { false }

    companion object {
        private const val KEY_NVIDIA_API_KEY = "nvidia_api_key"
        private const val KEY_NVIDIA_MODEL = "nvidia_model"
        private const val LEGACY_KEY_GROQ = "groq_api_key"
    }
}
