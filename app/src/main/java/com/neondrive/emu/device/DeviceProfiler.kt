package com.neondrive.emu.device

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import com.neondrive.emu.data.VideoFilter
import com.swordfish.libretrodroid.ShaderConfig
import java.io.File

enum class Tier(val label: String) { LOW("اقتصادي"), MID("متوسط"), HIGH("عالي") }

data class DeviceProfile(
    val gpu: String,
    val soc: String,
    val cores: Int,
    val maxGhz: Float,
    val ramGb: Float,
    val tier: Tier,
) {
    /** Filter picked automatically for this hardware. */
    val autoFilter: VideoFilter
        get() = when (tier) {
            Tier.LOW -> VideoFilter.SHARP
            Tier.MID -> VideoFilter.SMOOTH
            Tier.HIGH -> VideoFilter.ULTRA
        }

    val rewindByDefault: Boolean get() = tier != Tier.LOW
    val rewindIntervalMs: Long get() = if (tier == Tier.HIGH) 250 else 500
    val skipDuplicateFrames: Boolean get() = tier == Tier.LOW

    fun shaderFor(filter: VideoFilter): ShaderConfig = when (if (filter == VideoFilter.AUTO) autoFilter else filter) {
        VideoFilter.PIXEL, VideoFilter.AUTO -> ShaderConfig.Default
        VideoFilter.SHARP -> ShaderConfig.Sharp
        VideoFilter.SMOOTH -> ShaderConfig.CUT2()
        VideoFilter.ULTRA -> ShaderConfig.CUT3()
        VideoFilter.CRT -> ShaderConfig.CRT
        VideoFilter.LCD -> ShaderConfig.LCD
    }
}

/** Reads CPU, RAM and GPU (via a throw-away EGL context) and rates the device. */
object DeviceProfiler {
    @Volatile private var cached: DeviceProfile? = null

    fun get(context: Context): DeviceProfile = cached ?: synchronized(this) {
        cached ?: profile(context).also { cached = it }
    }

    private fun profile(context: Context): DeviceProfile {
        val gpu = runCatching { glRenderer() }.getOrNull() ?: "Unknown GPU"
        val cores = Runtime.getRuntime().availableProcessors()
        val maxKhz = (0 until cores).maxOfOrNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
                .getOrDefault(0L)
        } ?: 0L
        val mem = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val ramGb = mem.totalMem / 1_073_741_824f
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val ghz = maxKhz / 1_000_000f

        val gpuScore = gpuScore(gpu)
        val cpuScore = when { ghz >= 2.6f && cores >= 8 -> 3; ghz >= 2.0f -> 2; ghz == 0f -> 2; else -> 1 }
        val ramScore = when { ramGb >= 5.5f -> 3; ramGb >= 2.8f -> 2; else -> 1 }
        val avg = (gpuScore * 2 + cpuScore + ramScore) / 4f   // GPU matters most for shaders
        val tier = when { avg >= 2.5f -> Tier.HIGH; avg >= 1.75f -> Tier.MID; else -> Tier.LOW }

        return DeviceProfile(gpu, soc, cores, ghz, ramGb, tier)
    }

    private fun gpuScore(gpu: String): Int {
        val g = gpu.lowercase()
        val num = Regex("""\d+""").find(g.substringAfter("adreno").substringAfter("mali"))?.value?.toIntOrNull() ?: 0
        return when {
            "immortalis" in g || "xclipse" in g -> 3
            "adreno" in g -> when { num >= 640 -> 3; num >= 530 -> 2; else -> 1 }
            "mali-g" in g -> when {
                num >= 100 -> if (num >= 610) 3 else 2      // G610, G710, G720 ...
                num >= 76 -> 3                               // G76, G77, G78
                num >= 52 -> 2
                else -> 1
            }
            "powervr" in g || "mali-t" in g || "mali-4" in g -> 1
            else -> 2
        }
    }

    private fun glRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val ctx = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            EGL14.eglMakeCurrent(display, surface, surface, ctx)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, ctx)
            return renderer
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
