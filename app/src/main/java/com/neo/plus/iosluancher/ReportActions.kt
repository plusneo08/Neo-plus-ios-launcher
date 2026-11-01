package com.neo.plus.iosluancher

import android.content.Context
import org.json.JSONObject

/**
 * PLACEHOLDER so the project builds. Assistant.kt calls ReportActions.compare(...) and ReportActions.wordReport(...)
 * but ReportActions.kt was missing from the repo. These just say the feature is not in this build.
 * If you have your real ReportActions.kt, use it INSTEAD of this file (same name, same folder: overwrite this one).
 */
object ReportActions {
    private const val MSG = "Comparing files and Word reports are not available in this build."
    fun compare(ctx: Context, a: JSONObject): ActionResult = ActionResult(MSG, false, false)
    fun wordReport(ctx: Context, a: JSONObject): ActionResult = ActionResult(MSG, false, false)
}
