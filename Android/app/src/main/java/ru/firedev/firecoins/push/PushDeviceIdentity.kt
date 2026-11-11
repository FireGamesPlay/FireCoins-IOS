package ru.firedev.firecoins.push

import android.content.Context
import java.util.UUID

object PushDeviceIdentity {
    private const val PREFS = "firecoins_push"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TOKEN = "fcm_token"

    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    fun token(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)

    fun saveToken(context: Context, value: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, value).apply()
    }
}
