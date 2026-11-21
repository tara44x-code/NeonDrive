package com.neondrive.emu.data

import android.content.Context

enum class VideoFilter(val label: String) {
    AUTO("تلقائي حسب الجهاز"),
    PIXEL("بكسل أصلي"),
    SHARP("حاد ناعم"),
    SMOOTH("تنعيم ذكي (CUT2)"),
    ULTRA("دقة فائقة (CUT3)"),
    XBRZ("xBRZ وضوح عالي"),
    XBR("xBR ناعم"),
    CRT("شاشة CRT"),
    LCD("شاشة LCD"),
}

/** How the picture fills the phone screen. [ratio] = forced display ratio (null = engine decides). */
enum class AspectMode(val label: String, val ratio: Float?) {
    ORIGINAL("أصلي", null),
    INTEGER("بكسل مثالي", null),          // whole-number scaling: every console line = N screen lines
    STRETCH_16_9("تمدد 16:9", 16f / 9f),
    STRETCH_16_10("16:10", 16f / 10f),
    STRETCH_3_2("3:2", 3f / 2f),
    FULL("ملء الشاشة", null),
}

/** Colour output of the console's video chip, done by the engine itself (blargg NTSC filter). */
enum class ColorMode(val label: String, val coreValue: String) {
    ORIGINAL("أصلية نقية", "disabled"),
    RGB("كيبل RGB", "rgb"),
    SVIDEO("S-Video", "svideo"),
    COMPOSITE("تلفزيون Composite", "composite"),
    MONO("أبيض وأسود", "monochrome"),
}

enum class TouchMode(val label: String) {
    AUTO("تلقائي (تختفي مع الجويستك)"),
    ALWAYS("ظاهرة دائماً"),
    HIDDEN("مخفية دائماً"),
}

/** Targets a physical controller button can be mapped to. [keyCode] is what we send to the core. */
enum class PadTarget(val label: String, val keyCode: Int) {
    A("A", 100), B("B", 97), C("C", 96),              // KEYCODE_BUTTON_Y / _B / _A (see SegaKeys)
    X("X", 102), Y("Y", 99), Z("Z", 103),             // KEYCODE_BUTTON_L1 / _X / _R1
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
            .getOrElse { if (p.getBoolean("wide", false)) AspectMode.STRETCH_16_9 else AspectMode.ORIGINAL }
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

    // ---- Picture adjustment (applied on top of any filter) ----
    var brightness: Float
        get() = p.getFloat("bright", 0f)
        set(v) = p.edit().putFloat("bright", v).apply()
    var contrast: Float
        get() = p.getFloat("contrast", 1f)
        set(v) = p.edit().putFloat("contrast", v).apply()
    var saturation: Float
        get() = p.getFloat("sat", 1f)
        set(v) = p.edit().putFloat("sat", v).apply()

    fun colorAdjust() = com.swordfish.libretrodroid.ColorAdjust(brightness, contrast, saturation)

    var colorMode: ColorMode
        get() = runCatching { ColorMode.valueOf(p.getString("color", "ORIGINAL")!!) }.getOrDefault(ColorMode.ORIGINAL)
        set(v) = p.edit().putString("color", v.name).apply()

    /** Removes the console's sprite limit: less flicker when many objects are on screen. */
    var noSpriteLimit: Boolean
        get() = p.getBoolean("noSpriteLimit", false)
        set(v) = p.edit().putBoolean("noSpriteLimit", v).apply()

    /** Game Gear LCD ghosting, like the real handheld screen. */
    var ggLcd: Boolean
        get() = p.getBoolean("ggLcd", false)
        set(v) = p.edit().putBoolean("ggLcd", v).apply()

    var autoResume: Boolean
        get() = p.getBoolean("autoResume", true)
        set(v) = p.edit().putBoolean("autoResume", v).apply()

    // ---- Theme ----
    var uiPalette: com.neondrive.emu.ui.NeonPalette
        get() = com.neondrive.emu.ui.NeonPalette.byId(p.getString("uiPal", null), p.getString("uiPalC", null))
        set(v) = p.edit().putString("uiPal", v.id).putString("uiPalC", v.encode()).apply()

    var padPalette: com.neondrive.emu.ui.NeonPalette
        get() = com.neondrive.emu.ui.NeonPalette.byId(p.getString("padPal", null), p.getString("padPalC", null))
        set(v) = p.edit().putString("padPal", v.id).putString("padPalC", v.encode()).apply()

    // ---- Touch layout (per console) ----
    fun layout(system: GameSystem) = com.neondrive.emu.game.ControlsLayout.decode(p.getString("layout_${system.name}", null))
    fun setLayout(system: GameSystem, layout: com.neondrive.emu.game.ControlsLayout) =
        p.edit().putString("layout_${system.name}", layout.encode()).apply()

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
