package com.neo.plus.ioslauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Touch gestures the plain tap / long-press actions do not cover: double tap, drag, swipe, pinch in / out.
 * Where: a "label" (found by its words in the accessibility tree) or x / y as fractions of the screen (0.0 to 1.0) or pixels.
 * Needs the accessibility service to have "Perform gestures" allowed (android:canPerformGestures="true" in its config).
 */
object Gestures {
    private val gh: Handler by lazy { val t = HandlerThread("neo-gesture"); t.start(); Handler(t.looper) }

    private fun center(s: AccessibilityService, label: String): Pair<Float, Float>? {
        val root = try { s.rootInActiveWindow } catch (e: Throwable) { null } ?: return null
        val nodes = try { root.findAccessibilityNodeInfosByText(label) } catch (e: Throwable) { null } ?: return null
        for (n in nodes) {
            if (n.isPassword || !n.isVisibleToUser) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) return Pair(r.exactCenterX(), r.exactCenterY())
        }
        return null
    }

    private fun dot(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    private fun dispatch(s: AccessibilityService, g: GestureDescription): Boolean {
        var done = false
        val latch = CountDownLatch(1)
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }
        val posted = try { s.dispatchGesture(g, cb, gh) } catch (e: Throwable) { false }
        if (!posted) return false
        try { latch.await(5, TimeUnit.SECONDS) } catch (e: InterruptedException) { return false }
        return done
    }

    fun handle(c: Context, a: JSONObject): String {
        val s = IosControlService.instance ?: return "Turn on “NEO PLUS iOS Controls” in Accessibility so I can touch the screen"
        if (Build.VERSION.SDK_INT < 24) return "Gestures need Android 7 or newer"
        val dm = c.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        fun px(v: Double) = (if (v <= 1.0) v * w else v).toFloat().coerceIn(1f, w - 1f)
        fun py(v: Double) = (if (v <= 1.0) v * h else v).toFloat().coerceIn(1f, h - 1f)

        val type = a.optString("type").lowercase().trim().replace('-', '_').replace(' ', '_')
        val label = a.optString("label").trim()
        var x = px(a.optDouble("x", 0.5))
        var y = py(a.optDouble("y", 0.5))
        if (label.isNotEmpty()) {
            val p = center(s, label) ?: return "Couldn’t find “$label” on the screen"
            x = p.first; y = p.second
        }
        val d = dm.density

        val gd: GestureDescription = when (type) {
            "double_tap" -> GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(dot(x, y), 0L, 50L))
                .addStroke(GestureDescription.StrokeDescription(dot(x, y), 170L, 50L))
                .build()
            "drag", "swipe" -> {
                var x2 = if (a.has("x2")) px(a.optDouble("x2")) else x
                var y2 = if (a.has("y2")) py(a.optDouble("y2")) else y
                if (!a.has("x2") && !a.has("y2")) {
                    when (a.optString("direction", "up").lowercase()) {
                        "down" -> y2 = (y + h * 0.35f).coerceAtMost(h - 1f)
                        "left" -> x2 = (x - w * 0.35f).coerceAtLeast(1f)
                        "right" -> x2 = (x + w * 0.35f).coerceAtMost(w - 1f)
                        else -> y2 = (y - h * 0.35f).coerceAtLeast(1f)
                    }
                }
                val path = Path().apply { moveTo(x, y); lineTo(x2, y2) }
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, if (type == "drag") 700L else 280L))
                    .build()
            }
            "pinch_in", "pinch_out" -> {
                val near = 20f * d
                val far = 130f * d
                val s0 = if (type == "pinch_out") near else far
                val s1 = if (type == "pinch_out") far else near
                val p1 = Path().apply { moveTo((x - s0).coerceAtLeast(1f), y); lineTo((x - s1).coerceAtLeast(1f), y) }
                val p2 = Path().apply { moveTo((x + s0).coerceAtMost(w - 1f), y); lineTo((x + s1).coerceAtMost(w - 1f), y) }
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(p1, 0L, 450L))
                    .addStroke(GestureDescription.StrokeDescription(p2, 0L, 450L))
                    .build()
            }
            else -> return "Unknown gesture “$type”. Use double_tap, drag, swipe, pinch_in or pinch_out"
        }

        val ok = dispatch(s, gd)
        val what = type.replace('_', ' ')
        return if (ok) "Did a $what" + (if (label.isNotEmpty()) " on “$label”" else "")
        else "Couldn’t do the $what. The screen refused it, or “Perform gestures” is not allowed for the accessibility control."
    }
}
