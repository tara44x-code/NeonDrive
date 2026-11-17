package com.neondrive.emu.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.neondrive.emu.data.Cheat
import com.neondrive.emu.data.CheatFetch
import com.neondrive.emu.data.TitleMatch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.neondrive.emu.data.CheatRepository
import com.neondrive.emu.data.Game
import com.neondrive.emu.data.Settings
import com.neondrive.emu.data.VideoFilter
import com.neondrive.emu.data.AspectMode
import com.neondrive.emu.ui.Neon
import com.neondrive.emu.ui.NeonChip
import com.neondrive.emu.ui.NeonPanel
import com.neondrive.emu.ui.NeonTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** In-game pause menu. Being a separate (translucent) activity pauses emulation automatically. */
class GameMenuActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_GAME = "game"
        const val RES_ACTION = "action"
        const val RES_SLOT = "slot"
        const val RES_CHEATS_CHANGED = "cheats"
        const val RES_FILTER_CHANGED = "filter"
        const val RES_ASPECT_CHANGED = "aspect"
        const val ACTION_SAVE = "save"
        const val ACTION_LOAD = "load"
        const val ACTION_RESET = "reset"
        const val ACTION_QUIT = "quit"
        const val ACTION_TOUCH = "touch"
        const val SLOTS = 4

        fun intent(context: Context, game: Game) =
            Intent(context, GameMenuActivity::class.java).putExtra(EXTRA_GAME, game.toJson().toString())
    }

    private lateinit var game: Game
    private lateinit var repo: CheatRepository
    private lateinit var settings: Settings
    private var cheatsChanged = false
    private var filterChanged = false
    private var aspectChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = Game.fromJson(JSONObject(intent.getStringExtra(EXTRA_GAME)!!))
        repo = CheatRepository(this)
        settings = Settings(this)
        setResultFor(null)

        setContent {
            NeonTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    var page by remember { mutableStateOf("main") }
                    BackHandler { if (page == "main") finish() else page = "main" }
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        NeonPanel(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.95f), glow = true) {
                            when (page) {
                                "main" -> MainPage(onCheats = { page = "cheats" })
                                "cheats" -> CheatsPage(onBack = { page = "main" })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setResultFor(action: String?, slot: Int = 0) {
        setResult(RESULT_OK, Intent().apply {
            action?.let { putExtra(RES_ACTION, it) }
            putExtra(RES_SLOT, slot)
            putExtra(RES_CHEATS_CHANGED, cheatsChanged)
            putExtra(RES_FILTER_CHANGED, filterChanged)
            putExtra(RES_ASPECT_CHANGED, aspectChanged)
        })
    }

    private fun finishWith(action: String?, slot: Int = 0) {
        setResultFor(action, slot); finish()
    }

    @Composable
    private fun MainPage(onCheats: () -> Unit) {
        var filter by remember { mutableStateOf(settings.filter) }
        var aspect by remember { mutableStateOf(settings.aspect) }
        val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(game.title, style = Neon.glowText.copy(fontSize = 20.sp), maxLines = 1)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuButton("استئناف", Icons.Default.PlayArrow, Modifier.weight(1f)) { finishWith(null) }
                MenuButton("الجيتات", Icons.Default.AutoFixHigh, Modifier.weight(1f), onCheats)
            }

            Text("الحفظ والتحميل", color = Neon.Cyan, fontWeight = FontWeight.Bold)
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (slot in 1..SLOTS) {
                    val f = GameActivity.stateFile(this@GameMenuActivity, game, slot)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "خانة $slot" + if (f.exists()) " • ${fmt.format(Date(f.lastModified()))}" else " • فارغة",
                            color = if (f.exists()) Neon.Text else Neon.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                        OutlinedButton({ finishWith(ACTION_SAVE, slot) }) { Text("حفظ", color = Neon.Cyan) }
                        Button(
                            { finishWith(ACTION_LOAD, slot) }, enabled = f.exists(),
                            colors = ButtonDefaults.buttonColors(containerColor = Neon.Magenta),
                        ) { Text("تحميل", color = Neon.Bg) }
                    }
                }
            }

            Text("نسبة العرض", color = Neon.Cyan, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectMode.entries.forEach { a ->
                    NeonChip(a.label, a == aspect) {
                        aspect = a; settings.aspect = a; aspectChanged = true; setResultFor(null)
                    }
                }
            }

            Text("فلتر الصورة", color = Neon.Cyan, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoFilter.entries.forEach { f ->
                    NeonChip(f.label, f == filter) {
                        filter = f; settings.filter = f; filterChanged = true; setResultFor(null)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuButton("أزرار اللمس", Icons.Default.TouchApp, Modifier.weight(1f)) { finishWith(ACTION_TOUCH) }
                MenuButton("إعادة", Icons.Default.RestartAlt, Modifier.weight(1f)) { finishWith(ACTION_RESET) }
                MenuButton("خروج", Icons.AutoMirrored.Filled.ExitToApp, Modifier.weight(1f)) { finishWith(ACTION_QUIT) }
            }
        }
    }

    @Composable
    private fun CheatsPage(onBack: () -> Unit) {
        var cheats by remember { mutableStateOf(repo.load(game)) }
        var loading by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        var showAdd by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf(game.headerName?.let { TitleMatch.base(it) } ?: game.fileTitle) }
        var results by remember { mutableStateOf<List<String>>(emptyList()) }
        var filterText by remember { mutableStateOf("") }

        fun update(list: List<Cheat>) {
            cheats = list; repo.save(game, list); cheatsChanged = true; setResultFor(null)
        }

        fun toggle(i: Int, on: Boolean) {
            val c = cheats[i]
            if (on) {
                val used = cheats.filter { it.enabled }.sumOf { CheatRepository.parts(it.code) }
                if (used + CheatRepository.parts(c.code) > CheatRepository.MAX_PARTS) {
                    status = "وصلت الحد: المحرك يتحمل ${CheatRepository.MAX_PARTS} كود بنفس الوقت. طفّي جيت ثاني أول."
                    return
                }
            }
            update(cheats.toMutableList().also { it[i] = c.copy(enabled = on) })
        }

        fun showFetched(r: CheatFetch) {
            cheats = r.cheats; cheatsChanged = true; setResultFor(null)
            status = "لكينا ${r.cheats.size} جيت من ${r.files.size} ملف"
        }

        fun download() {
            loading = true; status = null
            lifecycleScope.launch {
                val result = runCatching { repo.fetchOnline(game) }
                loading = false
                result.onSuccess { r ->
                    if (r == null) { status = "ما تعرّفت على اللعبة تلقائياً، اكتب اسمها وابحث 👇"; showSearch = true }
                    else showFetched(r)
                }.onFailure { status = "تعذر البحث: ${it.message}" }
            }
        }

        fun runSearch() {
            loading = true
            lifecycleScope.launch {
                results = runCatching { repo.search(game.system, query) }.getOrDefault(emptyList())
                loading = false
                if (results.isEmpty()) status = "ماكو نتائج لـ \"$query\""
            }
        }

        fun pick(name: String) {
            loading = true
            lifecycleScope.launch {
                val r = runCatching { repo.fetchFiles(game, listOf(name)) }.getOrNull()
                loading = false
                if (r == null) status = "تعذر تحميل الملف" else { showFetched(r); showSearch = false; results = emptyList() }
            }
        }

        LaunchedEffect(Unit) { if (cheats.isEmpty()) download() }


        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Neon.Cyan) }
                Text("الجيتات", style = Neon.glowText.copy(fontSize = 18.sp), modifier = Modifier.weight(1f))
                IconButton(onClick = ::download, enabled = !loading) { Icon(Icons.Default.CloudDownload, "تحديث", tint = Neon.Cyan) }
                IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Default.Search, "بحث يدوي", tint = Neon.Cyan) }
                IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "إضافة", tint = Neon.Magenta) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Neon.Magenta, trackColor = Neon.Surface)
            // One compact line: result + how much of the engine limit is used
            val usedParts = cheats.filter { it.enabled }.sumOf { CheatRepository.parts(it.code) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status ?: "${cheats.size} جيت", color = Neon.TextDim, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (cheats.isNotEmpty()) Text(
                    "مفعّل ${cheats.count { it.enabled }} • $usedParts/${CheatRepository.MAX_PARTS}",
                    color = if (usedParts > CheatRepository.MAX_PARTS * 0.8) Neon.Magenta else Neon.Cyan, fontSize = 11.sp,
                )
            }
            if (cheats.any { it.enabled }) {
                OutlinedButton(
                    onClick = {
                        update(cheats.map { it.copy(enabled = false) })
                        status = "تم إطفاء كل الجيتات"
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Neon.Magenta),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.Block, null, tint = Neon.Magenta, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("إطفاء كل الجيتات", color = Neon.Magenta, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            if (cheats.size > 8) {
                OutlinedTextField(
                    filterText, { filterText = it }, Modifier.fillMaxWidth().heightIn(min = 44.dp), singleLine = true,
                    placeholder = { Text("فلترة الجيتات… (مثلاً: Lives أو Energy)", fontSize = 12.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                )
            }

            if (showSearch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        query, { query = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("اسم اللعبة بالإنجليزي") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    )
                    IconButton(onClick = ::runSearch) { Icon(Icons.Default.Search, "بحث", tint = Neon.Cyan) }
                }
                results.take(8).forEach { name ->
                    Text(
                        name, color = Neon.Cyan, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { pick(name) }.padding(vertical = 6.dp),
                    )
                }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val shown = cheats.withIndex().filter { (_, c) ->
                    filterText.isBlank() || c.description.contains(filterText, true) || c.code.contains(filterText, true)
                }
                items(shown, key = { it.index }) { (i, c) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (c.enabled) Neon.Cyan.copy(alpha = 0.10f) else Neon.Bg.copy(alpha = 0f))
                            .clickable { toggle(i, !c.enabled) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.description, color = if (c.enabled) Neon.Cyan else Neon.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(c.code + if (c.custom) " • يدوي" else if (c.source.isNotEmpty()) " • ${c.source}" else "", color = Neon.TextDim, fontSize = 11.sp, maxLines = 1)
                        }
                        Switch(
                            c.enabled, { on -> toggle(i, on) }, Modifier.scale(0.8f),
                            colors = SwitchDefaults.colors(checkedTrackColor = Neon.Cyan, checkedThumbColor = Neon.Bg),
                        )
                    }
                }
            }
        }

        if (showAdd) {
            var desc by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAdd = false },
                containerColor = Neon.SurfaceHi,
                title = { Text("كود جديد") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(desc, { desc = it }, label = { Text("الوصف") }, singleLine = true)
                        OutlinedTextField(code, { code = it.uppercase() }, label = { Text("الكود (Game Genie / Action Replay)") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(enabled = code.isNotBlank(), onClick = {
                        update(cheats + Cheat(desc.ifBlank { code }, code.trim(), enabled = true, custom = true))
                        showAdd = false
                    }) { Text("إضافة", color = Neon.Cyan) }
                },
                dismissButton = { TextButton({ showAdd = false }) { Text("إلغاء") } },
            )
        }
    }

    @Composable
    private fun MenuButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Surface(
            onClick = onClick, modifier = modifier.height(56.dp), shape = RoundedCornerShape(14.dp),
            color = Neon.Surface, border = androidx.compose.foundation.BorderStroke(1.5.dp, Neon.edge),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(icon, null, tint = Neon.Cyan)
                Spacer(Modifier.width(8.dp))
                Text(text, color = Neon.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}
