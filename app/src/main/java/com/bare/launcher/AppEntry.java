package com.bare.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** One launchable app: one entry per package, sorted by label. */
final class AppEntry {

    final String pkg;
    final String label;
    final boolean system;
    final ActivityInfo info;
    final ComponentName component;

    private AppEntry(ActivityInfo info, String label) {
        this.info = info;
        this.pkg = info.packageName;
        this.label = label;
        this.component = new ComponentName(info.packageName, info.name);
        int f = info.applicationInfo.flags;
        this.system = (f & ApplicationInfo.FLAG_SYSTEM) != 0
                && (f & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    Intent launchIntent() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /** Blocking; call from a background thread. TV (leanback) activities win over phone ones. */
    static List<AppEntry> query(PackageManager pm, String self) {
        HashMap<String, AppEntry> byPkg = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, self, byPkg);
        collect(pm, Intent.CATEGORY_LAUNCHER, self, byPkg);
        ArrayList<AppEntry> list = new ArrayList<>(byPkg.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    private static void collect(PackageManager pm, String category, String self,
                                HashMap<String, AppEntry> out) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.applicationInfo == null
                    || ai.packageName.equals(self) || out.containsKey(ai.packageName)) {
                continue;
            }
            out.put(ai.packageName, new AppEntry(ai, String.valueOf(ri.loadLabel(pm))));
        }
    }
}
