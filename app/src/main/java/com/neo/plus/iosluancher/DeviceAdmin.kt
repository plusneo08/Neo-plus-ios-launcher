package com.neo.plus.iosluancher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Device-admin hook for Neo Plus Assistant. Gives the assistant a reliable "lock the screen" and lets it keep working while locked-down apps are open. */
class NeoDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, "Neo Plus Assistant is now a device admin", Toast.LENGTH_SHORT).show()
        }
        // If the phone was set up with this app as device owner, this also hands it the secure-settings permission.
        Thread { SecureAccess.ensure(context.applicationContext) }.start()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, "Neo Plus Assistant device admin turned off", Toast.LENGTH_SHORT).show()
        }
    }
}

object DeviceAdmin {
    fun component(ctx: Context) = ComponentName(ctx, NeoDeviceAdminReceiver::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isActive(ctx: Context): Boolean = attempt { dpm(ctx).isAdminActive(component(ctx)) }

    fun requestIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets Neo Plus Assistant lock your screen when you ask. It cannot erase data or change your password."
            )

    /** Locks the screen straight away. Needs the device admin to be active. */
    fun lockNow(ctx: Context): Boolean = attempt {
        if (!isActive(ctx)) false else { dpm(ctx).lockNow(); true }
    }
}

/**
 * WRITE_SECURE_SETTINGS is what lets Dark mode and Battery saver flip directly (no Settings screen).
 * Android never lets an app give this permission to itself, so [ensure] tries every on-device route:
 *  - already granted (adb grant, priv-app install) -> done
 *  - (removed: no shell is used)
 *  - device owner (`dpm set-device-owner`) -> the app grants the permission to itself
 */
object SecureAccess {
    const val PERM = "android.permission.WRITE_SECURE_SETTINGS"

    fun has(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun adbCommand(ctx: Context) = "pm grant ${ctx.packageName} $PERM"

    fun ensure(ctx: Context): Boolean {
        if (has(ctx)) return true
        attempt { Shell.ok("pm grant ${ctx.packageName} $PERM") }
        if (has(ctx)) return true
        attempt {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                dpm.setPermissionGrantState(
                    DeviceAdmin.component(ctx), ctx.packageName, PERM,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } else false
        }
        return has(ctx)
    }
}

/**
 * Whole-phone kiosk. Only possible when the app is the device owner (one adb command, see README).
 * Then Android's status bar and navigation bar are hidden in EVERY app opened from the launcher,
 * and every runtime permission is granted automatically, including for apps installed later.
 */
object Kiosk {
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)

    fun isOwner(ctx: Context): Boolean = attempt { dpm(ctx).isDeviceOwnerApp(ctx.packageName) }
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("kiosk_on", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("kiosk_on", on).apply()

    /** Allows every installed app inside the kiosk, hides the system bars, auto-grants permissions. */
    fun configure(ctx: Context) {
        if (!isOwner(ctx)) return
        attempt {
            val d = dpm(ctx)
            val admin = DeviceAdmin.component(ctx)
            val pkgs = ctx.packageManager.getInstalledApplications(0).map { it.packageName }.toTypedArray()
            d.setLockTaskPackages(admin, pkgs)
            if (android.os.Build.VERSION.SDK_INT >= 28 && !NeoLock.needsLock(ctx)) {
                d.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS)
            }
            d.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)
            if (android.os.Build.VERSION.SDK_INT >= 30) attempt { d.setUserControlDisabledPackages(admin, listOf(ctx.packageName)); true }
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.forEach { perm ->
                attempt { d.setPermissionGrantState(admin, ctx.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
            }
            true
        }
    }

    /** (Shell is a no-op now) used to become device owner from inside the app (fails harmlessly if accounts are signed in). */
    fun becomeOwnerViaRoot(ctx: Context): Boolean {
        if (isOwner(ctx)) return true
        attempt {
            val c = "dpm set-device-owner ${ctx.packageName}/${NeoDeviceAdminReceiver::class.java.name}"
            Shell.ok(c)
        }
        return isOwner(ctx)
    }

    /** Gives up device-owner status completely (so the app can be uninstalled or the phone returned to normal). */
    fun release(ctx: Context): Boolean = attempt {
        if (!isOwner(ctx)) false else { dpm(ctx).clearDeviceOwnerApp(ctx.packageName); true }
    }

    fun permitted(ctx: Context): Boolean = attempt { isOwner(ctx) && dpm(ctx).isLockTaskPermitted(ctx.packageName) }
}
