package com.neondrive.emu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.emu.data.Settings
import kotlin.math.roundToInt

/** Brightness / contrast / saturation sliders, shared by Settings and the pause menu. */
@Composable
fun PictureSliders(settings: Settings, onChange: () -> Unit = {}) {
    var brightness by remember { mutableFloatStateOf(settings.brightness) }
    var contrast by remember { mutableFloatStateOf(settings.contrast) }
    var saturation by remember { mutableFloatStateOf(settings.saturation) }

    @Composable
    fun row(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, set: (Float) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Neon.Text, fontSize = 13.sp, modifier = Modifier.width(72.dp))
            Slider(
                value, set, valueRange = range, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Neon.Magenta, activeTrackColor = Neon.Cyan, inactiveTrackColor = Neon.Surface),
            )
            Text(shown, color = Neon.TextDim, fontSize = 12.sp, modifier = Modifier.width(44.dp))
        }
    }

    Column {
        row("الإشراق", brightness, -0.3f..0.3f, "${(brightness * 100).roundToInt()}") {
            brightness = it; settings.brightness = it; onChange()
        }
        row("التباين", contrast, 0.7f..1.4f, "${(contrast * 100).roundToInt()}%") {
            contrast = it; settings.contrast = it; onChange()
        }
        row("التشبّع", saturation, 0f..1.8f, "${(saturation * 100).roundToInt()}%") {
            saturation = it; settings.saturation = it; onChange()
        }
        TextButton(onClick = {
            brightness = 0f; contrast = 1f; saturation = 1f
            settings.brightness = 0f; settings.contrast = 1f; settings.saturation = 1f; onChange()
        }) { Text("رجوع للألوان الأصلية", color = Neon.Magenta, fontSize = 12.sp) }
    }
}
