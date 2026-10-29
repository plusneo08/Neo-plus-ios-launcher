package com.neo.plus.iosluancher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * The default wallpaper of the Phone and Messages apps: the Neo Plus logo, high on the screen with a soft glow,
 * feathered into the background (neo_icon.png is only 192 px, so its square edge is faded out and it is kept
 * fairly small), then a dark veil that gets stronger towards the bottom so the lists and the keypad stay readable.
 * Value "" (and "-" in Messages) = this wallpaper, "n:" = plain theme colours.
 */
@Composable
fun NeoLogoBackdrop(accent: Color = Color(0xFF38BDF8)) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val logoSize = maxWidth * 0.62f
        val centreY = maxHeight * 0.28f
        Box(Modifier.fillMaxSize().drawBehind {
            val c = Offset(size.width / 2f, size.height * 0.28f)
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.24f), Color.Transparent), center = c, radius = size.width * 0.95f))
        })
        Image(
            painterResource(R.drawable.neo_icon), null,
            Modifier.align(Alignment.TopCenter).offset(y = centreY - logoSize / 2f).size(logoSize)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = 0.60f }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.radialGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent, center = center, radius = size.minDimension / 2f),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentScale = ContentScale.Fit
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.50f)))))
    }
}
