package com.neondrive.emu.data

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Game database + cheat files bundled inside the APK (see scripts/fetch_db.sh),
 * so identification and cheats work with no internet. Online updates overwrite the local copy.
 */
object OfflineDb {
    private fun dbDir(c: Context) = File(c.filesDir, "db").apply { mkdirs() }
    private fun cheatRoot(c: Context) = File(c.filesDir, "cheatdb")

    fun bundledVersion(c: Context): String? =
        runCatching { c.assets.open("db/version.txt").bufferedReader().use { it.readText().trim() } }.getOrNull()

    /** DAT file for [system]: the downloaded one if present, otherwise copied from the APK. */
    @Synchronized
    fun dat(c: Context, system: GameSystem): File {
        val f = File(dbDir(c), "${system.name}.dat")
        if (!f.exists()) runCatching {
            c.assets.open("db/${system.name}.dat").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return f
    }

    /** Extracts the bundled cheats once (and again after an app update brings a newer pack). */
    @Synchronized
    fun ensureCheats(c: Context) {
        val root = cheatRoot(c)
        val marker = File(root, ".version")
        val bundled = bundledVersion(c) ?: return
        if (marker.exists() && marker.readText() == bundled) return
        root.deleteRecursively(); root.mkdirs()
        runCatching {
            ZipInputStream(c.assets.open("cheats.zip").buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || !e.name.endsWith(".cht")) continue
                    val out = File(root, e.name.removePrefix("./"))
                    if (!out.canonicalPath.startsWith(root.canonicalPath)) continue // zip-slip guard
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
            marker.writeText(bundled)
        }
    }

    fun cheatNames(c: Context, system: GameSystem): List<String> {
        ensureCheats(c)
        return File(cheatRoot(c), system.name).listFiles()
            ?.filter { it.name.endsWith(".cht") }?.map { it.name.removeSuffix(".cht") }.orEmpty()
    }

    fun cheatText(c: Context, system: GameSystem, name: String): String? {
        ensureCheats(c)
        return File(cheatRoot(c), "${system.name}/$name.cht").takeIf { it.exists() }?.readText()
    }

    fun cheatFileCount(c: Context): Int {
        ensureCheats(c)
        return GameSystem.entries.sumOf { File(cheatRoot(c), it.name).listFiles()?.size ?: 0 }
    }
}
