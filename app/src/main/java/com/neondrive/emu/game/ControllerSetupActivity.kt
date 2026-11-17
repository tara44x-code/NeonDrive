package com.neondrive.emu.game

import android.content.Context
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.emu.data.PadTarget
import com.neondrive.emu.data.Settings
import com.neondrive.emu.ui.Neon
import com.neondrive.emu.ui.NeonPanel
import com.neondrive.emu.ui.NeonTheme

/**
 * Lists connected controllers (live) and lets the player map each Sega button:
 * tap a row, then press the button on the controller.
 */
class ControllerSetupActivity : ComponentActivity(), InputManager.InputDeviceListener {

    private lateinit var settings: Settings
    private var map by mutableStateOf<Map<Int, PadTarget>>(emptyMap())
    private var waitingFor by mutableStateOf<PadTarget?>(null)
    private var devices by mutableStateOf<List<String>>(emptyList())
    private var lastKey by mutableStateOf<String?>(null)
    private val inputManager by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        map = settings.padMap
        refreshDevices()
        setContent {
            NeonTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        refreshDevices()
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
    override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()

    private fun refreshDevices() {
        devices = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
            .filter { d -> !d.isVirtual && ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) }
            .map { it.name }.distinct().toList()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = waitingFor
        val fromPad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (event.source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        if (fromPad && event.action == KeyEvent.ACTION_UP) lastKey = KeyEvent.keyCodeToString(event.keyCode)
        if (target != null && fromPad) {
            if (event.action == KeyEvent.ACTION_UP) {
                // one physical key -> one target; replace any previous binding of either
                val updated = map.filterValues { it != target }.toMutableMap()
                updated[event.keyCode] = target
                map = updated; settings.padMap = updated
                waitingFor = null
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Neon.Cyan) }
                Text("إعداد الجويستك", style = Neon.glowText.copy(fontSize = 22.sp))
            }

            NeonPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("الأجهزة المتصلة", color = Neon.Cyan, fontWeight = FontWeight.Bold)
                    if (devices.isEmpty()) {
                        Text("ماكو جويستك متصل. وصّله بالبلوتوث أو الكيبل وراح يظهر هنا تلقائياً.", color = Neon.TextDim, fontSize = 13.sp)
                    } else devices.forEach {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SportsEsports, null, tint = Neon.Magenta)
                            Spacer(Modifier.width(8.dp))
                            Text(it, color = Neon.Text, fontSize = 14.sp)
                        }
                    }
                    lastKey?.let { Text("آخر زر انضغط: $it", color = Neon.TextDim, fontSize = 12.sp) }
                }
            }

            NeonPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ربط الأزرار يدوياً", color = Neon.Cyan, fontWeight = FontWeight.Bold)
                    Text(
                        "اضغط على الزر بالقائمة، وبعدها اضغط الزر اللي تريده على الجويستك. الأسهم تشتغل تلقائياً. إذا ما ربطت شي، يشتغل الربط الافتراضي.",
                        color = Neon.TextDim, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    PadTarget.entries.forEach { t ->
                        val bound = map.entries.firstOrNull { it.value == t }?.key
                        val waiting = waitingFor == t
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (waiting) Neon.Magenta.copy(alpha = 0.25f) else Neon.Surface)
                                .clickable { waitingFor = if (waiting) null else t }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t.label, color = Neon.Text, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                            Text(
                                when {
                                    waiting -> "اضغط الزر على الجويستك…"
                                    bound != null -> KeyEvent.keyCodeToString(bound).removePrefix("KEYCODE_")
                                    else -> "افتراضي"
                                },
                                color = if (waiting) Neon.Magenta else Neon.TextDim, fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    TextButton(onClick = { map = emptyMap(); settings.padMap = emptyMap(); waitingFor = null }) {
                        Text("رجوع للربط الافتراضي", color = Neon.Magenta)
                    }
                }
            }
        }
    }
}
