package com.neo.plus.iosluancher

/**
 * Kept only so older call sites still compile. NEO PLUS no longer uses Shizuku or root: this never runs any command.
 * Everything the app needs comes from normal Android permissions, the settings pages and the system "default Home app" prompt.
 */
object Shell {
    data class Result(val ok: Boolean, val out: String)

    fun run(cmd: String, timeoutMs: Long = 30_000): Result = Result(false, "")
    fun ok(cmd: String, timeoutMs: Long = 30_000): Boolean = false
    fun available(): Boolean = false
    fun label(): String = "settings"
}
