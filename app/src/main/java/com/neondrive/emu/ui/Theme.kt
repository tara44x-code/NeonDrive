package com.neondrive.emu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Neon {
    val Bg = Color(0xFF07060F)
    val Surface = Color(0xFF110E22)
    val SurfaceHi = Color(0xFF1A1533)
    val Cyan = Color(0xFF00E5FF)
    val Blue = Color(0xFF2F6BFF)
    val Magenta = Color(0xFFFF2BD6)
    val Purple = Color(0xFF8A2BE2)
    val Text = Color(0xFFEDEBFF)
    val TextDim = Color(0xFF9C97C2)

    val edge = Brush.linearGradient(listOf(Cyan, Blue, Purple, Magenta))
    val glowText = TextStyle(
        color = Text, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp,
        shadow = Shadow(color = Cyan, blurRadius = 24f),
    )
}

private val scheme = darkColorScheme(
    primary = Neon.Cyan, onPrimary = Color.Black,
    secondary = Neon.Magenta, onSecondary = Color.Black,
    tertiary = Neon.Purple,
    background = Neon.Bg, onBackground = Neon.Text,
    surface = Neon.Surface, onSurface = Neon.Text,
    surfaceVariant = Neon.SurfaceHi, onSurfaceVariant = Neon.TextDim,
    outline = Neon.Purple,
)

@Composable
fun NeonTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

/** Soft outer glow + gradient border, like the app icon. */
fun Modifier.neonGlow(radius: Dp = 18.dp, glow: Color = Neon.Cyan, strength: Float = 0.35f): Modifier =
    this.drawBehind {
        val r = radius.toPx()
        for (i in 1..4) {
            val spread = i * 3.dp.toPx()
            drawRoundRect(
                color = glow.copy(alpha = strength / i),
                topLeft = androidx.compose.ui.geometry.Offset(-spread, -spread),
                size = androidx.compose.ui.geometry.Size(size.width + spread * 2, size.height + spread * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + spread),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }

@Composable
fun NeonPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    glow: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .then(if (glow) Modifier.neonGlow(radius) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Neon.SurfaceHi, Neon.Surface)))
            .border(1.5.dp, Neon.edge, shape),
        content = content,
    )
}

@Composable
fun NeonChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) Neon.Cyan.copy(alpha = 0.18f) else Neon.Surface)
            .border(1.dp, if (selected) Neon.edge else Brush.linearGradient(listOf(Neon.SurfaceHi, Neon.SurfaceHi)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (selected) Neon.Cyan else Neon.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
