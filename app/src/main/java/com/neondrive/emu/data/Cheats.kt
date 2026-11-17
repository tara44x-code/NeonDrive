package com.neondrive.emu.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Cheat(
    val description: String,
    val code: String,
    val enabled: Boolean = false,
    val custom: Boolean = false,
    val source: String = "",   // e.g. "USA", "Action Replay", "Rumbles"
)

/** Result of an online lookup: which cheat files were matched and the merged list. */
data class CheatFetch(val files: List<String>, val cheats: List<Cheat>)

/**
 * Online cheats from libretro-database (.cht, Game Genie / Action Replay).
 * Finding the right file is "smart": the full list of cheat files per console is cached, then matched
 * against the No-Intro name, the name inside the cartridge header and the file name, with fuzzy matching.
 * All variants of a game are merged (e.g. "(USA)" + "(Action Replay)" + "(Rumbles)").
 */
class CheatRepository(context: Context) {
    private val dir = File(context.filesDir, "cheats").apply { mkdirs() }
    private val indexDir = File(context.filesDir, "db").apply { mkdirs() }

    private fun file(game: Game) = File(dir, "${game.crc}.json")

    fun load(game: Game): List<Cheat> = runCatching {
        val arr = JSONArray(file(game).readText())
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            Cheat(o.getString("d"), o.getString("c"), o.optBoolean("e"), o.optBoolean("u"), o.optString("s"))
        }
    }.getOrDefault(emptyList())

    fun save(game: Game, cheats: List<Cheat>) {
        val arr = JSONArray()
        cheats.forEach {
            arr.put(JSONObject().put("d", it.description).put("c", it.code).put("e", it.enabled).put("u", it.custom).put("s", it.source))
        }
        file(game).writeText(arr.toString())
    }

    /** All cheat file names (without .cht) for a console; cached for 7 days. */
    fun index(system: GameSystem): List<String> {
        val f = File(indexDir, "${system.name}.cht.idx")
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000
        if (stale) runCatching {
            val url = "https://api.github.com/repos/libretro/libretro-database/git/trees/master:cht%2F${Uri.encode(system.dbName)}"
            val tree = JSONObject(Net.getText(url) ?: return@runCatching).getJSONArray("tree")
            val names = (0 until tree.length()).map { tree.getJSONObject(it).getString("path") }
                .filter { it.endsWith(".cht") }.map { it.removeSuffix(".cht") }
            if (names.isNotEmpty()) f.writeText(names.joinToString("\n"))
        }
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    /** Automatic lookup. Returns null if nothing matched. */
    suspend fun fetchOnline(game: Game): CheatFetch? = withContext(Dispatchers.IO) {
        val hints = listOfNotNull(game.dbName, game.headerName, game.fileTitle)
        val prefer = TitleMatch.tags(game.dbName ?: game.fileName)
        val index = index(game.system)
        val files = if (index.isNotEmpty()) TitleMatch.bestMatches(hints, index, prefer).take(6)
                    else hints.distinct() // index unavailable (rate limit): try names directly
        downloadAndMerge(game, files)
    }

    /** Manual search inside the cheat index (for the search box). */
    suspend fun search(system: GameSystem, query: String): List<String> = withContext(Dispatchers.IO) {
        val q = TitleMatch.norm(query)
        if (q.length < 2) return@withContext emptyList()
        index(system)
            .map { it to TitleMatch.norm(it) }
            .map { (name, n) -> name to if (n.contains(q)) 1.0 + q.length / 100.0 else TitleMatch.similarity(n, q) }
            .filter { it.second >= 0.55 }
            .sortedByDescending { it.second }
            .take(30).map { it.first }
    }

    /** Downloads specific cheat files picked by the user and merges them. */
    suspend fun fetchFiles(game: Game, files: List<String>): CheatFetch? =
        withContext(Dispatchers.IO) { downloadAndMerge(game, files) }

    private fun downloadAndMerge(game: Game, files: List<String>): CheatFetch? {
        val found = mutableListOf<String>()
        val online = mutableListOf<Cheat>()
        val seen = mutableSetOf<String>()
        for (name in files) {
            val text = Net.getText(LibretroUrls.cheatUrl(game.system, name)) ?: continue
            found += name
            val label = Regex("""\(([^)]*)\)""").findAll(name).joinToString(" ") { it.groupValues[1] }
            parseCht(text).forEach { c ->
                if (seen.add(c.code.uppercase().replace(" ", ""))) online += c.copy(source = label)
            }
        }
        if (found.isEmpty()) return null
        val previous = load(game)
        val enabled = previous.filter { it.enabled }.map { it.code }.toSet()
        val merged = online.map { it.copy(enabled = it.code in enabled) } + previous.filter { it.custom }
        save(game, merged)
        return CheatFetch(found, merged)
    }

    companion object {
        /** Parses RetroArch .cht: cheatN_desc = "...", cheatN_code = "...", cheatN_enable = false */
        fun parseCht(text: String): List<Cheat> {
            val kv = Regex("""^\s*cheat(\d+)_(desc|code|enable)\s*=\s*"?(.*?)"?\s*$""")
            val map = sortedMapOf<Int, MutableMap<String, String>>()
            text.lineSequence().forEach { line ->
                kv.find(line)?.let { m ->
                    map.getOrPut(m.groupValues[1].toInt()) { mutableMapOf() }[m.groupValues[2]] = m.groupValues[3]
                }
            }
            return map.values.mapNotNull { f ->
                val code = f["code"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Cheat(f["desc"]?.takeIf { it.isNotBlank() } ?: code, code, enabled = false)
            }
        }
    }
}
