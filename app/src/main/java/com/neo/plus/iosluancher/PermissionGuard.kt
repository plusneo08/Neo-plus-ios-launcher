package com.neo.plus.iosluancher

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps every permission the assistant needs switched on.
 *
 * Android (and phone makers' battery managers) can switch permissions off again later: "remove permissions if the app isn't
 * used", a restart of the accessibility service, a settings clean-up. The app used to ask only once, so a permission that
 * went off stayed off. This guard:
 *  - checks everything every 30 minutes and every time the launcher is opened,
 *  - repairs what the app is allowed to repair by itself (device owner: grants and locks them; the one-time
 *    WRITE_SECURE_SETTINGS grant: switches the controls service back on),
 *  - asks again for the rest (the permission pop-up, or the right Settings page, at most once every 30 minutes),
 *  - and shows one notification naming exactly what is off.
 *
 * Only a device owner (one adb command, see the README) can make Android refuse to let permissions be switched off.
 */
object PermissionGuard {
    private const val PREF = "neo_launcher"
    private const val CH = "neo_guard"
    private const val NOTIF_ID = 7731
    private const val EVERY_MS = 30 * 60_000L
    private const val OPEN_EVERY_MS = 30 * 60_000L

    /** The runtime permissions the assistant uses, for the Android version this phone runs. */
    fun runtimeList(): List<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALENDAR, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
            list += Manifest.permission.READ_MEDIA_AUDIO
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
        } else list += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT <= 28) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        return list
    }

    fun missingRuntime(ctx: Context): List<String> =
        runtimeList().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** One special switch that is off, and the Settings page that turns it on. */
    class Gap(val label: String, val fix: Intent)

    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    fun gaps(ctx: Context): List<Gap> {
        val app = ctx.applicationContext
        val out = ArrayList<Gap>()
        fun add(label: String, i: Intent) { out.add(Gap(label, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) }
        try {
            if (!EnableAssistant.accessibilityOk(app)) add("Accessibility (NEO PLUS iOS Controls)", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else if (IosControlService.instance == null) add("The controls service is not running (the phone’s battery manager stopped it)", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(app)))
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) add("Display over other apps", Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(app)))
            if (!NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) add("Notification access", Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(app)) add("Modify system settings", Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(app)))
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.isNotificationPolicyAccessGranted) add("Do Not Disturb access", Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(app.packageName)) add("Unrestricted battery use", Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(app)))
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) add("All files access", Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(app)))
            if (Build.VERSION.SDK_INT >= 31) {
                val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (!am.canScheduleExactAlarms()) add("Alarms & reminders", Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(app)))
            }
            if (Build.VERSION.SDK_INT >= 34 && nm != null && !nm.canUseFullScreenIntent()) add("Full-screen notifications", Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri(app)))
            if (!DeviceAdmin.isActive(app)) add("Device administrator", DeviceAdmin.requestIntent(app))
            // "Remove permissions if app isn't used" must be OFF for this app
            if (Build.VERSION.SDK_INT >= 30 && !app.packageManager.isAutoRevokeWhitelisted) add("Keep permissions when the app isn’t used", Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, pkgUri(app)))
        } catch (e: Exception) { }
        return out
    }

    /** What the app can put right without the user: device owner grants (and locks) everything, WRITE_SECURE_SETTINGS re-enables the controls service. */
    fun repairSilently(ctx: Context) {
        val app = ctx.applicationContext
        try { if (Kiosk.isOwner(app)) Kiosk.configure(app) } catch (e: Exception) { }
        try {
            if (!EnableAssistant.accessibilityOk(app) && SecureAccess.ensure(app)) {
                val me = "${app.packageName}/${IosControlService::class.java.name}"
                val old = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                if (!old.contains(me)) {
                    Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (old.isBlank()) me else "$old:$me")
                    Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
            }
        } catch (e: Exception) { }
    }

    /** The alarm tick: repair, then tell the user once if something is still off. */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        repairSilently(app)
        val names = missingRuntime(app).map { it.substringAfterLast('.').lowercase().replace('_', ' ') } + gaps(app).map { it.label }
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (names.isEmpty()) { nm.cancel(NOTIF_ID); return }
        try {
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Permissions", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                app, 5, Intent(app, MainActivity::class.java).putExtra("guard_fix", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = "Switched off: " + names.take(5).joinToString(", ") + (if (names.size > 5) " and ${names.size - 5} more" else "") + ". Tap to turn it back on."
            nm.notify(NOTIF_ID, NotificationCompat.Builder(app, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Neo Plus needs a permission again")
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }

    /**
     * Called whenever the launcher comes to the front. Opens the next thing that needs the user, but no more than one Settings
     * page every 30 minutes (or at once when they tapped the notification), so it never turns into a loop of Settings pages.
     */
    fun nextFix(ctx: Context, force: Boolean): Intent? {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("guard_last_open", 0L) < OPEN_EVERY_MS) return null
        val g = gaps(ctx).firstOrNull() ?: return null
        prefs.edit().putLong("guard_last_open", now).apply()
        return g.fix
    }

    fun arm(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(app, 7732, Intent(app, GuardReceiver::class.java).setAction("neo.guard.tick"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = System.currentTimeMillis() + EVERY_MS
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** Fires every 30 minutes (and is set again after a restart) to re-check the permissions. */
class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try { PermissionGuard.tick(app) } catch (e: Exception) { }
            try { PermissionGuard.arm(app) } catch (e: Exception) { }
            try { pending.finish() } catch (e: Exception) { }
        }.start()
    }
}
