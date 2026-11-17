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
import com.neondrive.emu.data.AspectMode
import com.neondrive.emu.data.TouchMode
import com.neondrive.emu.game.ControllerSetupActivity
import android.content.Intent
import com.neondrive.emu.data.OfflineDb
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.neondrive.emu.data.GameDatabase
import com.neondrive.emu.data.CheatRepository
import com.neondrive.emu.data.GameSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsScreen(vm: LibraryViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var filter by remember { mutableStateOf(s.filter) }
    var aspect by remember { mutableStateOf(s.aspect) }
    var touchMode by remember { mutableStateOf(s.touchMode) }
    val context = LocalContext.current
    var six by remember { mutableStateOf(s.sixButtonPad) }
    var rewind by remember { mutableStateOf(s.rewind ?: (vm.profile?.rewindByDefault ?: true)) }
    var haptics by remember { mutableStateOf(s.haptics) }
    var autoResume by remember { mutableStateOf(s.autoResume) }
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

        Section("الثيم والألوان") {
            ThemePicker(
                title = "ألوان الواجهة",
                current = Neon.ui,
                onPick = { Neon.ui = it; s.uiPalette = it },
            )
            Spacer(Modifier.height(10.dp))
            ThemePicker(
                title = "ألوان أزرار اللعب",
                current = Neon.pad,
                onPick = { Neon.pad = it; s.padPalette = it },
            )
            Spacer(Modifier.height(6.dp))
            Text("معاينة الأزرار", color = Neon.TextDim, fontSize = 12.sp)
            PadPreview()
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
            Spacer(Modifier.height(8.dp))
            Text("نسبة العرض", color = Neon.Text, fontSize = 14.sp)
            Text(
                "أصلي = نفس نسبة الجهاز الأصلي • تمدد / ملء الشاشة = تكبير الصورة على قياس موبايلك",
                color = Neon.TextDim, fontSize = 11.sp,
            )
            FlowRowCompat {
                AspectMode.entries.forEach { a -> NeonChip(a.label, aspect == a) { aspect = a; s.aspect = a } }
            }
        }

        Section("التحكم") {
            Text("أزرار اللمس", color = Neon.Text, fontSize = 14.sp)
            FlowRowCompat {
                TouchMode.entries.forEach { m -> NeonChip(m.label, touchMode == m) { touchMode = m; s.touchMode = m } }
            }
            Button(
                onClick = { context.startActivity(Intent(context, ControllerSetupActivity::class.java)) },
                colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("إعداد الجويستك وربط الأزرار", color = Neon.Bg, fontWeight = FontWeight.Bold) }
            Toggle("يد 6 أزرار", "A B C + X Y Z لألعاب القتال", six) { six = it; s.sixButtonPad = it }
            Toggle("الاهتزاز عند اللمس", null, haptics) { haptics = it; s.haptics = it }
            Text("شفافية الأزرار ${(opacity * 100).toInt()}%", color = Neon.Text, fontSize = 14.sp)
            Slider(opacity, { opacity = it; s.controlsOpacity = it }, valueRange = 0.2f..1f, colors = neonSlider())
            Text("حجم الأزرار ${(scale * 100).toInt()}%", color = Neon.Text, fontSize = 14.sp)
            Slider(scale, { scale = it; s.controlsScale = it }, valueRange = 0.7f..1.4f, colors = neonSlider())
        }

        Section("المحاكاة") {
            Toggle("استئناف تلقائي", "ترجع للعبة من نفس المكان اللي طلعت منه", autoResume) {
                autoResume = it; s.autoResume = it
            }
            Toggle("الرجوع بالزمن (Rewind)", "زر ⏪ يرجعك ثواني لورا (قبل الموت مثلاً)", rewind) {
                rewind = it; s.rewind = it
            }
        }

        Section("قاعدة البيانات (تشتغل بدون إنترنت)") {
            val scope = rememberCoroutineScope()
            var info by remember { mutableStateOf("…") }
            var busy by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                info = withContext(Dispatchers.IO) {
                    "مدمجة بالتطبيق: ${OfflineDb.cheatFileCount(context)} ملف جيتات + أسماء كل ألعاب سيكا" +
                        (OfflineDb.bundledVersion(context)?.let { " • تاريخ $it" } ?: "")
                }
            }
            Text(info, color = Neon.TextDim, fontSize = 12.sp)
            Text("التعرّف على الألعاب والجيتات يشتغل بدون نت. التحديث اختياري لجلب أحدث الإضافات.", color = Neon.TextDim, fontSize = 11.sp)
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; info = "جاري التحديث…"
                    scope.launch {
                        info = withContext(Dispatchers.IO) {
                            val dats = GameDatabase(context).refreshOnline()
                            val repo = CheatRepository(context)
                            val idx = GameSystem.entries.sumOf { repo.onlineIndex(it, force = true).size }
                            if (dats == 0 && idx == 0) "تعذر الاتصال بالإنترنت — القاعدة المدمجة تبقى شغالة"
                            else "تم التحديث: $dats قواعد أسماء • $idx ملف جيتات أونلاين"
                        }
                        busy = false
                        vm.rescanAll()
                    }
                },
            ) { Text("تحديث من الإنترنت", color = Neon.Cyan) }
        }

        Section("حول") {
            Text("تطوير: مهند سات لنك", color = Neon.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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

/** Preset palettes + a custom 2–3 colour gradient built from swatches. */
@Composable
private fun ThemePicker(title: String, current: NeonPalette, onPick: (NeonPalette) -> Unit) {
    var customOpen by remember { mutableStateOf(current.id == "custom") }
    var picks by remember { mutableStateOf(if (current.id == "custom") current.stops else listOf(NeonPalette.swatches[0], NeonPalette.swatches[9])) }

    Text(title, color = Neon.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    NeonPalette.presets.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { p ->
                Box(Modifier.weight(1f)) { PaletteOption(p, current.id == p.id) { customOpen = false; onPick(p) } }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
    PaletteOption(NeonPalette.custom(picks), current.id == "custom") { customOpen = !customOpen; onPick(NeonPalette.custom(picks)) }

    if (customOpen) {
        Spacer(Modifier.height(8.dp))
        Text("اختر لحد 3 ألوان بالترتيب (اضغط اللون مرة ثانية حتى تشيله)", color = Neon.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        FlowRowCompat {
            NeonPalette.swatches.forEach { c ->
                val index = picks.indexOf(c)
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(c)
                        .border(if (index >= 0) 3.dp else 1.dp, if (index >= 0) Color.White else Neon.SurfaceHi, CircleShape)
                        .clickable {
                            val next = if (index >= 0) picks - c else (picks + c).takeLast(3)
                            if (next.size >= 2) { picks = next; onPick(NeonPalette.custom(next)) } else picks = next
                        },
                    contentAlignment = Alignment.Center,
                ) { if (index >= 0) Text("${index + 1}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
    }
}

/** Small live preview of the touch buttons with the chosen pad palette. */
@Composable
private fun PadPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("A" to 0f, "B" to 0.2f, "C" to 0.4f, "X" to 0.6f, "Y" to 0.8f, "Z" to 1f).forEach { (label, t) ->
            val c = Neon.pad.sample(t)
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Neon.Surface).border(2.dp, c, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = c, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
        }
    }
}
