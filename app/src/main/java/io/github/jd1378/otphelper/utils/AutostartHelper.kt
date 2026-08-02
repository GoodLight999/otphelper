package io.github.jd1378.otphelper.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

class AutostartHelper {
  companion object {
    private val POWER_MANAGER_INTENTS =
        listOf(
            // HONOR MagicOS. Activity names vary by generation and region.
            Intent()
                .setComponent(
                    ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupAppControlActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.optimize.process.ProtectActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.optimize.bootstart.BootStartActivity")),
            // Huawei / older HONOR firmware.
            Intent()
                .setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupAppControlActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.letv.android.letvsafe",
                        "com.letv.android.letvsafe.AutobootManageActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent()
                .setComponent(
                    ComponentName(
                        "com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"))
                .setData(Uri.parse("mobilemanager://function/entry/AutoStart")))

    fun openAutostartSettings(context: Context) {
      for (template in POWER_MANAGER_INTENTS) {
        val intent = Intent(template).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ActivityHelper.isCallable(context, intent)) {
          try {
            context.startActivity(intent)
            return
          } catch (error: Throwable) {
            AppLogger.w(
                "AutostartHelper",
                "Unable to open ${intent.component?.flattenToShortString()}: ${error.javaClass.simpleName}",
            )
          }
        }
      }
      AppLogger.w("AutostartHelper", "No callable OEM app-launch settings activity was found")
    }

    fun hasAutostartSettings(context: Context): Boolean =
        POWER_MANAGER_INTENTS.any { ActivityHelper.isCallable(context, Intent(it)) }
  }
}
