package com.chakri.clipsyncd

import android.content.Context

object Prefs {
    private const val FILE = "clipsyncd_prefs"
    private const val KEY_MAC_IP = "mac_ip"
    private const val KEY_SECRET = "secret"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getMacIp(context: Context): String? = prefs(context).getString(KEY_MAC_IP, null)

    fun getSecret(context: Context): String? = prefs(context).getString(KEY_SECRET, null)

    fun save(context: Context, macIp: String, secret: String) {
        prefs(context).edit()
            .putString(KEY_MAC_IP, macIp.trim())
            .putString(KEY_SECRET, secret)
            .apply()
    }
}
