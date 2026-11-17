package com.neondrive.emu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.neondrive.emu.game.GameActivity
import com.neondrive.emu.ui.LibraryScreen
import com.neondrive.emu.ui.LibraryViewModel
import com.neondrive.emu.ui.NeonTheme
import com.neondrive.emu.ui.SettingsScreen
import com.neondrive.emu.ui.Neon
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()

    private var crashReport by mutableStateOf<String?>(null)

    override fun onStart() {
        super.onStart()
        vm.autoScan() // new games copied into the folder show up automatically
    }

    override fun onResume() {
        super.onResume()
        // Android records a crash a moment after the app restarts, so check now and again shortly after.
        lifecycleScope.launch {
            repeat(2) { attempt ->
                if (attempt > 0) delay(2_000)
                if (crashReport == null) {
                    crashReport = withContext(Dispatchers.IO) { CrashReporter.pendingReport(this@MainActivity) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeonTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    BackHandler(enabled = showSettings) { showSettings = false }
                    crashReport?.let { text -> CrashReportDialog(text) { crashReport = null } }
                    if (showSettings) {
                        SettingsScreen(vm, onBack = { showSettings = false })
                    } else {
                        LibraryScreen(
                            vm = vm,
                            onOpenSettings = { showSettings = true },
                            onPlay = { game ->
                                vm.launch(game) { g, rom -> GameActivity.start(this@MainActivity, g, rom) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashReportDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.SurfaceHi,
        title = { Text("اللعبة سكّرت المرة السابقة", color = Neon.Magenta, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("هذا تقرير المشكلة. انسخه أو شاركه ويا المطوّر حتى نصلّحها بالضبط:", color = Neon.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(text, color = Neon.Text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton({
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "مشاركة التقرير",
                ))
            }) { Text("مشاركة", color = Neon.Cyan) }
        },
        dismissButton = {
            Row {
                TextButton({ clipboard.setText(AnnotatedString(text)) }) { Text("نسخ", color = Neon.Cyan) }
                TextButton(onDismiss) { Text("إغلاق", color = Neon.TextDim) }
            }
        },
    )
}
