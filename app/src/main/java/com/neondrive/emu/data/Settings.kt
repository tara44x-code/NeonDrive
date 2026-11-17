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

/** How the picture fills the phone screen. [ratio] = forced display ratio (null = engine decides). */
enum class AspectMode(val label: String, val ratio: Float?) {
    ORIGINAL("أصلي", null),
    WIDE_NATIVE("عريض حقيقي 16:9", null),   // Genesis Plus GX Wide: shows more of the level (Mega Drive)
    STRETCH_16_9("تمدد 16:9", 16f / 9f),
    STRETCH_16_10("16:10", 16f / 10f),
    STRETCH_3_2("3:2", 3f / 2f),
    FULL("ملء الشاشة", null),
}

enum class TouchMode(val label: String) {
    AUTO("تلقائي (تختفي مع الجويستك)"),
    ALWAYS("ظاهرة دائماً"),
    HIDDEN("مخفية دائماً"),
}

/** Targets a physical controller button can be mapped to. [keyCode] is what we send to the core. */
enum class PadTarget(val label: String, val keyCode: Int) {
    A("A", 99), B("B", 96), C("C", 97),               // KEYCODE_BUTTON_X / _A / _B (see SegaKeys)
    X("X", 102), Y("Y", 100), Z("Z", 103),            // KEYCODE_BUTTON_L1 / _Y / _R1
    START("START", 108), MODE("MODE", 109),           // KEYCODE_BUTTON_START / _SELECT
    MENU("القائمة", -1),
}

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var romFolders: Set<String>
        get() = p.getStringSet("folders", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("folders", v).apply()

    var filter: VideoFilter
        get() = runCatching { VideoFilter.valueOf(p.getString("filter", "AUTO")!!) }.getOrDefault(VideoFilter.AUTO)
        set(v) = p.edit().putString("filter", v.name).apply()

    var aspect: AspectMode
        get() = runCatching { AspectMode.valueOf(p.getString("aspect", null)!!) }
            .getOrElse { if (p.getBoolean("wide", false)) AspectMode.WIDE_NATIVE else AspectMode.ORIGINAL }
        set(v) = p.edit().putString("aspect", v.name).apply()

    var touchMode: TouchMode
        get() = runCatching { TouchMode.valueOf(p.getString("touchMode", "AUTO")!!) }.getOrDefault(TouchMode.AUTO)
        set(v) = p.edit().putString("touchMode", v.name).apply()

    /** Custom controller mapping: physical Android keycode -> target. Empty = default mapping. */
    var padMap: Map<Int, PadTarget>
        get() = (p.getString("padMap", "") ?: "").split(',').mapNotNull { e ->
            val (k, t) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val target = runCatching { PadTarget.valueOf(t) }.getOrNull() ?: return@mapNotNull null
            k.toIntOrNull()?.let { it to target }
        }.toMap()
        set(v) = p.edit().putString("padMap", v.entries.joinToString(",") { "${it.key}:${it.value.name}" }).apply()

    var sixButtonPad: Boolean
        get() = p.getBoolean("six", true)
        set(v) = p.edit().putBoolean("six", v).apply()

    /** null = follow device profile */
    var rewind: Boolean?
        get() = if (p.contains("rewind")) p.getBoolean("rewind", true) else null
        set(v) { if (v == null) p.edit().remove("rewind").apply() else p.edit().putBoolean("rewind", v).apply() }

    var autoResume: Boolean
        get() = p.getBoolean("autoResume", true)
        set(v) = p.edit().putBoolean("autoResume", v).apply()

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
