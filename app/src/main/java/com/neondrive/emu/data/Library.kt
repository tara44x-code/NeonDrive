package com.neondrive.emu.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

data class Game(
    val uri: String,          // SAF document uri of the file (or archive)
    val fileName: String,
    val innerName: String?,   // ROM name inside a .zip, if any
    val system: GameSystem,
    val crc: String,
    val dbName: String?,      // official No-Intro name, if identified
    val lastPlayed: Long = 0L,
    val headerName: String? = null, // name stored inside the cartridge header
) {
    /** File name without extension / tags, e.g. "Earthworm Jim (U) [!].bin" -> "Earthworm Jim" */
    val fileTitle: String get() = TitleMatch.base(innerName ?: fileName)

    val title: String get() = dbName ?: fileTitle
    val romExtension: String get() = (innerName ?: fileName).substringAfterLast('.').lowercase()
    val coverUrl: String? get() = dbName?.let { LibretroUrls.boxartUrl(system, it) }

    fun toJson() = JSONObject().apply {
        put("uri", uri); put("fileName", fileName); put("innerName", innerName ?: "")
        put("system", system.name); put("crc", crc); put("dbName", dbName ?: "")
        put("lastPlayed", lastPlayed); put("headerName", headerName ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject) = Game(
            uri = o.getString("uri"),
            fileName = o.getString("fileName"),
            innerName = o.optString("innerName").ifEmpty { null },
            system = GameSystem.valueOf(o.getString("system")),
            crc = o.getString("crc"),
            dbName = o.optString("dbName").ifEmpty { null },
            lastPlayed = o.optLong("lastPlayed"),
            headerName = o.optString("headerName").ifEmpty { null },
        )
    }
}

class LibraryRepository(private val context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val db = GameDatabase(context)

    fun load(): List<Game> = runCatching {
        val arr = JSONArray(file.readText())
        List(arr.length()) { Game.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(games: List<Game>) {
        file.writeText(JSONArray().apply { games.forEach { put(it.toJson()) } }.toString())
    }

    fun markPlayed(game: Game) {
        save(load().map { if (it.uri == game.uri) it.copy(lastPlayed = System.currentTimeMillis()) else it })
    }

    /** Recursively scans a SAF folder. Already-known files are reused, so rescans are fast. */
    suspend fun scan(treeUri: Uri, onProgress: (String) -> Unit): List<Game> = withContext(Dispatchers.IO) {
        val known = load().associateBy { it.uri }
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext known.values.toList()
        val found = mutableListOf<Game>()

        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 6) return
            for (f in dir.listFiles()) {
                if (f.isDirectory) { walk(f, depth + 1); continue }
                val name = f.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in GameSystem.allExtensions && ext !in GameSystem.archiveExtensions) continue
                val uri = f.uri.toString()
                val cached = known[uri]
                if (cached != null && (cached.dbName != null)) { found += cached; continue }
                onProgress(name)
                runCatching { identify(f.uri, name, ext) }.getOrNull()?.let {
                    found += it.copy(lastPlayed = cached?.lastPlayed ?: 0L)
                }
            }
        }
        walk(root, 0)

        // Keep games from other folders the user added earlier.
        val otherFolders = known.values.filter { g -> found.none { it.uri == g.uri } && !g.uri.startsWith(treeUriPrefix(treeUri)) }
        val result = (found + otherFolders).sortedBy { it.title.lowercase() }
        save(result)
        result
    }

    private fun treeUriPrefix(treeUri: Uri) = "$treeUri/document/"

    private fun identify(uri: Uri, name: String, ext: String): Game? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            if (ext == "zip") {
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: return null
                        val innerExt = e.name.substringAfterLast('.', "").lowercase()
                        val system = GameSystem.fromExtension(innerExt) ?: continue
                        val inner = e.name.substringAfterLast('/')
                        return build(uri, name, inner, system, zip)
                    }
                }
            }
            val system = GameSystem.fromExtension(ext) ?: return null
            return build(uri, name, null, system, stream)
        }
    }

    private fun build(uri: Uri, fileName: String, inner: String?, system: GameSystem, stream: InputStream): Game {
        val (crc, head) = crcAndHeader(stream)
        val header = RomHeader.parse(system, head)
        val shownName = inner ?: fileName
        val hints = listOfNotNull(TitleMatch.base(shownName), header?.name)
        val dbName = db.identify(system, crc, header?.serial, hints, TitleMatch.tags(shownName))
        return Game(uri.toString(), fileName, inner, system, crc, dbName, headerName = header?.name)
    }

    /** CRC32 of the whole file + its first 512 bytes (cartridge header). */
    private fun crcAndHeader(stream: InputStream): Pair<String, ByteArray> {
        val crc = CRC32(); val buf = ByteArray(64 * 1024)
        val head = java.io.ByteArrayOutputStream(512)
        while (true) {
            val n = stream.read(buf); if (n < 0) break
            crc.update(buf, 0, n)
            if (head.size() < 512) head.write(buf, 0, minOf(n, 512 - head.size()))
        }
        return "%08X".format(crc.value) to head.toByteArray()
    }

    /** Copies (or extracts) the ROM to a cache file the core can open by path. */
    suspend fun prepareRom(game: Game): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "roms").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the current game
        val out = File(dir, "${game.crc}.${game.romExtension}")
        val input = context.contentResolver.openInputStream(Uri.parse(game.uri))
            ?: error("Cannot open ${game.fileName}")
        input.use { stream ->
            if (game.innerName != null) {
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: error("ROM not found in archive")
                        if (e.name.substringAfterLast('/') == game.innerName) {
                            out.outputStream().use { zip.copyTo(it) }; break
                        }
                    }
                }
            } else {
                out.outputStream().use { stream.copyTo(it) }
            }
        }
        out
    }
}
