package com.neondrive.emu.data

import android.net.Uri

/** Consoles handled by the Genesis Plus GX core. [dbName] matches libretro-database folder names. */
enum class GameSystem(
    val label: String,
    val shortLabel: String,
    val dbName: String,
    val extensions: Set<String>,
) {
    GENESIS("Mega Drive / Genesis", "MD", "Sega - Mega Drive - Genesis", setOf("md", "gen", "bin", "smd", "68k")),
    MASTER_SYSTEM("Master System", "SMS", "Sega - Master System - Mark III", setOf("sms")),
    GAME_GEAR("Game Gear", "GG", "Sega - Game Gear", setOf("gg")),
    SG1000("SG-1000", "SG", "Sega - SG-1000", setOf("sg"));

    companion object {
        val allExtensions: Set<String> = entries.flatMap { it.extensions }.toSet()
        val archiveExtensions = setOf("zip")

        fun fromExtension(ext: String): GameSystem? =
            entries.firstOrNull { ext.lowercase() in it.extensions }
    }
}

object LibretroUrls {
    private const val DB_RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master"
    private const val THUMBS = "https://thumbnails.libretro.com"

    fun datUrl(system: GameSystem) = "$DB_RAW/metadat/no-intro/${Uri.encode(system.dbName)}.dat"

    fun cheatUrl(system: GameSystem, gameName: String) =
        "$DB_RAW/cht/${Uri.encode(system.dbName)}/${Uri.encode(gameName)}.cht"

    /**
     * Cover URLs to try in order: the exact edition on the thumbnail server, the same file on
     * GitHub (mirror), then other region editions of the same game.
     */
    fun coverCandidates(system: GameSystem, dbName: String?, fallbackTitle: String): List<String> {
        val base = TitleMatch.base(dbName ?: fallbackTitle)
        val names = buildList {
            dbName?.let { add(it) }
            listOf("(USA)", "(World)", "(USA, Europe)", "(Europe)", "(Japan)", "(Japan, USA)", "(USA, Europe, Korea)")
                .forEach { add("$base $it") }
        }.distinct()
        val urls = mutableListOf<String>()
        names.forEachIndexed { i, n ->
            urls += boxartUrl(system, n)
            if (i == 0) urls += githubBoxartUrl(system, n)
        }
        return urls.distinct()
    }

    private fun safeName(name: String) = name.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")

    fun githubBoxartUrl(system: GameSystem, gameName: String): String {
        val repo = system.dbName.replace(' ', '_')
        return "https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/Named_Boxarts/${Uri.encode(safeName(gameName))}.png"
    }

    /** libretro-thumbnails replaces these characters with '_' in file names. */
    fun boxartUrl(system: GameSystem, gameName: String): String {
        val safe = gameName.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")
        return "$THUMBS/${Uri.encode(system.dbName)}/Named_Boxarts/${Uri.encode(safe)}.png"
    }
}
