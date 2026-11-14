package com.ATVBasic.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Asking Android to make this app the default home screen (Android cannot do it silently). */
internal object HomeRole {

    @Suppress("DEPRECATION")
    fun isDefault(c: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = if (Build.VERSION.SDK_INT >= 33) {
            c.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            c.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return ri?.activityInfo?.packageName == c.packageName
    }

    /** Opens the system's own "make default launcher" screen, trying the best option first. */
    fun request(a: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = a.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (!rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                        a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
                    }
                    return
                }
            }
        } catch (ex: RuntimeException) {
            // fall through to the older routes
        }
        try {
            a.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (ex: RuntimeException) {
            try { // last resort: the home chooser, which has an "Always" button
                a.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (ignored: RuntimeException) {
                // nothing more to try
            }
        }
    }
}
