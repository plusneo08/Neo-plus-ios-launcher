package com.neo.plus.iosluancher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Today's footsteps, from the phone's own step counter (a low-power chip that counts even while the screen is off).
 *
 * The chip only gives a running total since the phone started, so today's number is kept here: every reading adds the
 * difference from the previous one. At local midnight the number goes back to 0 (also when nobody is looking: the clock
 * is checked on every reading and by the screens that show it). A phone restart resets the chip; that is detected (the
 * total goes DOWN) and counting simply carries on.
 */
object StepCounter {
    private const val PREF = "neo_steps"

    /** Steps counted today. Compose reads this, so every screen showing it updates by itself. */
    val steps = mutableIntStateOf(0)

    /** True once the phone's step counter is really delivering (no chip, or no permission: stays false and nothing is shown). */
    val working = mutableStateOf(false)

    private var appCtx: Context? = null
    private var started = false
    private var live = false      // a reading has arrived in THIS run of the app
    private var day = ""
    private var total = 0L
    private var lastRaw = -1L

    private fun prefs() = appCtx!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** Milliseconds until the next local midnight. */
    fun msToMidnight(): Long {
        val c = Calendar.getInstance()
        val now = c.timeInMillis
        c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return (c.timeInMillis - now).coerceAtLeast(1_000L)
    }

    @Synchronized
    private fun load(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        val p = prefs()
        day = p.getString("day", "") ?: ""
        total = p.getLong("total", 0L)
        lastRaw = p.getLong("last", -1L)
        rollover()
    }

    private fun save() {
        prefs().edit().putString("day", day).putLong("total", total).putLong("last", lastRaw)
            .putInt("h_$day", total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())   // kept per day for the weekly bars
            .apply()
    }

    /** New day: start again from 0. */
    private fun rollover() {
        val t = dayKey()
        if (day != t) {
            day = t
            total = 0L
            // Alive all day: the next reading continues from the last one. Not alive (the app was closed over midnight): the steps taken
            // while nobody was reading cannot be told apart from yesterday's, so counting starts from the next reading.
            if (!live) lastRaw = -1L
            save()
        }
        steps.intValue = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Called by the screens that show the number (and every minute): makes sure a new day has started. */
    @Synchronized
    fun refresh(ctx: Context) {
        load(ctx)
        rollover()
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val raw = e.values.firstOrNull()?.toLong() ?: return
            onRaw(raw)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @Synchronized
    private fun onRaw(raw: Long) {
        live = true
        working.value = true
        rollover()
        val delta = when {
            lastRaw < 0L -> 0L        // first reading ever / after midnight without the app: nothing to compare with yet
            raw >= lastRaw -> raw - lastRaw
            else -> raw               // the phone restarted: the chip began again from 0
        }
        total += delta
        lastRaw = raw
        steps.intValue = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        save()
    }

    /** Steps of the last [days] days, oldest first; the last value is today. */
    @Synchronized
    fun history(ctx: Context, days: Int = 7): List<Int> {
        load(ctx)
        val p = prefs()
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        return (days - 1 downTo 0).map { i ->
            if (i == 0) steps.intValue
            else p.getInt("h_" + fmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }.time), 0)
        }
    }

    /** The daily target shown on the home screen (tap the card to change it). */
    fun goal(ctx: Context): Int = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("goal", 8000)
    fun setGoal(ctx: Context, g: Int) { ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("goal", g).apply() }

    /** Rough walking distance in km (average stride about 0.76 m) and energy in kcal (about 0.04 per step). */
    fun km(steps: Int): Double = steps * 0.00076
    fun kcal(steps: Int): Int = (steps * 0.04).toInt()

    /** Starts counting. Safe to call from any screen, any number of times. */
    @Synchronized
    fun start(ctx: Context) {
        load(ctx)
        if (started) return
        try {
            val sm = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            started = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: SecurityException) {
            started = false // "Physical activity" is not allowed for the app: nothing is shown until it is
        } catch (e: Exception) {
            started = false
        }
    }
}

/** "👣 3,482": today's steps. Shows nothing on a phone without a step counter. Goes back to 0 at midnight. */
@Composable
fun StepsPill(modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = 0.9f), size: TextUnit = 13.sp) {
    val ctx = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        StepCounter.start(ctx)
        while (true) {
            StepCounter.refresh(ctx)
            delay(minOf(30_000L, StepCounter.msToMidnight() + 300L))
        }
    }
    if (!StepCounter.working.value && StepCounter.steps.intValue == 0) {
        // no reading yet: show 0 only once the chip has delivered something, so unsupported phones stay clean
        return
    }
    androidx.compose.foundation.layout.Row(
        modifier.padding(top = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        FrameEmoji(WALK_FRAMES, 350L, size = size)   // 👣 walks, then jogs, as you take steps
        Text(
            NumberFormat.getIntegerInstance().format(StepCounter.steps.intValue),
            color = color, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false
        )
    }
}
