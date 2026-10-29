package com.neo.plus.iosluancher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * The default wallpaper of the Phone and Messages apps: the black-and-gold Neo Plus artwork, filling the screen,
 * with a dark veil (stronger towards the bottom) so the lists, the keypad and the chat bubbles stay readable.
 * Value "" (and "-" in Messages) = this wallpaper, "n:" = plain theme colours.
 */
@Composable
fun NeoLogoBackdrop(accent: Color = Color(0xFF38BDF8)) {
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.neo_wall_gold), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.62f)))))
    }
}
