package com.neo.plus.ioslauncher

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Flashlight with adjustable brightness.
 * Android 13+ (API 33) lets an app turn the torch on at a chosen strength; the phone reports how many steps it has
 * (FLASH_INFO_STRENGTH_MAXIMUM_LEVEL). Many phones have only 1 step (on/off): [steps] then returns 1 and the
 * brightness buttons tell the user the phone cannot dim its flashlight.
 */
object Torch {
    private const val KEY = "torch_level"

    private fun cm(ctx: Context): CameraManager = ctx.applicationContext.getSystemService(CameraManager::class.java)

    fun id(ctx: Context): String? = try {
        val m = cm(ctx)
        m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) { null }

    /** Number of brightness steps this phone's torch has (1 = no adjustable brightness). */
    @SuppressLint("NewApi")
    fun steps(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 33) return 1
        return try {
            val m = cm(ctx)
            val id = id(ctx) ?: return 1
            (m.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1).coerceAtLeast(1)
        } catch (e: Exception) { 1 }
    }

    fun adjustable(ctx: Context) = steps(ctx) > 1

    /** The chosen level 1..steps (remembered). First time: the phone's own default strength. */
    @SuppressLint("NewApi")
    fun level(ctx: Context): Int {
        val max = steps(ctx)
        val sp = ctx.applicationContext.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)
        val saved = sp.getInt(KEY, 0)
        if (saved in 1..max) return saved
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val id = id(ctx)
                val d = if (id != null) cm(ctx).getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) else null
                if (d != null && d in 1..max) return d
            } catch (e: Exception) { }
        }
        return max
    }

    /** 1..100 for display. */
    fun percent(ctx: Context, level: Int = level(ctx)): Int = (level * 100f / steps(ctx)).let { Math.round(it) }.coerceIn(1, 100)

    /** How many steps one tap of + / - moves (about 10 % of the range, at least 1). */
    fun stepSize(ctx: Context): Int = Math.ceil(steps(ctx) / 10.0).toInt().coerceAtLeast(1)

    /** Turns the torch on at [level] (or the remembered one); on phones without brightness steps it is a plain on. */
    @SuppressLint("NewApi")
    fun on(ctx: Context, level: Int = level(ctx)): Boolean {
        val id = id(ctx) ?: return false
        return try {
            val max = steps(ctx)
            val l = level.coerceIn(1, max)
            if (Build.VERSION.SDK_INT >= 33 && max > 1) cm(ctx).turnOnTorchWithStrengthLevel(id, l)
            else cm(ctx).setTorchMode(id, true)
            ctx.applicationContext.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE).edit().putInt(KEY, l).apply()
            true
        } catch (e: Exception) { false }
    }

    fun off(ctx: Context) {
        try { id(ctx)?.let { cm(ctx).setTorchMode(it, false) } } catch (e: Exception) { }
    }

    /** Voice / text command: percent 1..100. */
    fun onPercent(ctx: Context, percent: Int): Boolean =
        on(ctx, Math.round(steps(ctx) * percent.coerceIn(1, 100) / 100f).coerceIn(1, steps(ctx)))
}
