package com.neo.plus.iosluancher

import android.content.Context

/**
 * Rooted phones: does everything the grant-all.sh script does, from inside the app, with one su session per step.
 * Grants every permission, switches on the accessibility service, notification access, device admin,
 * makes NEO PLUS the default home app, switches to gesture navigation and tries to become device owner
 * (full-phone launcher mode; that last step only succeeds while no accounts are signed in).
 */
object RootSetup {
    private fun run(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.trim()
    } catch (e: Throwable) { "" }

    fun available(): Boolean = run("id").contains("uid=0")

    /** Returns false when the phone is not rooted (or root was denied). */
    fun applyAll(ctx: Context): Boolean {
        if (!available()) return false
        val p = ctx.packageName
        listOf(
            "CAMERA", "READ_CONTACTS", "CALL_PHONE", "SEND_SMS", "READ_SMS", "READ_CALL_LOG", "READ_PHONE_STATE", "READ_CALENDAR", "RECORD_AUDIO",
            "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "POST_NOTIFICATIONS", "READ_MEDIA_AUDIO",
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "BLUETOOTH_CONNECT",
            "WRITE_SECURE_SETTINGS"
        ).forEach { run("pm grant $p android.permission.$it") }
        listOf("MANAGE_EXTERNAL_STORAGE", "WRITE_SETTINGS", "SYSTEM_ALERT_WINDOW", "ACCESS_RESTRICTED_SETTINGS")
            .forEach { run("appops set $p $it allow") }
        run("cmd notification allow_listener $p/${NeoNotificationService::class.java.name}")
        run("cmd notification allow_dnd $p")
        run("dpm set-active-admin $p/${NeoDeviceAdminReceiver::class.java.name}")

        val svc = "$p/${IosControlService::class.java.name}"
        val cur = run("settings get secure enabled_accessibility_services")
        if (!cur.contains(svc)) {
            val new = if (cur.isBlank() || cur == "null") svc else "$cur:$svc"
            run("settings put secure enabled_accessibility_services '$new'")
        }
        run("settings put secure accessibility_enabled 1")

        run("cmd role add-role-holder android.app.role.HOME $p")
        run("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
        // Android 10 and older: tell the system to keep its bars hidden in every app
        if (android.os.Build.VERSION.SDK_INT <= 29) run("settings put global policy_control immersive.full=*")

        Kiosk.becomeOwnerViaRoot(ctx)
        Kiosk.configure(ctx)
        return true
    }
}
