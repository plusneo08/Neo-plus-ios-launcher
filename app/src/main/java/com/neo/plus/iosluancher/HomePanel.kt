package com.neo.plus.iosluancher

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clean glass panel at the top of the first home page:
 * big time, the date, and live weather for where the phone is.
 */
@Composable
fun HomePanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val use24 = DateFormat.is24HourFormat(ctx)

    // Ticks exactly on the minute
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L + 30L)
        }
    }

    // Weather: cached value shows instantly, fresh data replaces it
    var weather by remember { mutableStateOf(WeatherRepo.cached(appCtx)) }
    var permitted by remember { mutableStateOf(WeatherRepo.hasPermission(appCtx)) }
    var tick by remember { mutableIntStateOf(0) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        if (ok) tick++
    }
    LaunchedEffect(tick, permitted) {
        while (permitted) {
            val c = weather
            val stale = c == null || System.currentTimeMillis() - c.time > 20 * 60_000L
            var failed = false
            if (stale) {
                val fresh = WeatherRepo.refresh(appCtx)
                if (fresh != null) weather = fresh else failed = true
            }
            delay(if (failed && weather == null) 45_000L else 10 * 60_000L)
        }
    }

    val date = Date(now)
    val loc = Locale.getDefault()
    val time = SimpleDateFormat(if (use24) "HH:mm" else "h:mm", loc).format(date)
    val ampm = if (use24) "" else SimpleDateFormat("a", loc).format(date).uppercase(loc)
    val weekday = SimpleDateFormat("EEEE", loc).format(date)
    val dayMonth = SimpleDateFormat("d MMMM", loc).format(date)

    Row(
        modifier
            .glass(RoundedCornerShape(30.dp), 0.20f)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- time + date
        Column(
            Modifier.weight(1.2f).fillMaxHeight().clickable {
                ctx.safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            },
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                weekday.uppercase(loc), color = Color.White.copy(alpha = 0.66f),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    time, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Thin,
                    letterSpacing = (-2).sp, style = ShadowStyle, maxLines = 1
                )
                if (ampm.isNotEmpty()) {
                    Text(
                        ampm, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp, bottom = 13.dp)
                    )
                }
            }
            Text(
                dayMonth, color = Color.White.copy(alpha = 0.92f), fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                        .appendPath(System.currentTimeMillis().toString()).build()
                    ctx.safeStart(Intent(Intent.ACTION_VIEW, uri))
                }
            )
        }

        Box(Modifier.padding(horizontal = 14.dp).width(0.8.dp).fillMaxHeight(0.62f).background(Color.White.copy(alpha = 0.22f)))

        // ---- weather
        val w = weather
        Column(
            Modifier.weight(0.95f).fillMaxHeight().clickable {
                when {
                    w != null -> ctx.safeStart(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("weather")))
                    )
                    !permitted -> askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    else -> tick++
                }
            },
            verticalArrangement = Arrangement.Center
        ) {
            if (w != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherGlyph(weatherKind(w.code, w.isDay), Modifier.size(38.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${w.temp}°", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light, style = ShadowStyle)
                }
                Text(
                    weatherText(w.code), color = Color.White.copy(alpha = 0.94f), fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "H:${w.hi}°  L:${w.lo}°", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (w.place.isNotBlank()) {
                    Text(
                        w.place, color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (!permitted) {
                Text("Weather", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tap to allow location", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            } else {
                Text("Weather", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Getting forecast…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}
