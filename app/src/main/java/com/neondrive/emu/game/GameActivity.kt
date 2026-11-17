package com.neondrive.emu.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.neondrive.emu.data.CheatRepository
import com.neondrive.emu.data.Game
import com.neondrive.emu.data.GameSystem
import com.neondrive.emu.data.Settings
import com.neondrive.emu.device.DeviceProfile
import com.neondrive.emu.device.DeviceProfiler
import com.neondrive.emu.ui.Neon
import com.neondrive.emu.ui.NeonTheme
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class GameActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_GAME = "game"
        private const val EXTRA_ROM = "rom"
        const val CORE_NORMAL = "libgenesis_plus_gx_libretro_android.so"
        const val CORE_WIDE = "libgenesis_plus_gx_wide_libretro_android.so"
        const val AUTO_SLOT = 0

        fun start(context: Context, game: Game, rom: File) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_GAME, game.toJson().toString())
                    .putExtra(EXTRA_ROM, rom.absolutePath)
            )
        }

        fun statesDir(context: Context) = File(context.filesDir, "states").apply { mkdirs() }
        fun stateFile(context: Context, game: Game, slot: Int) = File(statesDir(context), "${game.crc}_$slot.state")
    }

    private lateinit var retroView: GLRetroView
    private lateinit var game: Game
    private lateinit var settings: Settings
    private lateinit var profile: DeviceProfile
    private lateinit var cheats: CheatRepository
    private lateinit var sramFile: File

    @Volatile private var coreReady = false
    private var rewindEnabled = false
    private val rewindBuffer = ArrayDeque<ByteArray>()
    private var rewindMax = 60
    @Volatile private var rewinding = false

    private var fastForward by mutableStateOf(false)
    private var gamepadConnected by mutableStateOf(false)
    private var showTouchAnyway by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var lastSram: ByteArray? = null

    private val menuLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.let(::handleMenuResult)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        game = Game.fromJson(JSONObject(intent.getStringExtra(EXTRA_GAME)!!))
        val romPath = intent.getStringExtra(EXTRA_ROM)!!
        settings = Settings(this)
        profile = DeviceProfiler.get(this)
        cheats = CheatRepository(this)
        rewindEnabled = settings.rewind ?: profile.rewindByDefault

        val savesDir = File(filesDir, "saves").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        sramFile = File(savesDir, "${game.crc}.srm")

        val coreName = if (settings.widescreen && game.system == GameSystem.GENESIS) CORE_WIDE else CORE_NORMAL
        val core = File(applicationInfo.nativeLibraryDir, coreName)
        if (!core.exists()) {
            Toast.makeText(this, "المحرك غير موجود داخل التطبيق: $coreName", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val data = GLRetroViewData(this).apply {
            coreFilePath = core.absolutePath
            gameFilePath = romPath
            systemDirectory = systemDir.absolutePath
            savesDirectory = savesDir.absolutePath
            saveRAMState = sramFile.takeIf { it.exists() }?.readBytes()
            shader = profile.shaderFor(settings.filter)
            rumbleEventsEnabled = false
            preferLowLatencyAudio = true
            skipDuplicateFrames = profile.skipDuplicateFrames
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code -> onEngineError(code) }
        }
        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it == GLRetroView.GLRetroEvents.FrameRendered }
            onCoreReady()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = openMenu()
        })

        // Game controls must stay left-to-right even when the phone language is Arabic.
        setContent {
            NeonTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { GameScreen() }
            }
        }
    }

    @Composable
    private fun GameScreen() {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(factory = { retroView }, modifier = Modifier.fillMaxSize())

            if (!gamepadConnected || showTouchAnyway) {
                TouchControls(
                    cfg = ControlsConfig(
                        system = game.system,
                        sixButtons = settings.sixButtonPad,
                        opacity = settings.controlsOpacity,
                        scale = settings.controlsScale,
                        haptics = settings.haptics,
                        rewindEnabled = rewindEnabled,
                    ),
                    fastForward = fastForward,
                    onKey = { code, pressed ->
                        retroView.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
                    },
                    onDpad = { x, y -> retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y) },
                    onMenu = ::openMenu,
                    onToggleFastForward = ::toggleFastForward,
                    onRewind = ::setRewinding,
                )
            }

            message?.let {
                Text(
                    it, color = Neon.Text,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Neon.Surface.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LaunchedEffect(it) { delay(1600); message = null }
            }
        }
    }

    // ---------- Core lifecycle ----------

    /** Only loading/GL failures are fatal. Cheat or save-state errors must never close the game. */
    private fun onEngineError(code: Int) {
        when (code) {
            GLRetroView.ERROR_LOAD_LIBRARY, GLRetroView.ERROR_LOAD_GAME, GLRetroView.ERROR_GL_NOT_COMPATIBLE -> {
                Toast.makeText(this, "تعذر تشغيل اللعبة ($code) — تأكد من سلامة ملف اللعبة", Toast.LENGTH_LONG).show()
                finish()
            }
            GLRetroView.ERROR_CHEAT -> message = "أحد أكواد الجيتات غير صالح لهاي النسخة من اللعبة"
            GLRetroView.ERROR_SERIALIZATION -> {
                stateFile(this, game, AUTO_SLOT).delete()
                message = "تعذر تحميل الحفظ"
            }
            else -> message = "تنبيه من المحرك ($code)"
        }
    }

    // ---- Crash guard: if the app dies right after resuming a state or applying cheats,
    // the next launch skips that step instead of crashing again.
    private val guardFile by lazy { File(filesDir, "guard_${game.crc}") }

    private fun recoverFromLastCrash() {
        if (!guardFile.exists()) return
        val pending = guardFile.readText()
        if ("cheats" in pending) {
            cheats.save(game, cheats.load(game).map { it.copy(enabled = false) })
            message = "عطّلنا الجيتات لأنها سببت إغلاق اللعبة المرة السابقة"
        }
        if ("resume" in pending) stateFile(this, game, AUTO_SLOT).delete()
        guardFile.delete()
    }

    private fun onCoreReady() {
        coreReady = true
        recoverFromLastCrash()
        lifecycleScope.launch(Dispatchers.Default) {
            if (game.system == GameSystem.GENESIS && settings.sixButtonPad) {
                runCatching {
                    val options = retroView.getControllers().firstOrNull().orEmpty()
                    options.firstOrNull { it.description?.contains("6") == true }?.let { retroView.setControllerType(0, it.id) }
                }
            }
            val auto = stateFile(this@GameActivity, game, AUTO_SLOT)
            val willResume = settings.autoResume && auto.exists()
            val hasCheats = cheats.load(game).any { it.enabled }
            if (willResume || hasCheats) {
                guardFile.writeText(listOfNotNull("resume".takeIf { willResume }, "cheats".takeIf { hasCheats }).joinToString(","))
            }
            if (willResume && runCatching { retroView.unserializeState(auto.readBytes()) }.getOrDefault(false)) {
                message = "تم الاستئناف من آخر نقطة لعبت بيها"
            }
            applyCheats()
            delay(5_000)          // survived 5 s -> it's safe
            guardFile.delete()
        }
        // Periodic SRAM autosave + rewind capture, only while the game is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                withContext(Dispatchers.Default) {
                    var sinceSram = 0L
                    val step = profile.rewindIntervalMs
                    while (isActive) {
                        delay(step)
                        if (rewindEnabled && !rewinding) captureRewind()
                        sinceSram += step
                        if (sinceSram >= 30_000) { sinceSram = 0; saveSram(); saveAutoState() }
                    }
                }
            }
        }
    }

    private fun captureRewind() {
        val state = runCatching { retroView.serializeState() }.getOrNull() ?: return
        if (state.isEmpty()) return
        synchronized(rewindBuffer) {
            if (rewindBuffer.isEmpty()) {
                // Budget ~96 MB on high-end, ~48 MB otherwise.
                val budget = if (profile.tier == com.neondrive.emu.device.Tier.HIGH) 96L shl 20 else 48L shl 20
                rewindMax = (budget / state.size).toInt().coerceIn(20, 240)
            }
            rewindBuffer.addLast(state)
            while (rewindBuffer.size > rewindMax) rewindBuffer.removeFirst()
        }
    }

    private fun setRewinding(on: Boolean) {
        if (!coreReady) return
        rewinding = on
        if (!on) return
        lifecycleScope.launch(Dispatchers.Default) {
            while (rewinding) {
                val state = synchronized(rewindBuffer) { rewindBuffer.removeLastOrNull() }
                if (state == null) { rewinding = false; break }
                retroView.unserializeState(state)
                delay(70)
            }
        }
    }

    private fun toggleFastForward() {
        fastForward = !fastForward
        retroView.frameSpeed = if (fastForward) (if (profile.tier == com.neondrive.emu.device.Tier.LOW) 2 else 4) else 1
    }

    private fun saveSram() {
        if (!coreReady) return
        val bytes = runCatching { retroView.serializeSRAM() }.getOrNull() ?: return
        if (bytes.isEmpty() || bytes.contentEquals(lastSram)) return
        lastSram = bytes
        sramFile.writeBytes(bytes)
    }

    private suspend fun applyCheats() = withContext(Dispatchers.Default) {
        cheats.load(game).forEachIndexed { i, c ->
            val code = c.code.uppercase().replace(" ", "")
            runCatching { retroView.setCheat(i, c.enabled, code) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::game.isInitialized) guardFile.delete() // normal exit, not a crash
    }

    override fun onPause() {
        super.onPause()
        saveSram()
    }

    /** Snapshot used by "auto resume" next time the game is opened. */
    private fun saveAutoState() {
        if (!coreReady || !settings.autoResume || guardFile.exists()) return
        runCatching {
            val bytes = retroView.serializeState()
            if (bytes.isNotEmpty()) stateFile(this, game, AUTO_SLOT).writeBytes(bytes)
        }
    }

    // ---------- Menu ----------

    private fun openMenu() {
        menuLauncher.launch(GameMenuActivity.intent(this, game))
    }

    private fun handleMenuResult(data: Intent) {
        if (data.getBooleanExtra(GameMenuActivity.RES_FILTER_CHANGED, false)) {
            retroView.shader = profile.shaderFor(settings.filter)
        }
        if (data.getBooleanExtra(GameMenuActivity.RES_CHEATS_CHANGED, false)) {
            lifecycleScope.launch {
                guardFile.writeText("cheats")
                applyCheats(); message = "تم تحديث الجيتات"
                delay(5_000); guardFile.delete()
            }
        }
        val slot = data.getIntExtra(GameMenuActivity.RES_SLOT, 0)
        when (data.getStringExtra(GameMenuActivity.RES_ACTION)) {
            GameMenuActivity.ACTION_SAVE -> lifecycleScope.launch(Dispatchers.Default) {
                val ok = runCatching { stateFile(this@GameActivity, game, slot).writeBytes(retroView.serializeState()) }.isSuccess
                message = if (ok) "تم الحفظ في الخانة $slot" else "فشل الحفظ"
            }
            GameMenuActivity.ACTION_LOAD -> lifecycleScope.launch(Dispatchers.Default) {
                val f = stateFile(this@GameActivity, game, slot)
                val ok = f.exists() && runCatching { retroView.unserializeState(f.readBytes()) }.getOrDefault(false)
                synchronized(rewindBuffer) { rewindBuffer.clear() }
                message = if (ok) "تم التحميل من الخانة $slot" else "الخانة $slot فارغة"
            }
            GameMenuActivity.ACTION_RESET -> lifecycleScope.launch(Dispatchers.Default) {
                retroView.reset(); synchronized(rewindBuffer) { rewindBuffer.clear() }
                stateFile(this@GameActivity, game, AUTO_SLOT).delete()
            }
            GameMenuActivity.ACTION_TOUCH -> showTouchAnyway = !showTouchAnyway
            GameMenuActivity.ACTION_QUIT -> lifecycleScope.launch(Dispatchers.Default) {
                saveAutoState(); saveSram()
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    // ---------- Physical controllers ----------

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        gamepadConnected = InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id) ?: return@any false
            !d.isVirtual && isGamepad(d.sources)
        }
    }

    private fun has(source: Int, flag: Int) = (source and flag) == flag

    private fun isGamepad(source: Int) =
        has(source, InputDevice.SOURCE_GAMEPAD) || has(source, InputDevice.SOURCE_JOYSTICK)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::retroView.isInitialized && isGamepad(event.source)) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) openMenu()
                return true
            }
            if (!gamepadConnected) gamepadConnected = true
            val handled = if (event.action == KeyEvent.ACTION_DOWN) retroView.onKeyDown(event.keyCode, event)
                          else retroView.onKeyUp(event.keyCode, event)
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::retroView.isInitialized && isGamepad(event.source) && retroView.onGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
