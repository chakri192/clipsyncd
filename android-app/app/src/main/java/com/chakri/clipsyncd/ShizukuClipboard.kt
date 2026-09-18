package com.chakri.clipsyncd

import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Reads the clipboard via a Shizuku-brokered binder call instead of the
 * normal ClipboardManager path. Confirmed empirically (see project history)
 * that Android denies ClipboardManager reads from apps without UI focus —
 * even ones with an enabled AccessibilityService — but a call made through
 * Shizuku (which proxies with adb-shell-level privilege) is not subject to
 * that focus check.
 *
 * IClipboard is a hidden, non-SDK framework interface: reflecting into it
 * from a normal installed app is blocked by Android's hidden-API enforcement
 * (reflection on it silently reports NoSuchMethodException, confirmed via
 * logcat — an app_process-launched process isn't subject to this, which is
 * why the standalone probe worked but the app itself didn't until this
 * exemption was added).
 */
object ShizukuClipboard {
    private const val TAG = "ShizukuClipboard"
    private const val CALLING_PACKAGE = "com.android.shell"

    init {
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/IClipboard")
        } catch (e: Throwable) {
            Log.w(TAG, "hidden API exemption failed: ${e.message}")
        }
    }

    fun isReady(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun readText(): String? {
        try {
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("clipboard"))
            val stubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val clipboard = asInterface.invoke(null, binder)
            val getPrimaryClip = clipboard.javaClass.getMethod(
                "getPrimaryClip", String::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            val clip = getPrimaryClip.invoke(clipboard, CALLING_PACKAGE, null, 0, 0) ?: return null
            val getItemCount = clip.javaClass.getMethod("getItemCount")
            val count = getItemCount.invoke(clip) as Int
            if (count == 0) return null
            val getItemAt = clip.javaClass.getMethod("getItemAt", Int::class.javaPrimitiveType)
            val item = getItemAt.invoke(clip, 0)
            val getText = item.javaClass.getMethod("getText")
            return (getText.invoke(item) as? CharSequence)?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "readText failed: ${e.message}")
            return null
        }
    }
}
