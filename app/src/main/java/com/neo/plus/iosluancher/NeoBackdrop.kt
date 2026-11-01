package com.neo.plus.iosluancher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The default wallpaper of the Phone and Messages apps: the black-and-gold Neo Plus artwork, filling the WHOLE screen and shown
 * in full (not cropped or zoomed; see [neoFillBands]), with a dark veil (stronger towards the bottom) so the lists, the keypad
 * and the chat bubbles stay readable.
 * Value "" (and "-" in Messages) = this wallpaper, "n:" = plain theme colours.
 */
@Composable
fun NeoLogoBackdrop(accent: Color = Color(0xFF38BDF8)) {
    val art = ImageBitmap.imageResource(R.drawable.neo_wall_gold)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width.toInt()
            val h = size.height.toInt()
            val bands = neoFillBands(art.width, art.height, w, h)
            if (bands == null) {
                drawImage(art, dstOffset = IntOffset.Zero, dstSize = IntSize(w, h), filterQuality = FilterQuality.Medium)
            } else {
                for (r in bands) {
                    drawImage(
                        art,
                        srcOffset = IntOffset(0, r[0]), srcSize = IntSize(art.width, r[1]),
                        dstOffset = IntOffset(0, r[2]), dstSize = IntSize(w, r[3]),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.62f)))))
    }
}
