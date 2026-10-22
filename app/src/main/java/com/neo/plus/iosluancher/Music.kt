package com.neo.plus.iosluancher

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Lets the user pick which installed music app the Control Center plays through. */
object MusicPlayers {
    data class Player(val pkg: String, val label: String, val icon: ImageBitmap)

    private const val BROWSER_ACTION = "android.media.browse.MediaBrowserService"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)

    fun selected(ctx: Context): String? = prefs(ctx).getString("music_pkg", null)

    fun select(ctx: Context, pkg: String) {
        prefs(ctx).edit().putString("music_pkg", pkg).apply()
    }

    /** Every installed app that can act as a music player (media-browser apps + apps categorised as audio). */
    fun list(ctx: Context): List<Player> {
        val pm = ctx.packageManager
        val pkgs = linkedSetOf<String>()
        try {
            pm.queryIntentServices(Intent(BROWSER_ACTION), 0).forEach { pkgs += it.serviceInfo.packageName }
        } catch (e: Exception) { }
        try {
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(q, 0).forEach {
                if (it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_AUDIO) pkgs += it.activityInfo.packageName
            }
        } catch (e: Exception) { }
        pkgs -= ctx.packageName
        return pkgs.mapNotNull { pkg ->
            try {
                if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
                val info = pm.getApplicationInfo(pkg, 0)
                Player(pkg, pm.getApplicationLabel(info).toString(), renderIcon(pm.getApplicationIcon(info), 120).asImageBitmap())
            } catch (e: Exception) { null }
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Plays everything on the chosen player:
     * 1) its running media session, 2) connect to its MediaBrowserService and ask it to play,
     * 3) "play from search" intent / open the app.
     */
    fun play(ctx: Context, pkg: String, running: MediaController? = null) {
        val app = ctx.applicationContext
        if (running != null && running.packageName == pkg) {
            try { running.transportControls.play(); return } catch (e: Exception) { }
        }
        val comp = try {
            app.packageManager.queryIntentServices(Intent(BROWSER_ACTION).setPackage(pkg), 0)
                .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        } catch (e: Exception) { null }

        if (comp == null) { fallback(app, pkg); return }

        val main = Handler(Looper.getMainLooper())
        main.post {
            val holder = arrayOfNulls<MediaBrowser>(1)
            try {
                holder[0] = MediaBrowser(app, comp, object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            val c = MediaController(app, holder[0]!!.sessionToken)
                            val extras = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*") }
                            c.transportControls.playFromSearch("", extras)
                            main.postDelayed({
                                try {
                                    if (c.playbackState?.state != PlaybackState.STATE_PLAYING) c.transportControls.play()
                                } catch (e: Exception) { }
                            }, 800)
                        } catch (e: Exception) { fallback(app, pkg) }
                        main.postDelayed({ try { holder[0]?.disconnect() } catch (e: Exception) { } }, 3000)
                    }

                    override fun onConnectionFailed() { fallback(app, pkg) }
                }, null)
                holder[0]!!.connect()
            } catch (e: Exception) { fallback(app, pkg) }
        }
    }

    private fun fallback(ctx: Context, pkg: String) {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!ctx.safeStart(i)) {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.safeStart(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
