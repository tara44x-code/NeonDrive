package com.neondrive.emu.ui

import android.os.Build
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.neondrive.emu.R

/**
 * Animated neon signature "مهند سات لنك" with a transparent background.
 * Android 9+: animated WebP with real (soft) transparency. Older: GIF fallback.
 */
@Composable
fun DeveloperBadge(modifier: Modifier = Modifier, height: Dp = 40.dp) {
    val context = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
    }
    AsyncImage(
        model = if (Build.VERSION.SDK_INT >= 28) R.raw.dev_name_anim else R.raw.dev_name_legacy,
        contentDescription = "تطوير: مهند سات لنك",
        imageLoader = loader,
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height),
    )
}
