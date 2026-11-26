package dev.zophop.ui.workflows

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable? = null,
)

object AppListHelper {
    fun getInstalledApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val mainIntent =
            Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        val resolveInfos =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    pm.queryIntentActivities(mainIntent, 0)
                }
            } catch (_: Exception) {
                emptyList()
            }

        val selfPkg = context.packageName
        val apps =
            resolveInfos
                .map { info ->
                    InstalledAppInfo(
                        appName = info.loadLabel(pm).toString(),
                        packageName = info.activityInfo.packageName,
                        icon =
                            try {
                                info.loadIcon(pm)
                            } catch (_: Exception) {
                                null
                            },
                    )
                }.distinctBy { it.packageName }
                .filter { it.packageName != selfPkg && !it.packageName.startsWith("dev.zophop") }
                .sortedBy { it.appName }

        return listOf(InstalledAppInfo("All Apps (*)", ".*", null)) + apps
    }

    fun isAppInstalled(
        context: Context,
        packageName: String,
    ): Boolean {
        if (packageName.isBlank() || packageName == ".*" || packageName.contains("*")) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getAppNameForPackage(
        context: Context,
        packageName: String,
    ): String {
        if (packageName.isBlank() || packageName == ".*") return "All Apps"
        return try {
            val pm = context.packageManager
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    pm.getApplicationInfo(packageName, 0)
                }
            info.loadLabel(pm).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    fun getAppIconForPackage(
        context: Context,
        packageName: String,
    ): Drawable? {
        if (packageName.isBlank() || packageName == ".*" || packageName.contains("*")) return null
        return try {
            val pm = context.packageManager
            pm.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }
}
