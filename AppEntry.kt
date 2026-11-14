package com.ATVBasic.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import java.text.Collator

/** One launchable app: one entry per package, sorted by label. */
internal class AppEntry private constructor(
    val info: ActivityInfo,
    val label: String,
    private val category: String,
) {
    val pkg: String = info.packageName
    private val component = ComponentName(info.packageName, info.name)
    val system: Boolean = run {
        val flags = info.applicationInfo?.flags ?: 0
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    }

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(category)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    companion object {
        /** Blocking; call from a background thread. TV (leanback) activities win over phone ones. */
        fun query(pm: PackageManager, self: String): List<AppEntry> {
            val byPkg = HashMap<String, AppEntry>()
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, self, byPkg)
            collect(pm, Intent.CATEGORY_LAUNCHER, self, byPkg)
            // Locale-aware order, so Turkish letters (ç, ğ, ı, ö, ş, ü) sort where users expect them.
            val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
            return byPkg.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
        }

        private fun collect(
            pm: PackageManager,
            category: String,
            self: String,
            out: HashMap<String, AppEntry>,
        ) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in resolve(pm, query)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == self || out.containsKey(ai.packageName)) continue
                out[ai.packageName] = AppEntry(ai, ri.loadLabel(pm).toString(), category)
            }
        }

        @Suppress("DEPRECATION")
        private fun resolve(pm: PackageManager, intent: Intent): List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                pm.queryIntentActivities(intent, 0)
            }
    }
}
