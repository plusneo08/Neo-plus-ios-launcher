package com.neo.plus.iosluancher

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/* ------------------------------------------------------------------ */
/*  Data                                                               */
/* ------------------------------------------------------------------ */

data class WeatherData(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val hi: Int,
    val lo: Int,
    val place: String,
    val fahrenheit: Boolean,
    val time: Long
)

enum class WKind { CLEAR_DAY, CLEAR_NIGHT, PARTLY_DAY, PARTLY_NIGHT, CLOUDY, FOG, RAIN, SNOW, STORM }

/** WMO weather codes as returned by Open-Meteo. */
fun weatherKind(code: Int, isDay: Boolean): WKind = when (code) {
    0, 1 -> if (isDay) WKind.CLEAR_DAY else WKind.CLEAR_NIGHT
    2 -> if (isDay) WKind.PARTLY_DAY else WKind.PARTLY_NIGHT
    3 -> WKind.CLOUDY
    45, 48 -> WKind.FOG
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WKind.RAIN
    71, 73, 75, 77, 85, 86 -> WKind.SNOW
    95, 96, 99 -> WKind.STORM
    else -> WKind.CLOUDY
}

fun weatherText(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Foggy"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71 -> "Light snow"
    73 -> "Snow"
    75 -> "Heavy snow"
    77 -> "Snow grains"
    80, 81 -> "Showers"
    82 -> "Heavy showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm, hail"
    else -> "Cloudy"
}

/* ------------------------------------------------------------------ */
/*  Repository: location -> Open-Meteo -> cache                        */
/* ------------------------------------------------------------------ */

object WeatherRepo {
    private const val KEY = "weather_cache"
    private const val LAT = "weather_lat"
    private const val LON = "weather_lon"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("neo_launcher", Context.MODE_PRIVATE)

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun cached(ctx: Context): WeatherData? = try {
        val j = JSONObject(prefs(ctx).getString(KEY, null) ?: return null)
        WeatherData(
            j.getInt("t"), j.getInt("c"), j.getBoolean("d"), j.getInt("h"), j.getInt("l"),
            j.optString("p", ""), j.optBoolean("f", false), j.getLong("ts")
        )
    } catch (e: Exception) {
        null
    }

    private fun save(ctx: Context, w: WeatherData) {
        val j = JSONObject()
            .put("t", w.temp).put("c", w.code).put("d", w.isDay).put("h", w.hi).put("l", w.lo)
            .put("p", w.place).put("f", w.fahrenheit).put("ts", w.time)
        prefs(ctx).edit().putString(KEY, j.toString()).apply()
    }

    private fun usesFahrenheit(): Boolean =
        Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")

    /** Downloads fresh weather for the phone's position. Returns null when offline or without location. */
    suspend fun refresh(ctx: Context): WeatherData? = withContext(Dispatchers.IO) {
        try {
            val p = prefs(ctx)
            val loc = locate(ctx)
            val lat: Double
            val lon: Double
            if (loc != null) {
                lat = loc.latitude
                lon = loc.longitude
                p.edit().putString(LAT, lat.toString()).putString(LON, lon.toString()).apply()
            } else {
                lat = p.getString(LAT, null)?.toDoubleOrNull() ?: return@withContext null
                lon = p.getString(LON, null)?.toDoubleOrNull() ?: return@withContext null
            }
            val f = usesFahrenheit()
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto" +
                if (f) "&temperature_unit=fahrenheit" else ""
            val json = JSONObject(httpGet(url))
            val cur = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val w = WeatherData(
                temp = cur.getDouble("temperature_2m").roundToInt(),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                hi = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                lo = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                place = placeName(ctx, lat, lon) ?: cached(ctx)?.place ?: "",
                fahrenheit = f,
                time = System.currentTimeMillis()
            )
            save(ctx, w)
            w
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun placeName(ctx: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else {
            val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }
    } catch (e: Exception) {
        null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private suspend fun locate(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { prov -> try { lm.getLastKnownLocation(prov) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 3 * 3_600_000L) return last
        val provider = when {
            try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false } -> LocationManager.NETWORK_PROVIDER
            try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false } -> LocationManager.GPS_PROVIDER
            else -> return last
        }
        val fresh = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { if (cont.isActive) cont.resume(l) }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                try {
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { try { lm.removeUpdates(listener) } catch (e: Exception) { } }
            }
        }
        return fresh ?: last
    }
}

/* ------------------------------------------------------------------ */
/*  Hand-drawn weather glyphs (no image assets)                        */
/* ------------------------------------------------------------------ */

private val CloudLight = Color(0xFFF4F6FA)
private val CloudDark = Color(0xFFB4BAC6)
private val SunTop = Color(0xFFFFE27A)
private val SunBottom = Color(0xFFFFB300)

private fun DrawScope.sun(c: Offset, r: Float, spin: Float = 0f, glow: Float = 1f) {
    drawCircle(SunTop.copy(alpha = 0.22f * glow), r * 2.1f, c)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0 + spin)
        val p1 = Offset(c.x + (cos(a) * r * 1.45f).toFloat(), c.y + (sin(a) * r * 1.45f).toFloat())
        val p2 = Offset(c.x + (cos(a) * r * 1.92f).toFloat(), c.y + (sin(a) * r * 1.92f).toFloat())
        drawLine(SunBottom, p1, p2, strokeWidth = r * 0.28f, cap = StrokeCap.Round)
    }
    drawCircle(Brush.verticalGradient(listOf(SunTop, SunBottom), startY = c.y - r, endY = c.y + r), r, c)
}

private fun DrawScope.moon(c: Offset, r: Float) {
    val disc = Path().apply { addOval(Rect(Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f))) }
    val bite = Path().apply {
        val bc = Offset(c.x + r * 0.50f, c.y - r * 0.32f)
        addOval(Rect(Offset(bc.x - r * 0.92f, bc.y - r * 0.92f), Size(r * 1.84f, r * 1.84f)))
    }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), Color(0xFFF2F2F7))
}

private fun DrawScope.cloud(x: Float, y: Float, w: Float, color: Color) {
    val h = w * 0.55f
    drawRoundRect(color, Offset(x, y + h * 0.45f), Size(w, h * 0.55f), CornerRadius(h * 0.28f))
    drawCircle(color, h * 0.34f, Offset(x + w * 0.30f, y + h * 0.52f))
    drawCircle(color, h * 0.46f, Offset(x + w * 0.56f, y + h * 0.42f))
    drawCircle(color, h * 0.28f, Offset(x + w * 0.78f, y + h * 0.62f))
}

@Composable
fun WeatherGlyph(kind: WKind, modifier: Modifier = Modifier) {
    val t = rememberFrameSeconds()
    Canvas(modifier) {
        val w = size.width
        val time = t.value
        val bob = (sin(time * 1.6f) * w * 0.012f)
        val spin = time * 18f
        val glow = 0.8f + 0.2f * sin(time * 2f)
        when (kind) {
            WKind.CLEAR_DAY -> sun(Offset(w * 0.5f, w * 0.5f), w * 0.21f, spin, glow)
            WKind.CLEAR_NIGHT -> {
                moon(Offset(w * 0.5f, w * 0.5f), w * 0.30f)
                for (i in 0..2) {
                    val a = 0.4f + 0.6f * (0.5f + 0.5f * sin(time * 2.2f + i * 2f))
                    drawCircle(Color.White.copy(alpha = a), w * 0.018f, Offset(w * (0.18f + 0.62f * i / 2f), w * (0.18f + 0.1f * (i % 2))))
                }
            }
            WKind.PARTLY_DAY -> {
                sun(Offset(w * 0.36f, w * 0.36f), w * 0.15f, spin, glow)
                translate(left = sin(time * 0.9f) * w * 0.02f, top = bob) { cloud(w * 0.20f, w * 0.34f, w * 0.74f, CloudLight) }
            }
            WKind.PARTLY_NIGHT -> {
                moon(Offset(w * 0.37f, w * 0.36f), w * 0.21f)
                translate(left = sin(time * 0.9f) * w * 0.02f, top = bob) { cloud(w * 0.20f, w * 0.34f, w * 0.74f, CloudLight) }
            }
            WKind.CLOUDY -> translate(left = sin(time * 0.9f) * w * 0.025f, top = bob) { cloud(w * 0.08f, w * 0.26f, w * 0.84f, CloudLight) }
            WKind.FOG -> {
                listOf(0.34f to 0.70f, 0.50f to 0.56f, 0.66f to 0.70f).forEachIndexed { i, (y, len) ->
                    val x0 = (if (i == 1) w * 0.30f else w * 0.15f) + sin(time * 1.1f + i * 1.5f) * w * 0.05f
                    drawLine(Color.White.copy(alpha = 0.88f), Offset(x0, w * y), Offset(x0 + w * len, w * y), strokeWidth = w * 0.075f, cap = StrokeCap.Round)
                }
            }
            WKind.RAIN -> {
                translate(top = bob) { cloud(w * 0.10f, w * 0.14f, w * 0.80f, CloudLight) }
                val drop = Color(0xFF63B3FF)
                for (i in 0..2) {
                    val f = (time * 1.1f + i * 0.33f) % 1f
                    val x = w * (0.33f + 0.17f * i)
                    val y = w * (0.64f + 0.20f * f)
                    drawLine(drop.copy(alpha = 1f - f * 0.8f), Offset(x - w * 0.05f * f, y), Offset(x - w * 0.05f * f - w * 0.04f, y + w * 0.13f), strokeWidth = w * 0.065f, cap = StrokeCap.Round)
                }
            }
            WKind.SNOW -> {
                translate(top = bob) { cloud(w * 0.10f, w * 0.14f, w * 0.80f, CloudLight) }
                for (i in 0..2) {
                    val f = (time * 0.5f + i * 0.33f) % 1f
                    val x = w * (0.32f + 0.18f * i) + sin(time * 2f + i) * w * 0.02f
                    drawCircle(Color.White.copy(alpha = 1f - f * 0.7f), w * 0.045f, Offset(x, w * (0.68f + 0.24f * f)))
                }
            }
            WKind.STORM -> {
                translate(top = bob) { cloud(w * 0.10f, w * 0.12f, w * 0.80f, CloudDark) }
                val c = time % 3f
                val on = if (c < 0.12f || c in 0.26f..0.36f) 1f else 0.55f
                val bolt = Path().apply {
                    moveTo(w * 0.54f, w * 0.56f); lineTo(w * 0.40f, w * 0.78f); lineTo(w * 0.51f, w * 0.78f)
                    lineTo(w * 0.45f, w * 0.96f); lineTo(w * 0.67f, w * 0.69f); lineTo(w * 0.56f, w * 0.69f)
                    lineTo(w * 0.62f, w * 0.56f); close()
                }
                drawPath(bolt, Color(0xFFFFD60A).copy(alpha = on))
            }
        }
    }
}
