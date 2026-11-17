package com.neondrive.emu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.emu.data.VideoFilter

@Composable
fun SettingsScreen(vm: LibraryViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var filter by remember { mutableStateOf(s.filter) }
    var wide by remember { mutableStateOf(s.widescreen) }
    var six by remember { mutableStateOf(s.sixButtonPad) }
    var rewind by remember { mutableStateOf(s.rewind ?: (vm.profile?.rewindByDefault ?: true)) }
    var haptics by remember { mutableStateOf(s.haptics) }
    var opacity by remember { mutableFloatStateOf(s.controlsOpacity) }
    var scale by remember { mutableFloatStateOf(s.controlsScale) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Neon.Cyan) }
            Text("الإعدادات", style = Neon.glowText)
        }

        Section("الصورة والدقة") {
            val auto = vm.profile?.autoFilter?.label
            Text(
                "التلقائي يختار الفلتر حسب المعالج وكارت الشاشة" + (auto?.let { " (جهازك: $it)" } ?: ""),
                color = Neon.TextDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            FlowRowCompat {
                VideoFilter.entries.forEach { f ->
                    NeonChip(f.label, filter == f) { filter = f; s.filter = f }
                }
            }
            Toggle("شاشة عريضة 16:9 (تجريبي)", "يوسّع مجال الرؤية بألعاب Mega Drive", wide) { wide = it; s.widescreen = it }
        }

        Section("التحكم") {
            Toggle("يد 6 أزرار", "A B C + X Y Z لألعاب القتال", six) { six = it; s.sixButtonPad = it }
            Toggle("الاهتزاز عند اللمس", null, haptics) { haptics = it; s.haptics = it }
            Text("شفافية الأزرار ${(opacity * 100).toInt()}%", color = Neon.Text, fontSize = 14.sp)
            Slider(opacity, { opacity = it; s.controlsOpacity = it }, valueRange = 0.2f..1f, colors = neonSlider())
            Text("حجم الأزرار ${(scale * 100).toInt()}%", color = Neon.Text, fontSize = 14.sp)
            Slider(scale, { scale = it; s.controlsScale = it }, valueRange = 0.7f..1.4f, colors = neonSlider())
        }

        Section("المحاكاة") {
            Toggle("الرجوع بالزمن (Rewind)", "يستهلك ذاكرة إضافية؛ مطفأ تلقائياً بالأجهزة الضعيفة", rewind) {
                rewind = it; s.rewind = it
            }
        }

        Section("حول") {
            Text(
                "NeonDrive مجاني ومفتوح المصدر (GPLv3).\nالمحرك: Genesis Plus GX عبر LibretroDroid.\n" +
                    "الأسماء والأغلفة والجيتات من قاعدة بيانات libretro.\nالتطبيق لا يحتوي على أي ألعاب.",
                color = Neon.TextDim, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Neon.Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Neon.Text, fontSize = 14.sp)
            subtitle?.let { Text(it, color = Neon.TextDim, fontSize = 11.sp) }
        }
        Switch(
            value, onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Neon.Bg, checkedTrackColor = Neon.Cyan, uncheckedTrackColor = Neon.Surface),
        )
    }
}

@Composable
private fun neonSlider() = SliderDefaults.colors(thumbColor = Neon.Magenta, activeTrackColor = Neon.Cyan, inactiveTrackColor = Neon.Surface)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
