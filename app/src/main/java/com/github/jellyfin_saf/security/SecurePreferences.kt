package com.github.jellyfin_saf.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Encrypted credential storage backed by Android Keystore AES-256-GCM.
 * Stores server URLs, authentication tokens, and user settings securely.
 */
class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences

    init {
        val appContext = context.applicationContext
        prefs = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences. Resetting corrupted store.", e)
            // If Keystore state was invalidated (e.g. key reset), clear corrupted store and recreate
            appContext.getSharedPreferences(ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            val fallbackKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                fallbackKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        // Ensure unique, immutable device ID exists
        if (!prefs.contains(KEY_DEVICE_ID)) {
            prefs.edit().putString(KEY_DEVICE_ID, UUID.randomUUID().toString()).apply()
        }
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "jellyfin-audio-provider-dev") ?: "jellyfin-audio-provider-dev"

    var maxCacheMb: Long
        get() = prefs.getLong(KEY_MAX_CACHE_MB, DEFAULT_CACHE_SIZE_MB)
        set(value) = prefs.edit().putLong(KEY_MAX_CACHE_MB, value).apply()

    fun isAuthenticated(): Boolean {
        return !accessToken.isNullOrBlank() && !userId.isNullOrBlank() && serverUrl.isNotBlank()
    }

    /** Clears all stored authentication credentials. */
    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_USER_ID)
            .apply()
    }

    companion object {
        private const val TAG = "SecurePreferences"
        private const val ENCRYPTED_PREFS_NAME = "secure_credentials"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MAX_CACHE_MB = "max_cache_mb"

        const val DEFAULT_CACHE_SIZE_MB: Long = 2048L // 2 GB default
    }
}
