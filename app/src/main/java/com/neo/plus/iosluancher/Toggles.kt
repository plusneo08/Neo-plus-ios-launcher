package com.neo.plus.iosluancher

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf

internal inline fun attempt(block: () -> Boolean): Boolean = try { block() } catch (e: Throwable) { false }

/**
 * Real on/off switches (no Settings screens). Each switch tries, in order:
 *  1. the direct Android API (works on older Android targets / system apps / device owners),
 *  2. hidden system APIs (work when the app is installed as a privileged system app, e.g. in a custom ROM),
 *  3. root (`su`) shell commands.
 * Only if all of them fail does the user get a short message.
 */
object Toggles {
    private const val MSG = "Android blocks this switch for normal apps. See README > Real toggles."

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Runs the switch off the main thread. If every method fails, the matching Settings page opens instead. */
    private fun background(ctx: Context, fallback: String? = null, action: () -> Boolean) {
        Thread {
            if (!attempt(action)) {
                toast(ctx, MSG)
                if (fallback != null) {
                    Handler(Looper.getMainLooper()).post {
                        ctx.safeStart(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }.start()
    }

    private fun su(cmd: String): Boolean = attempt {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor() == 0
    }

    private fun flag(on: Boolean, a: String, b: String) = if (on) a else b

    fun setWifi(ctx: Context, on: Boolean) = background(ctx) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ok = attempt { wm.setWifiEnabled(on) }
        ok || su("svc wifi ${flag(on, "enable", "disable")}")
    }

    @SuppressLint("MissingPermission")
    fun setBluetooth(ctx: Context, on: Boolean) = background(ctx) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        @Suppress("DEPRECATION")
        val ok = attempt {
            if (adapter == null) false else if (on) adapter.enable() else adapter.disable()
        }
        ok || su("svc bluetooth ${flag(on, "enable", "disable")}")
    }

    fun setAirplane(ctx: Context, on: Boolean) = background(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS) {
        val ok = attempt {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            ConnectivityManager::class.java.getMethod("setAirplaneMode", java.lang.Boolean.TYPE).invoke(cm, on)
            true
        }
        ok ||
            attempt {
                // needs WRITE_SECURE_SETTINGS (adb grant) on Android 9 and older
                Settings.Global.putInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (on) 1 else 0)
                try {
                    ctx.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on))
                    true
                } catch (e: Throwable) {
                    Settings.Global.putInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (on) 0 else 1)
                    false
                }
            } ||
            su("cmd connectivity airplane-mode ${flag(on, "enable", "disable")}") ||
            su("settings put global airplane_mode_on ${flag(on, "1", "0")}; am broadcast -a android.intent.action.AIRPLANE_MODE")
    }

    fun setMobileData(ctx: Context, on: Boolean) = background(ctx, Settings.ACTION_WIRELESS_SETTINGS) {
        val ok = attempt {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            TelephonyManager::class.java.getMethod("setDataEnabled", java.lang.Boolean.TYPE).invoke(tm, on)
            true
        }
        ok || su("svc data ${flag(on, "enable", "disable")}")
    }

    /** Set to true when a direct switch needs the one-time WRITE_SECURE_SETTINGS grant. The launcher shows a dialog. */
    val setupNeeded = mutableStateOf(false)

    /**
     * Runs a switch that must work without ever opening a Settings screen (Dark mode, Battery saver).
     * Gets WRITE_SECURE_SETTINGS if any on-device route allows it, then flips the switch.
     * If Android still refuses, a short setup dialog explains the one-time grant instead of opening Settings.
     */
    private fun direct(ctx: Context, action: () -> Boolean) {
        val app = ctx.applicationContext
        Thread {
            SecureAccess.ensure(app)
            if (!attempt(action)) {
                toast(app, "One-time permission needed")
                Handler(Looper.getMainLooper()).post { setupNeeded.value = true }
            }
        }.start()
    }

    fun setDarkMode(ctx: Context, on: Boolean) = direct(ctx) {
        val ok = attempt {
            if (Build.VERSION.SDK_INT >= 29) {
                // Called via reflection: the method is not in the public SDK stubs
                val uim = ctx.getSystemService(UiModeManager::class.java)
                val result = UiModeManager::class.java
                    .getMethod("setNightModeActivated", java.lang.Boolean.TYPE)
                    .invoke(uim, on)
                result as? Boolean ?: true
            } else false
        }
        ok ||
            attempt {
                // needs WRITE_SECURE_SETTINGS
                Settings.Secure.putInt(ctx.contentResolver, "ui_night_mode", if (on) 2 else 1)
                // nudge the system so the theme changes right away
                attempt {
                    val uim = ctx.getSystemService(UiModeManager::class.java)
                    uim.nightMode = if (on) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
                    true
                }
                true
            } ||
            su("cmd uimode night ${flag(on, "yes", "no")}")
    }

    fun setLowPower(ctx: Context, on: Boolean) = direct(ctx) {
        val ok = attempt {
            // works for privileged system apps
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            PowerManager::class.java.getMethod("setPowerSaveModeEnabled", java.lang.Boolean.TYPE).invoke(pm, on) as? Boolean ?: true
        }
        ok ||
            attempt {
                // needs WRITE_SECURE_SETTINGS
                Settings.Global.putInt(ctx.contentResolver, "low_power", if (on) 1 else 0)
                true
            } ||
            su("settings put global low_power ${flag(on, "1", "0")}")
    }
}
