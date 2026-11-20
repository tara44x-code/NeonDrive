package com.neondrive.emu.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.neondrive.emu.CrashReporter
import com.neondrive.emu.data.AspectMode
import com.neondrive.emu.data.CheatRepository
import com.neondrive.emu.data.Game
import com.neondrive.emu.data.GameSystem
import com.neondrive.emu.data.PadTarget
import com.neondrive.emu.data.Settings
import com.neondrive.emu.data.TouchMode
import com.neondrive.emu.device.DeviceProfile
import com.neondrive.emu.device.DeviceProfiler
import com.neondrive.emu.device.Tier
import com.neondrive.emu.ui.Neon
import com.neondrive.emu.ui.NeonTheme
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.Variable
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class GameActivity : ComponentActivity(), InputManager.InputDeviceListener {

    companion object {
        private const val EXTRA_GAME = "game"
        private const val EXTRA_ROM = "rom"
        const val CORE_NORMAL = "libgenesis_plus_gx_libretro_android.so"
        const val CORE_WIDE = "libgenesis_plus_gx_wide_libretro_android.so"
        const val AUTO_SLOT = 0

        private const val EXTRA_RESUME = "resume"

        /** [resume]: continue from the last exit point (true) or start the game fresh (false). */
        fun start(context: Context, game: Game, rom: File, resume: Boolean = false) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_GAME, game.toJson().toString())
                    .putExtra(EXTRA_ROM, rom.absolutePath)
                    .putExtra(EXTRA_RESUME, resume)
            )
        }

        /** The widescreen core is not used: LibretroDroid draws its wider frames incorrectly (split picture). */
        @Suppress("UNUSED_PARAMETER")
        fun usesWideCore(settings: Settings, game: Game) = false

        fun statesDir(context: Context) = File(context.filesDir, "states").apply { mkdirs() }

        /** States from the normal and the widescreen core are not compatible, so they are stored apart. */
        fun stateFile(context: Context, game: Game, slot: Int, wide: Boolean = usesWideCore(Settings(context), game)) =
            File(statesDir(context), "${game.crc}${if (wide) "_w" else ""}_$slot.state")
    }

    private lateinit var retroView: GLRetroView
    private lateinit var game: Game
    private lateinit var romPath: String
    private lateinit var settings: Settings
    private lateinit var profile: DeviceProfile
    private lateinit var cheats: CheatRepository
    private lateinit var sramFile: File
    private var wideCore = false

    @Volatile private var coreReady = false
    private var rewindEnabled = false
    private val rewindBuffer = ArrayDeque<ByteArray>()
    private var rewindMax = 30
    @Volatile private var rewinding = false

    private var fastForward by mutableStateOf(false)
    private var gamepadName by mutableStateOf<String?>(null)
    private var touchOverride by mutableStateOf<Boolean?>(null)   // set from the pause menu for this session
    private var aspect by mutableStateOf(AspectMode.ORIGINAL)
    private var message by mutableStateOf<String?>(null)
    private var lastSram: ByteArray? = null
    private var padMap: Map<Int, PadTarget> = emptyMap()

    /** Any unexpected error in background work is reported instead of killing the game. */
    private val safe = CoroutineExceptionHandler { _, e ->
        CrashReporter.crumb("coroutine error: ${e::class.java.simpleName}: ${e.message}")
        message = "خطأ داخلي: ${e::class.java.simpleName}"
    }

    private val menuLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.let(::handleMenuResult)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        game = Game.fromJson(JSONObject(intent.getStringExtra(EXTRA_GAME)!!))
        romPath = intent.getStringExtra(EXTRA_ROM)!!
        settings = Settings(this)
        profile = DeviceProfiler.get(this)
        cheats = CheatRepository(this)
        rewindEnabled = settings.rewind ?: profile.rewindByDefault
        aspect = settings.aspect
        padMap = settings.padMap
        wideCore = usesWideCore(settings, game)

        val savesDir = File(filesDir, "saves").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        sramFile = File(savesDir, "${game.crc}.srm")

        val coreName = if (wideCore) CORE_WIDE else CORE_NORMAL
        val core = File(applicationInfo.nativeLibraryDir, coreName)
        CrashReporter.crumb("open '${game.title}' [${game.system}] core=$coreName aspect=$aspect rewind=$rewindEnabled tier=${profile.tier}")
        if (!core.exists()) {
            Toast.makeText(this, "المحرك غير موجود داخل التطبيق: $coreName", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val data = GLRetroViewData(this).apply {
            coreFilePath = core.absolutePath
            gameFilePath = romPath
            systemDirectory = systemDir.absolutePath
            savesDirectory = savesDir.absolutePath
            saveRAMState = sramFile.takeIf { it.exists() && it.length() > 0 }?.readBytes()
            shader = profile.shaderFor(settings.filter)
            rumbleEventsEnabled = false
            preferLowLatencyAudio = true
            skipDuplicateFrames = profile.skipDuplicateFrames
            variables = coreVariables()
            colorAdjust = settings.colorAdjust()
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        lifecycleScope.launch(safe) {
            retroView.getGLRetroErrors().collect { code -> onEngineError(code) }
        }
        lifecycleScope.launch(safe) {
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

    /** Engine options for picture quality, taken from Settings. */
    private fun coreVariables(): Array<Variable> = arrayOf(
        Variable("genesis_plus_gx_blargg_ntsc_filter", settings.colorMode.coreValue),
        Variable("genesis_plus_gx_no_sprite_limit", if (settings.noSpriteLimit) "enabled" else "disabled"),
        Variable("genesis_plus_gx_lcd_filter", if (settings.ggLcd) "enabled" else "disabled"),
    )

    /** Console picture height in lines and display ratio, for pixel-perfect (integer) scaling. */
    private val nativeLines: Int get() = when (game.system) {
        GameSystem.GENESIS -> 224; GameSystem.GAME_GEAR -> 144; else -> 192
    }
    private val nativeRatio: Float get() = if (game.system == GameSystem.GAME_GEAR) 10f / 9f else 4f / 3f

    // ---------- Screen ----------

    @Composable
    private fun GameScreen() {
        val showTouch = touchOverride ?: when (settings.touchMode) {
            TouchMode.AUTO -> gamepadName == null
            TouchMode.ALWAYS -> true
            TouchMode.HIDDEN -> false
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val ratio = aspect.ratio
            val density = LocalDensity.current
            val viewModifier = when {
                aspect == AspectMode.INTEGER -> {
                    // Largest whole multiple of the console's lines that fits the screen.
                    val maxH = constraints.maxHeight; val maxW = constraints.maxWidth
                    var k = (maxH / nativeLines).coerceAtLeast(1)
                    while (k > 1 && k * nativeLines * nativeRatio > maxW) k--
                    val hPx = k * nativeLines
                    val wPx = (hPx * nativeRatio).toInt()
                    with(density) { Modifier.size(wPx.toDp(), hPx.toDp()) }
                }
                ratio != null -> Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true)
                else -> Modifier.fillMaxSize()
            }

            AndroidView(
                factory = { retroView },
                modifier = viewModifier,
                update = { applySurfaceSize(aspect) },
            )

            if (showTouch) {
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
                        if (coreReady) retroView.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
                    },
                    onDpad = { x, y -> if (coreReady) retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y) },
                    onMenu = ::openMenu,
                    onToggleFastForward = ::toggleFastForward,
                    onRewind = ::setRewinding,
                )
            } else {
                // Controls hidden: keep a faint menu button so the player is never stuck.
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(34.dp).alpha(0.35f)
                        .clip(CircleShape).background(Neon.Surface).clickable { openMenu() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Menu, "القائمة", tint = Neon.Cyan, modifier = Modifier.size(18.dp)) }
            }

            message?.let {
                Text(
                    it, color = Neon.Text,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Neon.Surface.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LaunchedEffect(it) { delay(2200); message = null }
            }
        }
    }

    private var appliedAspect: AspectMode? = null

    /**
     * Stretch modes: the GL surface keeps the console's own ratio and Android scales it to the view,
     * whose size is the ratio the player picked (or the whole screen for FULL).
     */
    private fun applySurfaceSize(mode: AspectMode) {
        if (appliedAspect == mode) return
        appliedAspect = mode
        val holder = retroView.holder
        if (mode == AspectMode.ORIGINAL || mode == AspectMode.INTEGER) {
            holder.setSizeFromLayout()
        } else {
            val native = when (game.system) { GameSystem.GAME_GEAR -> 10f / 9f; else -> 4f / 3f }
            val h = 960
            holder.setFixedSize((h * native).toInt(), h)
        }
    }

    // ---------- Core lifecycle ----------

    /** Only loading/GL failures are fatal. Cheat or save-state errors must never close the game. */
    private fun onEngineError(code: Int) {
        CrashReporter.crumb("engine error $code")
        when (code) {
            GLRetroView.ERROR_LOAD_LIBRARY, GLRetroView.ERROR_LOAD_GAME, GLRetroView.ERROR_GL_NOT_COMPATIBLE -> {
                Toast.makeText(this, "تعذر تشغيل اللعبة ($code) — تأكد من سلامة ملف اللعبة", Toast.LENGTH_LONG).show()
                finish()
            }
            GLRetroView.ERROR_CHEAT -> message = "أحد أكواد الجيتات غير صالح لهاي النسخة من اللعبة"
            GLRetroView.ERROR_SERIALIZATION -> {
                stateFile(this, game, AUTO_SLOT, wideCore).delete()
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
        CrashReporter.crumb("recovering after crash during: $pending")
        if ("cheats" in pending) {
            cheats.save(game, cheats.load(game).map { it.copy(enabled = false) })
            message = "عطّلنا الجيتات لأنها سببت إغلاق اللعبة المرة السابقة"
        }
        if ("resume" in pending) stateFile(this, game, AUTO_SLOT, wideCore).delete()
        guardFile.delete()
    }

    private fun onCoreReady() {
        coreReady = true
        CrashReporter.crumb("core ready")
        recoverFromLastCrash()
        lifecycleScope.launch(Dispatchers.Default + safe) {
            if (game.system == GameSystem.GENESIS && settings.sixButtonPad) {
                runCatching {
                    val options = retroView.getControllers().firstOrNull().orEmpty()
                    options.firstOrNull { it.description?.contains("6") == true }?.let {
                        CrashReporter.crumb("controller -> ${it.description}")
                        retroView.setControllerType(0, it.id)
                    }
                }
            }
            val auto = stateFile(this@GameActivity, game, AUTO_SLOT, wideCore)
            val willResume = intent.getBooleanExtra(EXTRA_RESUME, false) && auto.exists() && auto.length() > 0
            val enabledCheats = cheats.load(game).count { it.enabled }
            if (willResume || enabledCheats > 0) {
                guardFile.writeText(listOfNotNull("resume".takeIf { willResume }, "cheats".takeIf { enabledCheats > 0 }).joinToString(","))
            }
            if (willResume) {
                CrashReporter.crumb("auto-resume (${auto.length()} bytes)")
                if (runCatching { retroView.unserializeState(auto.readBytes()) }.getOrDefault(false)) {
                    message = "تم الاستئناف من آخر نقطة لعبت بيها"
                }
            }
            if (enabledCheats > 0) { CrashReporter.crumb("apply $enabledCheats cheats"); applyCheats() }
            delay(5_000)          // survived 5 s -> it's safe
            guardFile.delete()
            CrashReporter.crumb("running OK")
        }
        // Periodic SRAM/auto-state save + rewind capture, only while the game is on screen.
        lifecycleScope.launch(safe) {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                withContext(Dispatchers.Default) {
                    var elapsed = 0L
                    val step = if (rewindEnabled) profile.rewindIntervalMs else 1_000L
                    while (isActive) {
                        delay(step)
                        if (rewindEnabled && !rewinding) captureRewind()
                        elapsed += step
                        if (elapsed >= 30_000) { elapsed = 0; saveSram(); saveAutoState() }
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
                // Stay well inside the Java heap: at most 1/8 of it (and never above 64 MB).
                val budget = minOf(Runtime.getRuntime().maxMemory() / 8, 64L shl 20)
                rewindMax = (budget / state.size).toInt().coerceIn(10, 180)
                CrashReporter.crumb("rewind: state=${state.size / 1024}KB max=$rewindMax")
            }
            rewindBuffer.addLast(state)
            while (rewindBuffer.size > rewindMax) rewindBuffer.removeFirst()
        }
    }

    private fun setRewinding(on: Boolean) {
        if (!coreReady) return
        rewinding = on
        if (!on) return
        lifecycleScope.launch(Dispatchers.Default + safe) {
            while (rewinding) {
                val state = synchronized(rewindBuffer) { rewindBuffer.removeLastOrNull() }
                if (state == null) { rewinding = false; break }
                retroView.unserializeState(state)
                delay(70)
            }
        }
    }

    private fun toggleFastForward() {
        if (!coreReady) return
        fastForward = !fastForward
        retroView.frameSpeed = if (fastForward) (if (profile.tier == Tier.LOW) 2 else 3) else 1
        CrashReporter.crumb("fast-forward=$fastForward")
    }

    /** Core calls happen only while the game is running (never while it is paused). */
    private fun saveSram() {
        if (!coreReady) return
        runCatching {
            val bytes = retroView.serializeSRAM()
            if (bytes.isEmpty() || bytes.contentEquals(lastSram)) return
            lastSram = bytes
            sramFile.writeBytes(bytes)
        }
    }

    /** Snapshot used by "auto resume" next time the game is opened. */
    private fun saveAutoState() {
        if (!coreReady || guardFile.exists()) return
        runCatching {
            val bytes = retroView.serializeState()
            if (bytes.isNotEmpty()) stateFile(this, game, AUTO_SLOT, wideCore).writeBytes(bytes)
        }
    }

    /**
     * Genesis Plus GX keeps every code it receives in a fixed table of 150 entries and writes past
     * its end when it is full (native crash). So: clear the table, then send ONLY enabled cheats,
     * never more than [CheatRepository.MAX_PARTS] code parts in total.
     */
    private suspend fun applyCheats() = withContext(Dispatchers.Default) {
        val enabled = cheats.load(game).filter { it.enabled }
        onEmulationThread { LibretroDroid.resetCheat() }
        var parts = 0
        var skipped = 0
        enabled.forEachIndexed { i, c ->
            val code = c.code.uppercase().replace(" ", "")
            val p = CheatRepository.parts(code)
            if (parts + p > CheatRepository.MAX_PARTS) { skipped++; return@forEachIndexed }
            parts += p
            runCatching { retroView.setCheat(i, true, code) }
        }
        CrashReporter.crumb("cheats applied: ${enabled.size - skipped} ($parts parts), skipped $skipped")
        if (skipped > 0) message = "تم تفعيل ${enabled.size - skipped} جيت • $skipped ما تفعّل لأن المحرك وصل الحد"
    }

    /** Runs [block] on the GL/emulation thread and waits for it (same as LibretroDroid's own calls). */
    private fun onEmulationThread(block: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        retroView.queueEvent { try { runCatching(block) } finally { latch.countDown() } }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::game.isInitialized) {
            guardFile.delete() // normal exit, not a crash
            CrashReporter.crumb("closed normally")
        }
    }

    // ---------- Menu ----------

    private var menuOpening = false

    /** Saves first (while the core is still running), then opens the pause menu. */
    private fun openMenu() {
        if (menuOpening) return
        menuOpening = true
        lifecycleScope.launch(safe) {
            withContext(Dispatchers.Default) { saveSram(); saveAutoState() }
            menuLauncher.launch(GameMenuActivity.intent(this@GameActivity, game))
            menuOpening = false
        }
    }

    private fun handleMenuResult(data: Intent) {
        if (data.getBooleanExtra(GameMenuActivity.RES_FILTER_CHANGED, false)) {
            retroView.shader = profile.shaderFor(settings.filter)
        }
        if (data.getBooleanExtra(GameMenuActivity.RES_ASPECT_CHANGED, false)) {
            val newAspect = settings.aspect
            if (usesWideCore(settings, game) != wideCore) {
                // Switching to/from the widescreen core needs the engine to restart.
                CrashReporter.crumb("restart for core change")
                lifecycleScope.launch(safe) {
                    withContext(Dispatchers.Default) { saveSram() }
                    finish(); start(this@GameActivity, game, File(romPath), resume = false)
                }
                return
            }
            aspect = newAspect
        }
        if (data.getBooleanExtra(GameMenuActivity.RES_IMAGE_CHANGED, false)) {
            CrashReporter.crumb("image options: color=${settings.colorMode} noFlicker=${settings.noSpriteLimit}")
            lifecycleScope.launch(Dispatchers.Default + safe) { retroView.updateVariables(*coreVariables()) }
            retroView.colorAdjust = settings.colorAdjust()
        }
        if (data.getBooleanExtra(GameMenuActivity.RES_CHEATS_CHANGED, false)) {
            lifecycleScope.launch(safe) {
                guardFile.writeText("cheats")
                CrashReporter.crumb("cheats changed")
                applyCheats(); message = "تم تحديث الجيتات"
                delay(5_000); guardFile.delete()
            }
        }
        val slot = data.getIntExtra(GameMenuActivity.RES_SLOT, 0)
        val action = data.getStringExtra(GameMenuActivity.RES_ACTION) ?: return
        CrashReporter.crumb("menu action=$action slot=$slot")
        when (action) {
            GameMenuActivity.ACTION_SAVE -> lifecycleScope.launch(Dispatchers.Default + safe) {
                val ok = runCatching { stateFile(this@GameActivity, game, slot, wideCore).writeBytes(retroView.serializeState()) }.isSuccess
                message = if (ok) "تم الحفظ في الخانة $slot" else "فشل الحفظ"
            }
            GameMenuActivity.ACTION_LOAD -> lifecycleScope.launch(Dispatchers.Default + safe) {
                val f = stateFile(this@GameActivity, game, slot, wideCore)
                val ok = f.exists() && runCatching { retroView.unserializeState(f.readBytes()) }.getOrDefault(false)
                synchronized(rewindBuffer) { rewindBuffer.clear() }
                message = if (ok) "تم التحميل من الخانة $slot" else "الخانة $slot فارغة"
            }
            GameMenuActivity.ACTION_RESET -> lifecycleScope.launch(Dispatchers.Default + safe) {
                retroView.reset(); synchronized(rewindBuffer) { rewindBuffer.clear() }
                stateFile(this@GameActivity, game, AUTO_SLOT, wideCore).delete()
            }
            GameMenuActivity.ACTION_TOUCH -> {
                val visibleNow = touchOverride ?: (settings.touchMode == TouchMode.ALWAYS ||
                    (settings.touchMode == TouchMode.AUTO && gamepadName == null))
                touchOverride = !visibleNow
            }
            GameMenuActivity.ACTION_QUIT -> finish() // state + SRAM were saved when the menu opened
        }
    }

    // ---------- Physical controllers ----------

    private val inputManager by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        padMap = settings.padMap
        refreshGamepads(announce = false)
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshGamepads(announce = true)
    override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepads(announce = true)
    override fun onInputDeviceChanged(deviceId: Int) = refreshGamepads(announce = false)

    private fun refreshGamepads(announce: Boolean) {
        val before = gamepadName
        gamepadName = InputDevice.getDeviceIds().asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { !it.isVirtual && isGamepad(it.sources) }?.name
        if (announce && before != gamepadName) {
            message = gamepadName?.let { "تم توصيل: $it 🎮" } ?: "تم فصل الجويستك"
            CrashReporter.crumb("gamepad=${gamepadName ?: "none"}")
        }
    }

    private fun has(source: Int, flag: Int) = (source and flag) == flag

    private fun isGamepad(source: Int) =
        has(source, InputDevice.SOURCE_GAMEPAD) || has(source, InputDevice.SOURCE_JOYSTICK)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::retroView.isInitialized && isGamepad(event.source)) {
            if (gamepadName == null) gamepadName = event.device?.name ?: "Gamepad"
            val port = ((event.device?.controllerNumber ?: 1) - 1).coerceAtLeast(0)

            // 1) Custom mapping chosen by the player
            padMap[event.keyCode]?.let { target ->
                if (target == PadTarget.MENU) {
                    if (event.action == KeyEvent.ACTION_UP) openMenu()
                } else if (coreReady && event.repeatCount == 0) {
                    retroView.sendKeyEvent(event.action, target.keyCode, port)
                }
                return true
            }
            // 2) Default: MODE/BACK opens the menu, everything else goes to the core
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) openMenu()
                return true
            }
            if (!coreReady) return true
            val handled = if (event.action == KeyEvent.ACTION_DOWN) retroView.onKeyDown(event.keyCode, event)
                          else retroView.onKeyUp(event.keyCode, event)
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::retroView.isInitialized && coreReady && isGamepad(event.source) && retroView.onGenericMotionEvent(event)) return true
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
