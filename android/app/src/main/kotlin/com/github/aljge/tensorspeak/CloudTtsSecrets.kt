package com.github.aljge.tensorspeak

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-encrypted storage for cloud TTS API keys. Falls back to "nothing configured"
 * rather than crashing if the wrapping key can't be recovered (e.g. after a cross-device
 * backup restore, where the Keystore entry doesn't travel with it).
 */
object CloudTtsSecrets {
    private const val FILE = "tensorspeak_cloud_secrets"
    private const val KEY_OPENAI = "openai_api_key"
    private const val KEY_ELEVENLABS = "elevenlabs_api_key"
    private const val KEY_DEEPGRAM = "deepgram_api_key"
    private const val KEY_CUSTOM = "custom_api_key"
    private const val TAG = "CloudTtsSecrets"

    private fun prefs(context: Context): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure { Log.w(TAG, "cloud secrets unavailable", it) }.getOrNull()

    fun openAiApiKey(context: Context): String = get(context, KEY_OPENAI)
    fun setOpenAiApiKey(context: Context, key: String) = set(context, KEY_OPENAI, key)

    fun elevenLabsApiKey(context: Context): String = get(context, KEY_ELEVENLABS)
    fun setElevenLabsApiKey(context: Context, key: String) = set(context, KEY_ELEVENLABS, key)

    fun deepgramApiKey(context: Context): String = get(context, KEY_DEEPGRAM)
    fun setDeepgramApiKey(context: Context, key: String) = set(context, KEY_DEEPGRAM, key)

    fun customApiKey(context: Context): String = get(context, KEY_CUSTOM)
    fun setCustomApiKey(context: Context, key: String) = set(context, KEY_CUSTOM, key)

    private fun get(context: Context, key: String): String =
        runCatching { prefs(context)?.getString(key, "").orEmpty() }
            .getOrElse { "" }
            .trim()

    private fun set(context: Context, key: String, value: String) {
        runCatching { prefs(context)?.edit()?.putString(key, value.trim())?.apply() }
            .onFailure { Log.w(TAG, "failed to persist cloud secret", it) }
    }
}
