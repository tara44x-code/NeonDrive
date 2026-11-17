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

    /** libretro-thumbnails replaces these characters with '_' in file names. */
    fun boxartUrl(system: GameSystem, gameName: String): String {
        val safe = gameName.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")
        return "$THUMBS/${Uri.encode(system.dbName)}/Named_Boxarts/${Uri.encode(safe)}.png"
    }
}
