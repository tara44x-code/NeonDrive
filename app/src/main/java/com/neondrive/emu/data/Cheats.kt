package com.neondrive.emu.data

import android.content.Context
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
)

/**
 * Online cheats from the libretro-database (.cht files, Game Genie / Action Replay codes),
 * cached per game together with the user's on/off choices and custom codes.
 */
class CheatRepository(context: Context) {
    private val dir = File(context.filesDir, "cheats").apply { mkdirs() }

    private fun file(game: Game) = File(dir, "${game.crc}.json")

    fun load(game: Game): List<Cheat> = runCatching {
        val arr = JSONArray(file(game).readText())
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            Cheat(o.getString("d"), o.getString("c"), o.optBoolean("e"), o.optBoolean("u"))
        }
    }.getOrDefault(emptyList())

    fun save(game: Game, cheats: List<Cheat>) {
        val arr = JSONArray()
        cheats.forEach {
            arr.put(JSONObject().put("d", it.description).put("c", it.code).put("e", it.enabled).put("u", it.custom))
        }
        file(game).writeText(arr.toString())
    }

    /** Downloads cheats for [game]; keeps custom ones and previous toggles. Returns null if none online. */
    suspend fun fetchOnline(game: Game): List<Cheat>? = withContext(Dispatchers.IO) {
        val names = listOfNotNull(game.dbName, game.title).distinct()
        val text = names.firstNotNullOfOrNull { Net.getText(LibretroUrls.cheatUrl(game.system, it)) }
            ?: return@withContext null
        val previous = load(game)
        val enabledCodes = previous.filter { it.enabled }.map { it.code }.toSet()
        val online = parseCht(text).map { it.copy(enabled = it.code in enabledCodes) }
        val merged = online + previous.filter { it.custom }
        save(game, merged)
        merged
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
                Cheat(f["desc"] ?: code, code, enabled = f["enable"] == "true")
            }
        }
    }
}
