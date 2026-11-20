package com.neondrive.emu.game

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Records the screen (H.264) and the game's own sound (AAC, via playback capture) into an MP4.
 * Android 10+ (playback capture). If sound capture is not allowed, records video only.
 */
@RequiresApi(29)
class ScreenRecorder(context: Context, private val projection: MediaProjection, private val outFile: File) {

    private val width: Int
    private val height: Int
    private val dpi: Int

    init {
        val metrics = context.resources.displayMetrics
        val (sw, sh) = if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else metrics.widthPixels to metrics.heightPixels
        // 1280 px on the long side keeps files small and every phone's encoder happy.
        val scale = minOf(1f, 1280f / maxOf(sw, sh))
        width = ((sw * scale).toInt() / 16) * 16
        height = ((sh * scale).toInt() / 16) * 16
        dpi = metrics.densityDpi
    }

    private lateinit var muxer: MediaMuxer
    private lateinit var videoCodec: MediaCodec
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    @Volatile private var running = false
    @Volatile private var stopRequestedAt = 0L
    private val workers = mutableListOf<Thread>()

    /** Returns true when sound capture is active (false = video only). */
    fun start(withAudio: Boolean): Boolean {
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = videoCodec.createInputSurface()
        videoCodec.start()
        virtualDisplay = projection.createVirtualDisplay(
            "NeonDriveRecording", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )

        if (withAudio) {
            runCatching { setupAudio() }.onFailure {
                runCatching { audioRecord?.release() }; audioRecord = null
                runCatching { audioCodec?.release() }; audioCodec = null
            }
        }

        running = true
        workers += thread(name = "rec-video") { drainVideo() }
        if (audioCodec != null) workers += thread(name = "rec-audio") { audioLoop() }
        return audioCodec != null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested before recording starts
    private fun setupAudio() {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, 4096) * 4)
            .setAudioPlaybackCaptureConfig(capture)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }

        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        record.startRecording()
        audioRecord = record
        audioCodec = codec
    }

    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && (audioCodec == null || audioTrack >= 0)) {
            muxer.start()
            muxerStarted = true
        }
    }

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!muxerStarted || track < 0 || info.size <= 0) return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer.writeSampleData(track, buffer, info)
        }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (!running && System.nanoTime() - stopRequestedAt > 3_000_000_000L) break // safety
            val index = try { videoCodec.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    videoTrack = muxer.addTrack(videoCodec.outputFormat); maybeStartMuxer()
                }
                index >= 0 -> {
                    val buf = videoCodec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null) write(videoTrack, buf, info)
                    videoCodec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun audioLoop() {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return
        val pcm = ByteArray(8192)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!running && System.nanoTime() - stopRequestedAt > 3_000_000_000L) break // safety
            if (!inputDone) {
                val inIndex = try { codec.dequeueInputBuffer(10_000) } catch (e: Exception) { break }
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex) ?: continue
                    inBuf.clear()
                    val pts = System.nanoTime() / 1000
                    if (!running) {
                        codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val read = record.read(pcm, 0, minOf(pcm.size, inBuf.remaining()))
                        if (read > 0) inBuf.put(pcm, 0, read)
                        codec.queueInputBuffer(inIndex, 0, maxOf(read, 0), pts, 0)
                    }
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

    /** Stops and finalizes the file. Returns true if a playable video was written. */
    fun stop(): Boolean {
        stopRequestedAt = System.nanoTime()
        running = false
        runCatching { videoCodec.signalEndOfInputStream() }
        workers.forEach { it.join(4_000) }
        runCatching { audioRecord?.stop() }; runCatching { audioRecord?.release() }
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
        runCatching { videoCodec.stop() }; runCatching { videoCodec.release() }
        runCatching { virtualDisplay?.release() }
        val ok = synchronized(lock) {
            val started = muxerStarted
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            started
        }
        runCatching { projection.stop() }
        return ok && outFile.length() > 0
    }

    companion object { private const val SAMPLE_RATE = 44_100 }
}
