package com.neondrive.emu.data

import android.content.Context
import java.io.File

/**
 * Identifies ROMs by CRC32 using the No-Intro DAT files from libretro-database.
 * Each DAT is downloaded once (~1 MB) and cached; lookups work offline afterwards.
 */
class GameDatabase(context: Context) {
    private val dir = File(context.filesDir, "db")
    private val cache = mutableMapOf<GameSystem, Map<String, String>>()

    // game ( name "Title (Region)" ... rom ( name "..." size N crc ABCD1234 ...
    private val entry = Regex("""game \(\s*name "([^"]+)"[\s\S]*?\bcrc ([0-9A-Fa-f]{8})""")

    @Synchronized
    fun lookup(system: GameSystem, crc: String): String? = table(system)[crc.uppercase()]

    private fun table(system: GameSystem): Map<String, String> = cache.getOrPut(system) {
        val file = File(dir, "${system.name}.dat")
        if (!file.exists()) {
            runCatching { Net.download(LibretroUrls.datUrl(system), file) }
        }
        if (!file.exists()) return emptyMap() // offline: fall back to file names, retry next scan
        entry.findAll(file.readText()).associate { it.groupValues[2].uppercase() to it.groupValues[1] }
    }
}
