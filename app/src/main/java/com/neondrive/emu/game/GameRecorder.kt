package com.neondrive.emu.game

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.view.Surface
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Records ONLY the game: the picture is copied from the game area of the GL surface right after
 * each frame is drawn (the touch buttons are separate views and never reach it), and the sound
 * is taken straight from the emulator. No screen-capture permission, works from Android 7.
 */
class GameRecorder(private val view: GLRetroView, private val outFile: File) : GLRetroView.FrameSink {

    private val lock = Any()
    private lateinit var muxer: MediaMuxer
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var expectAudio = true

    // video (created on the GL thread at the first frame, once the game size is known)
    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var eglSurface: EGLSurface? = null
    private var encW = 0
    private var encH = 0
    @Volatile private var glFailed: String? = null

    // audio
    private var audioCodec: MediaCodec? = null
    private var sampleRate = 44_100

    @Volatile private var running = false
    private val workers = mutableListOf<Thread>()
    private var startNs = 0L

    /** Reason recording could not run on this device, if any. */
    val failure: String? get() = glFailed

    fun start() {
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        startNs = System.nanoTime()
        expectAudio = runCatching { setupAudio() }.isSuccess
        running = true
        if (expectAudio) {
            LibretroDroid.setAudioCapture(true)
            workers += thread(name = "rec-audio") { audioLoop() }
        }
        view.frameSink = this
    }

    // ------------------------------------------------------------ video (GL thread)

    override fun onFrameDrawn(surfaceWidth: Int, surfaceHeight: Int, gameBounds: FloatArray) {
        if (!running || glFailed != null || surfaceWidth <= 0 || surfaceHeight <= 0) return

        val gx = (gameBounds[0] * surfaceWidth).toInt().coerceIn(0, surfaceWidth - 1)
        val gy = (gameBounds[1] * surfaceHeight).toInt().coerceIn(0, surfaceHeight - 1)
        val gw = ((gameBounds[2] - gameBounds[0]) * surfaceWidth).toInt().coerceIn(1, surfaceWidth - gx)
        val gh = ((gameBounds[3] - gameBounds[1]) * surfaceHeight).toInt().coerceIn(1, surfaceHeight - gy)

        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        val windowDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val windowRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        if (eglSurface == null) {
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            if (!version.contains("OpenGL ES 3")) { glFailed = "gles2"; return }
            setupVideo(gw, gh)
            eglSurface = createEncoderSurface(display, context) ?: run { glFailed = "egl"; return }
            workers += thread(name = "rec-video") { drainVideo() }
        }

        // Draw into the encoder, read from the window's freshly drawn back buffer.
        if (!EGL14.eglMakeCurrent(display, eglSurface, windowRead, context)) { glFailed = "makeCurrent"; return }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, encW, encH)
        GLES30.glBlitFramebuffer(gx, gy, gx + gw, gy + gh, 0, 0, encW, encH, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(display, eglSurface)
        EGL14.eglMakeCurrent(display, windowDraw, windowRead, context)
    }

    private fun setupVideo(gameW: Int, gameH: Int) {
        // Up to 720 lines, keep the game's shape, sizes multiple of 16 for every encoder.
        val scale = minOf(1f, 720f / gameH)
        encW = maxOf(16, ((gameW * scale).toInt() / 16) * 16)
        encH = maxOf(16, ((gameH * scale).toInt() / 16) * 16)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
    }

    private fun createEncoderSurface(display: android.opengl.EGLDisplay, context: android.opengl.EGLContext): EGLSurface? {
        val id = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
        val config = configs[0] ?: return null
        val surface = EGL14.eglCreateWindowSurface(display, config, inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        return surface.takeIf { it != EGL14.EGL_NO_SURFACE }
    }

    private fun releaseGl() {
        val display = EGL14.eglGetCurrentDisplay()
        eglSurface?.let { if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(display, it) }
        eglSurface = null
    }

    private fun drainVideo() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var stopAt = 0L
        while (true) {
            if (!running) {
                if (stopAt == 0L) stopAt = System.nanoTime()
                if (System.nanoTime() - stopAt > 3_000_000_000L) break
            }
            val index = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    videoTrack = muxer.addTrack(codec.outputFormat); maybeStartMuxer()
                }
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null) write(videoTrack, buf, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    // ------------------------------------------------------------ audio

    private fun setupAudio() {
        sampleRate = LibretroDroid.getCoreSampleRate().takeIf { it in 8_000..96_000 } ?: 44_100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec
    }

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val pcm = ShortArray(4096)
        val info = MediaCodec.BufferInfo()
        var framesWritten = 0L
        var inputDone = false
        var pending = 0
        var pendingOffset = 0
        val startUs = startNs / 1000

        while (true) {
            if (!inputDone) {
                if (pending == 0) {
                    pending = if (running) LibretroDroid.readCapturedAudio(pcm) else 0
                    pendingOffset = 0
                }
                if (pending > 0 || !running) {
                    val inIndex = try { codec.dequeueInputBuffer(5_000) } catch (e: Exception) { break }
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val pts = startUs + framesWritten * 1_000_000L / sampleRate
                        if (inBuf == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts, 0)
                        } else if (!running && pending == 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inBuf.clear()
                            val count = minOf(pending, inBuf.remaining() / 2)
                            inBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, pendingOffset, count)
                            codec.queueInputBuffer(inIndex, 0, count * 2, pts, 0)
                            framesWritten += count / 2
                            pending -= count; pendingOffset += count
                        }
                    }
                } else {
                    Thread.sleep(5)
                }
            }
            var out = try { codec.dequeueOutputBuffer(info, 0) } catch (e: Exception) { break }
            while (out != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(lock) { audioTrack = muxer.addTrack(codec.outputFormat); maybeStartMuxer() }
                } else if (out >= 0) {
                    val buf = codec.getOutputBuffer(out)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null) write(audioTrack, buf, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                out = try { codec.dequeueOutputBuffer(info, 0) } catch (e: Exception) { return }
            }
        }
    }

    // ------------------------------------------------------------ muxer

    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && (!expectAudio || audioTrack >= 0)) {
            muxer.start(); muxerStarted = true
        }
    }

    private fun write(track: Int, buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!muxerStarted || track < 0 || info.size <= 0) return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer.writeSampleData(track, buffer, info)
        }
    }

    /** Stops and finalizes the MP4. Returns true when a playable file was written. */
    fun stop(): Boolean {
        view.frameSink = null
        running = false
        LibretroDroid.setAudioCapture(false)

        // EGL objects belong to the GL thread.
        val latch = CountDownLatch(1)
        view.queueEvent { try { releaseGl() } finally { latch.countDown() } }
        latch.await(2, TimeUnit.SECONDS)

        runCatching { videoCodec?.signalEndOfInputStream() }
        workers.forEach { it.join(4_000) }
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }
        runCatching { inputSurface?.release() }
        val ok = synchronized(lock) {
            val started = muxerStarted
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            started
        }
        return ok && outFile.length() > 0
    }
}
