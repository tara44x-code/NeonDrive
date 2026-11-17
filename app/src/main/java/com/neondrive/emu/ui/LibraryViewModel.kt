package com.neondrive.emu.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neondrive.emu.data.Game
import com.neondrive.emu.data.GameSystem
import com.neondrive.emu.data.LibraryRepository
import com.neondrive.emu.data.Settings
import com.neondrive.emu.device.DeviceProfile
import com.neondrive.emu.device.DeviceProfiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LibraryRepository(app)
    val settings = Settings(app)

    var games by mutableStateOf<List<Game>>(emptyList()); private set
    var scanning by mutableStateOf<String?>(null); private set
    var loadingGame by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    var profile by mutableStateOf<DeviceProfile?>(null); private set
    var query by mutableStateOf("")
    var systemFilter by mutableStateOf<GameSystem?>(null)

    val visibleGames: List<Game>
        get() = games
            .filter { systemFilter == null || it.system == systemFilter }
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

    val recent: List<Game> get() = games.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(8)

    init {
        viewModelScope.launch {
            games = withContext(Dispatchers.IO) { repo.load() }
            profile = withContext(Dispatchers.Default) { DeviceProfiler.get(getApplication()) }
        }
    }

    fun addFolder(uri: Uri) {
        val app = getApplication<Application>()
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.romFolders = settings.romFolders + uri.toString()
        scan(listOf(uri), quiet = false)
    }

    /** Manual refresh: also re-identifies games that weren't recognized before. */
    fun rescanAll() = scan(settings.romFolders.map(Uri::parse), quiet = false)

    /** Runs silently whenever the app comes to the foreground: picks up new / deleted games. */
    fun autoScan() = scan(settings.romFolders.map(Uri::parse), quiet = true)

    private var busy = false

    private fun scan(folders: List<Uri>, quiet: Boolean = false) {
        if (busy || folders.isEmpty()) return
        busy = true
        viewModelScope.launch {
            if (!quiet) scanning = "…"
            val before = withContext(Dispatchers.IO) { repo.load().map { it.uri }.toSet() }
            try {
                folders.forEach { f ->
                    games = repo.scan(f, reidentify = !quiet) { name -> scanning = name }
                }
                val added = games.count { it.uri !in before }
                if (quiet && added > 0) error = "تمت إضافة $added لعبة جديدة 🎮"
            } catch (e: Exception) {
                if (!quiet) error = "تعذر فحص المجلد: ${e.message}"
            } finally {
                scanning = null
                busy = false
            }
        }
    }

    /** Game waiting for the "continue / start over" choice. */
    var resumePrompt by mutableStateOf<Pair<Game, Long>?>(null)

    /** Opens a game; if it has a saved resume point, asks first. */
    fun play(game: Game, onReady: (Game, File, Boolean) -> Unit) {
        val auto = com.neondrive.emu.game.GameActivity.stateFile(getApplication(), game, com.neondrive.emu.game.GameActivity.AUTO_SLOT)
        if (settings.autoResume && auto.exists() && auto.length() > 0) resumePrompt = game to auto.lastModified()
        else launch(game, resume = false, onReady = onReady)
    }

    fun launch(game: Game, resume: Boolean, onReady: (Game, File, Boolean) -> Unit) {
        if (loadingGame != null) return
        viewModelScope.launch {
            loadingGame = game.title
            try {
                val rom = repo.prepareRom(game)
                withContext(Dispatchers.IO) { repo.markPlayed(game) }
                games = games.map { if (it.uri == game.uri) it.copy(lastPlayed = System.currentTimeMillis()) else it }
                onReady(game, rom, resume)
            } catch (e: Exception) {
                error = "تعذر فتح اللعبة: ${e.message}"
            } finally {
                loadingGame = null
            }
        }
    }
}
