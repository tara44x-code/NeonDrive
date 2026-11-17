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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neondrive.emu.game.GameActivity
import com.neondrive.emu.ui.LibraryScreen
import com.neondrive.emu.ui.LibraryViewModel
import com.neondrive.emu.ui.NeonTheme
import com.neondrive.emu.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeonTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val vm: LibraryViewModel = viewModel()
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    BackHandler(enabled = showSettings) { showSettings = false }
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
