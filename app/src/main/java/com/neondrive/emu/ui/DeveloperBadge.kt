package com.neondrive.emu.ui

import android.os.Build
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.neondrive.emu.R

/**
 * Animated neon signature "مهند سات لنك" with a transparent background.
 * Android 9+: animated WebP with real (soft) transparency. Older: GIF fallback.
 */
@Composable
fun DeveloperBadge(modifier: Modifier = Modifier, height: Dp = 40.dp) {
    AsyncImage(
        model = if (Build.VERSION.SDK_INT >= 28) R.raw.dev_name_anim else R.raw.dev_name_legacy,
        contentDescription = "تطوير: مهند سات لنك",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height),
    )
}
