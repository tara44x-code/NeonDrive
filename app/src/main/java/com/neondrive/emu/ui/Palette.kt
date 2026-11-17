package com.neondrive.emu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A neon gradient (2+ colour stops). Interface and touch buttons each use one. */
data class NeonPalette(val id: String, val label: String, val stops: List<Color>) {
    /** Colour at position [t] (0..1) along the gradient. */
    fun sample(t: Float): Color {
        if (stops.size == 1) return stops[0]
        val x = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        return lerp(stops[i], stops[i + 1], x - i)
    }

    fun encode() = stops.joinToString(",") { it.toArgb().toUInt().toString(16) }

    companion object {
        val presets = listOf(
            NeonPalette("neon", "نيون كلاسيك", listOf(Color(0xFF00E5FF), Color(0xFF2F6BFF), Color(0xFF8A2BE2), Color(0xFFFF2BD6))),
            NeonPalette("cyber", "سايبر", listOf(Color(0xFF00FFC6), Color(0xFF00B3FF), Color(0xFF7A5CFF))),
            NeonPalette("synth", "سينث ويف", listOf(Color(0xFFFFD319), Color(0xFFFF901F), Color(0xFFFF2975), Color(0xFF8C1EFF))),
            NeonPalette("laser", "أخضر ليزر", listOf(Color(0xFFB6FF00), Color(0xFF39FF14), Color(0xFF00E676))),
            NeonPalette("fire", "ناري", listOf(Color(0xFFFFE53B), Color(0xFFFF7A00), Color(0xFFFF2525))),
            NeonPalette("ice", "جليدي", listOf(Color(0xFFE0FBFF), Color(0xFF7DF9FF), Color(0xFF3D8BFF))),
            NeonPalette("royal", "ملكي", listOf(Color(0xFFFFD700), Color(0xFFC77DFF), Color(0xFF7B2CBF))),
            NeonPalette("pink", "وردي", listOf(Color(0xFFFFB3F0), Color(0xFFFF4FD8), Color(0xFFB5179E))),
            NeonPalette("blood", "أحمر قاني", listOf(Color(0xFFFF5370), Color(0xFFE0115F), Color(0xFF8B0033))),
            NeonPalette("rainbow", "قوس قزح", listOf(Color(0xFFFF2525), Color(0xFFFFD319), Color(0xFF39FF14), Color(0xFF00E5FF), Color(0xFF8A2BE2), Color(0xFFFF2BD6))),
        )
        val default = presets.first()

        /** Swatches for the custom gradient picker. */
        val swatches = listOf(
            0xFF00E5FF, 0xFF00FFC6, 0xFF39FF14, 0xFFB6FF00, 0xFFFFE53B, 0xFFFFD700, 0xFFFF901F, 0xFFFF2525,
            0xFFFF2975, 0xFFFF2BD6, 0xFFC77DFF, 0xFF8A2BE2, 0xFF7A5CFF, 0xFF2F6BFF, 0xFF3D8BFF, 0xFFFFFFFF,
        ).map { Color(it) }

        fun custom(stops: List<Color>) = NeonPalette("custom", "مخصص", stops)

        fun byId(id: String?, customCode: String?): NeonPalette {
            if (id == "custom") decode(customCode)?.let { return custom(it) }
            return presets.firstOrNull { it.id == id } ?: default
        }

        private fun decode(code: String?): List<Color>? = code?.split(',')
            ?.mapNotNull { it.toULongOrNull(16)?.let { v -> Color(v.toInt()) } }
            ?.takeIf { it.size >= 2 }
    }
}

/** Segmented glowing bar (like an equalizer) that previews a palette. */
@Composable
fun PaletteBar(palette: NeonPalette, modifier: Modifier = Modifier, segments: Int = 16, height: Dp = 22.dp) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(segments) { i ->
            val c = palette.sample(i / (segments - 1f))
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .drawBehind {
                        drawRoundRect(c.copy(alpha = 0.35f), Offset(-2f, -2f), Size(size.width + 4f, size.height + 4f), CornerRadius(6f))
                    }
                    .clip(RoundedCornerShape(3.dp))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.6f)))),
            )
        }
    }
}

/** Selectable palette card: name + bar, highlighted when selected. */
@Composable
fun PaletteOption(palette: NeonPalette, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) palette.sample(0f).copy(alpha = 0.12f) else Neon.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) palette.sample(0.5f) else Neon.SurfaceHi, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(palette.label, color = if (selected) palette.sample(0f) else Neon.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (selected) Text("✓", color = palette.sample(1f), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        PaletteBar(palette, Modifier.fillMaxWidth(), height = 16.dp)
    }
}
