package com.neondrive.emu.data

import android.content.Context
import java.io.File

/**
 * Identifies ROMs using the No-Intro DAT files from libretro-database, in three steps:
 *  1. exact CRC32 match,
 *  2. serial number read from the cartridge header (works for bad dumps, hacks, other revisions),
 *  3. fuzzy title match on the header name / file name.
 * Each DAT (~1 MB) is downloaded once and cached; lookups work offline afterwards.
 */
class GameDatabase(context: Context) {
    private val dir = File(context.filesDir, "db")
    private val cache = mutableMapOf<GameSystem, Table>()

    private class Table(val byCrc: Map<String, String>, val bySerial: Map<String, String>, val names: List<String>)

    private val block = Regex("""game \(\s*name "([^"]+)"(.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
    private val crcRe = Regex("""\bcrc ([0-9A-Fa-f]{8})""")
    private val serialRe = Regex("""serial "([^"]+)"""")

    @Synchronized
    fun identify(system: GameSystem, crc: String, serial: String?, titleHints: List<String>, regionHints: Set<String>): String? {
        val t = table(system)
        t.byCrc[crc.uppercase()]?.let { return it }
        serial?.let { s -> t.bySerial[s]?.let { return it } }
        return TitleMatch.bestMatches(titleHints, t.names, regionHints, threshold = 0.88).firstOrNull()
    }

    private fun table(system: GameSystem): Table = cache[system] ?: run {
        val file = File(dir, "${system.name}.dat")
        if (!file.exists()) runCatching { Net.download(LibretroUrls.datUrl(system), file) }
        if (!file.exists()) return Table(emptyMap(), emptyMap(), emptyList()) // offline: retry on next scan

        val byCrc = HashMap<String, String>(); val bySerial = HashMap<String, String>(); val names = ArrayList<String>()
        block.findAll(file.readText()).forEach { m ->
            val name = m.groupValues[1]; val body = m.groupValues[2]
            names += name
            crcRe.findAll(body).forEach { byCrc[it.groupValues[1].uppercase()] = name }
            serialRe.find(body)?.groupValues?.get(1)?.split(',')?.forEach { s ->
                RomHeader.normSerial(s)?.let { bySerial.putIfAbsent(it, name) }
            }
        }
        Table(byCrc, bySerial, names).also { cache[system] = it }
    }
}

/** Reads the Mega Drive cartridge header ($100-$1FF). */
object RomHeader {
    class Info(val serial: String?, val name: String?)

    fun parse(system: GameSystem, head: ByteArray): Info? {
        if (system != GameSystem.GENESIS || head.size < 0x190) return null
        fun str(off: Int, len: Int) = String(head, off, len, Charsets.ISO_8859_1).replace(Regex("""\s+"""), " ").trim()
        if (!str(0x100, 16).contains("SEGA")) return null
        val overseas = str(0x150, 48); val domestic = str(0x120, 48)
        val name = listOf(overseas, domestic).firstOrNull { n -> n.count { it.isLetter() } >= 3 }
        return Info(normSerial(str(0x180, 14)), name)
    }

    /** "GM T-50126 -00" / "T-50126-00" -> "T50126" */
    fun normSerial(raw: String): String? {
        val s = raw.uppercase().trim().removePrefix("GM").trim()
        val m = Regex("""([A-Z]{1,4})?[- ]?(\d{4,6})""").find(s) ?: return null
        return m.groupValues[1] + m.groupValues[2]
    }
}
