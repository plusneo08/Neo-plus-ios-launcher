package com.neo.plus.iosluancher

/**
 * PLACEHOLDER so the project builds. Assistant.kt calls QuickCmd.plan(...) but QuickCmd.kt was missing from the repo.
 * Returning null here means "not handled": the assistant then uses its normal matchers and the AI, exactly as before.
 * If you have your real QuickCmd.kt (the instant Wi-Fi / volume / brightness matcher), use it INSTEAD of this file
 * (same name, same folder: just overwrite this one).
 */
object QuickCmd {
    fun plan(text: String): LocalPlan? = null
}
