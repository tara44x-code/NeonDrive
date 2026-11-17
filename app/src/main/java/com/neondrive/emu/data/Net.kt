package com.neondrive.emu.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    /** Returns the body, or null on 404. Throws on other network errors. */
    fun getText(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "NeonDrive-Emulator")
        }
        try {
            return when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("HTTP ${conn.responseCode} for $url")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads to [target] atomically. Returns false on 404. */
    fun download(url: String, target: File): Boolean {
        val text = getText(url) ?: return false
        val tmp = File(target.parentFile, target.name + ".tmp")
        target.parentFile?.mkdirs()
        tmp.writeText(text)
        return tmp.renameTo(target)
    }
}
