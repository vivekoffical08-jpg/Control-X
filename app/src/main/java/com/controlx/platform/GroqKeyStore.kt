package com.controlx.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class GroqKeyStore(context: Context) {
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
            preferences.getString(KEY_GROQ, null)?.trim()?.takeIf(String::isNotEmpty)
        }.getOrNull()

    fun save(key: String): Boolean =
        runCatching {
            preferences.edit()
                .putString(KEY_GROQ, key.trim())
                .commit()
        }.getOrElse { false }

    fun clear(): Boolean =
        runCatching {
            preferences.edit().remove(KEY_GROQ).commit()
        }.getOrElse { false }

    companion object {
        private const val KEY_GROQ = "groq_api_key"
    }
}
