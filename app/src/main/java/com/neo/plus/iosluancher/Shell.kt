package com.neo.plus.iosluancher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * One place for every privileged shell command the app runs.
 *
 * Order: Shizuku first (no root needed; the same power as `adb shell`), then root (`su`).
 * Shizuku has to be running (start it once after every reboot with Wireless debugging, from the Shizuku app)
 * and NEO PLUS needs its permission (see [ShizukuSetup]).
 */
object Shell {
    data class Result(val ok: Boolean, val out: String)

    /** Shizuku is running and this app has been allowed to use it. */
    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun run(cmd: String, timeoutMs: Long = 30_000): Result {
        if (shizukuReady()) {
            val r = exec(shizukuProcess(cmd), timeoutMs)
            if (r != null && r.ok) return r
            // A command the shell user may not run can still work with root, when the phone has it
            exec(suProcess(cmd), timeoutMs)?.let { if (it.ok) return it }
            return r ?: Result(false, "")
        }
        return exec(suProcess(cmd), timeoutMs) ?: Result(false, "")
    }

    fun ok(cmd: String, timeoutMs: Long = 30_000): Boolean = run(cmd, timeoutMs).ok

    /** Shizuku (adb power) or root is usable right now. */
    fun available(): Boolean =
        shizukuReady() || exec(suProcess("id"), 10_000)?.out?.contains("uid=0") == true

    /** Short name of the route that will be used, for messages. */
    fun label(): String = if (shizukuReady()) "Shizuku" else "root"

    private fun suProcess(cmd: String): () -> Process = { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)) }

    /**
     * Shizuku 13 made newProcess private (the official way is a UserService); calling it by reflection is the
     * usual workaround and keeps this app free of AIDL services.
     */
    private fun shizukuProcess(cmd: String): () -> Process = {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    /** Runs the process, reads its output off-thread and gives up after [timeoutMs]. Null when it could not even start. */
    private fun exec(start: () -> Process, timeoutMs: Long): Result? {
        val p = try { start() } catch (e: Throwable) { return null }
        return try {
            val out = StringBuilder()
            val reader = Thread { try { out.append(p.inputStream.bufferedReader().readText()) } catch (e: Throwable) { } }
            val err = Thread { try { p.errorStream.readBytes() } catch (e: Throwable) { } }
            reader.start(); err.start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) { try { p.destroy() } catch (e: Throwable) { }; return Result(false, "") }
            reader.join(1000)
            Result(p.exitValue() == 0, out.toString().trim())
        } catch (e: Throwable) {
            try { p.destroy() } catch (x: Throwable) { }
            Result(false, "")
        }
    }
}

/**
 * Shizuku hookup: asks for its permission and finishes the automatic setup as soon as Shizuku is allowed.
 * Shizuku itself stops when the phone restarts; start it again with Wireless debugging in the Shizuku app.
 */
object ShizukuSetup {
    private const val REQ = 4711
    private const val PKG = "moe.shizuku.privileged.api"
    private var started = false
    private var asked = false

    fun installed(ctx: Context): Boolean = attempt { ctx.packageManager.getPackageInfo(PKG, 0); true }

    fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    /** Asks Shizuku for permission. False when Shizuku is not running or the question can't be asked any more. */
    fun requestPermission(): Boolean = try {
        when {
            !Shizuku.pingBinder() -> false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
            Shizuku.shouldShowRequestPermissionRationale() -> false // denied for good: allow it inside the Shizuku app
            else -> { Shizuku.requestPermission(REQ); true }
        }
    } catch (e: Throwable) { false }

    fun openShizukuApp(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return
        ctx.safeStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Call once at start-up. Does the full setup (once) when Shizuku is running and allowed. */
    fun start(app: Context) {
        if (started) return
        started = true
        val prefs = app.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)
        fun finish() {
            if (prefs.getBoolean("root_setup_v1", false)) return
            Thread {
                if (RootSetup.applyAll(app)) {
                    prefs.edit().putBoolean("root_setup_v1", true).apply()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(app, "NEO PLUS set up through Shizuku", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
        try {
            Shizuku.addBinderReceivedListenerSticky {
                if (Shell.shizukuReady()) finish()
                else if (!asked) { asked = true; requestPermission() }
            }
            Shizuku.addRequestPermissionResultListener { code, result ->
                if (code == REQ && result == PackageManager.PERMISSION_GRANTED) finish()
            }
        } catch (e: Throwable) { }
    }
}
