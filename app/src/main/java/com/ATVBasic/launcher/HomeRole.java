package com.ATVBasic.launcher;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/** Asking Android to make this app the default home screen (Android cannot do it silently). */
final class HomeRole {

    private HomeRole() {}

    static boolean isDefault(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null
                && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Opens the system's own "make default launcher" screen, trying the best option first. */
    static void request(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = a.getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (!rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                        a.startActivityForResult(
                                rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1);
                    }
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the older routes
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (RuntimeException e) {
            try { // last resort: the home chooser, which has an "Always" button
                a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // nothing more to try
            }
        }
    }
}
