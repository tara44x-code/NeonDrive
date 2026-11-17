package com.neondrive.emu.data

import android.content.Context

enum class VideoFilter(val label: String) {
    AUTO("تلقائي حسب الجهاز"),
    PIXEL("بكسل أصلي"),
    SHARP("حاد ناعم"),
    SMOOTH("تنعيم ذكي (CUT2)"),
    ULTRA("دقة فائقة (CUT3)"),
    CRT("شاشة CRT"),
    LCD("شاشة LCD"),
}

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var romFolders: Set<String>
        get() = p.getStringSet("folders", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("folders", v).apply()

    var filter: VideoFilter
        get() = runCatching { VideoFilter.valueOf(p.getString("filter", "AUTO")!!) }.getOrDefault(VideoFilter.AUTO)
        set(v) = p.edit().putString("filter", v.name).apply()

    var widescreen: Boolean
        get() = p.getBoolean("wide", false)
        set(v) = p.edit().putBoolean("wide", v).apply()

    var sixButtonPad: Boolean
        get() = p.getBoolean("six", true)
        set(v) = p.edit().putBoolean("six", v).apply()

    /** null = follow device profile */
    var rewind: Boolean?
        get() = if (p.contains("rewind")) p.getBoolean("rewind", true) else null
        set(v) { if (v == null) p.edit().remove("rewind").apply() else p.edit().putBoolean("rewind", v).apply() }

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    var controlsOpacity: Float
        get() = p.getFloat("opacity", 0.75f)
        set(v) = p.edit().putFloat("opacity", v).apply()

    var controlsScale: Float
        get() = p.getFloat("scale", 1f)
        set(v) = p.edit().putFloat("scale", v).apply()
}
