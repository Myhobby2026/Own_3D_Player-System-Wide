package com.neonplayer.systemwide.systemwide

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

object SystemWidePermissions {
    const val OP_PROJECT_MEDIA = "android:project_media"

    fun hasDump(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    fun hasRecordAudio(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun hasProjectMediaAppOp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val method = AppOpsManager::class.java.getMethod(
                "unsafeCheckOpNoThrow",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val result = method.invoke(appOps, OP_PROJECT_MEDIA, Process.myUid(), context.packageName) as? Int
            result == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun adbCommands(context: Context): String {
        val pkg = context.packageName
        return buildString {
            append("adb shell pm grant $pkg android.permission.DUMP\n")
            append("adb shell appops set $pkg PROJECT_MEDIA allow\n")
            append("adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow")
        }
    }

    fun checklist(context: Context): String = buildString {
        append("DUMP permission: ${yesNo(hasDump(context))}\n")
        append("PROJECT_MEDIA app-op: ${yesNo(hasProjectMediaAppOp(context))}\n")
        append("Microphone/capture permission: ${yesNo(hasRecordAudio(context))}\n")
        append("Notification permission: ${yesNo(hasNotifications(context))}")
    }

    private fun yesNo(value: Boolean) = if (value) "granted" else "missing"
}
