package com.neondrive.emu.game

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.swordfish.libretrodroid.LibretroDroid
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Records ONLY the game, without touching rendering: the emulator hands over each raw frame
 * (e.g. 320x224), it is enlarged and encoded on our own threads, and the sound comes straight
 * from the emulator. The game never waits for the encoder, the touch buttons are never in it,
 * and it needs no permission. Works from Android 7.
 *
 * The picture is enlarged by a WHOLE number (e.g. 320x224 x4 = 1280x896): every game pixel
 * becomes an exact square block, so edges stay sharp and colours never bleed (4:2:0 chroma
 * blocks fall inside one game pixel).
 *
 * @param maxLines tallest video allowed (1080 on strong phones, 720 otherwise)
 */
class GameRecorder(private val outFile: File, private val maxLines: Int) {

    private var encW = 0
    private var encH = 0

    private val lock = Any()
    private lateinit var muxer: MediaMuxer
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var expectAudio = true

    private lateinit var videoCodec: MediaCodec
    private var audioCodec: MediaCodec? = null
    private var sampleRate = 44_100

    @Volatile private var running = false
    @Volatile private var stopAt = 0L
    private val workers = mutableListOf<Thread>()
    private var startNs = 0L

    fun start() {
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        startNs = System.nanoTime()

        expectAudio = runCatching { setupAudio() }.isSuccess

        running = true
        LibretroDroid.setVideoCapture(true)
        workers += thread(name = "rec-video") { videoLoop() }
        if (expectAudio) {
            LibretroDroid.setAudioCapture(true)
            workers += thread(name = "rec-audio") { audioLoop() }
        }
    }

    // ------------------------------------------------------------ video

    /** Largest whole-number scale of the game frame that the encoder accepts. */
    private fun setupVideo(gameW: Int, gameH: Int) {
        var k = maxOf(1, maxLines / gameH)
        while (k > 1 && gameW * k > 1920) k--
        encW = (gameW * k) / 16 * 16
        encH = (gameH * k) / 16 * 16
        // Pixel art needs plenty of bits: ~0.3 bit per pixel per frame, 12-30 Mbps.
        val bitrate = (encW.toLong() * encH * 60 * 3 / 10).coerceIn(12_000_000, 30_000_000).toInt()
        fun format(highProfile: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (highProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            }
        }
        // Prefer High profile (more detail for the same size); fall back to the encoder's default.
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
        codec.start()
        videoCodec = codec
    }

    private fun videoLoop() {
        // Wait for the first game frame to learn its real size (up to 2 s).
        val size = IntArray(2)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (running && System.nanoTime() < deadline) {
            LibretroDroid.getCapturedFrameSize(size)
            if (size[0] > 0 && size[1] > 0) break
            Thread.sleep(10)
        }
        if (size[0] <= 0 || size[1] <= 0) { size[0] = 320; size[1] = 224 }
        try { setupVideo(size[0], size[1]) } catch (e: Exception) { return }

        val frameSize = encW * encH * 3 / 2
        val direct = ByteBuffer.allocateDirect(frameSize)
        val yuv = ByteArray(frameSize)
        val info = MediaCodec.BufferInfo()
        var lastSeq = -1L
        var eosQueued = false

        while (true) {
            // 1) feed the newest game frame
            if (!eosQueued) {
                if (!running) {
                    val idx = try { videoCodec.dequeueInputBuffer(10_000) } catch (e: Exception) { break }
                    if (idx >= 0) {
                        videoCodec.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                } else {
                    val seq = LibretroDroid.readCapturedFrameI420(direct, encW, encH)
                    if (seq >= 0 && seq != lastSeq) {
                        val idx = try { videoCodec.dequeueInputBuffer(5_000) } catch (e: Exception) { break }
                        if (idx >= 0) {
                            lastSeq = seq
                            direct.rewind(); direct.get(yuv); direct.rewind()
                            val image = videoCodec.getInputImage(idx)
                            if (image != null) {
                                fillImage(image, yuv)
                                videoCodec.queueInputBuffer(idx, 0, frameSize, System.nanoTime() / 1000, 0)
                            } else {
                                videoCodec.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, 0)
                            }
                        }
                    } else {
                        Thread.sleep(3)
                    }
                }
            } else if (System.nanoTime() - stopAt > 3_000_000_000L) break // safety

            // 2) collect encoded output
            var out = try { videoCodec.dequeueOutputBuffer(info, 0) } catch (e: Exception) { break }
            while (out != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(lock) { videoTrack = muxer.addTrack(videoCodec.outputFormat); maybeStartMuxer() }
                } else if (out >= 0) {
                    val buf = videoCodec.getOutputBuffer(out)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null) write(videoTrack, buf, info)
                    videoCodec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                out = try { videoCodec.dequeueOutputBuffer(info, 0) } catch (e: Exception) { return }
            }
        }
    }

    /** Copies an I420 frame into the encoder's image, whatever its plane layout (planar or NV12). */
    private fun fillImage(image: Image, yuv: ByteArray) {
        val planes = image.planes
        val cw = encW / 2; val ch = encH / 2
        copyPlane(yuv, 0, encW, encH, planes[0])
        copyPlane(yuv, encW * encH, cw, ch, planes[1])
        copyPlane(yuv, encW * encH + cw * ch, cw, ch, planes[2])
    }

    private fun copyPlane(src: ByteArray, offset: Int, w: Int, h: Int, plane: Image.Plane) {
        val dst = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            for (row in 0 until h) {
                dst.position(row * rowStride)
                dst.put(src, offset + row * w, w)
            }
        } else {
            for (row in 0 until h) {
                val base = row * rowStride
                val s = offset + row * w
                for (col in 0 until w) dst.put(base + col * pixelStride, src[s + col])
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
            } else if (System.nanoTime() - stopAt > 3_000_000_000L) break // safety

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

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!muxerStarted || track < 0 || info.size <= 0) return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer.writeSampleData(track, buffer, info)
        }
    }

    /** Stops and finalizes the MP4. Returns true when a playable file was written. */
    fun stop(): Boolean {
        stopAt = System.nanoTime()
        running = false
        LibretroDroid.setVideoCapture(false)
        LibretroDroid.setAudioCapture(false)
        workers.forEach { it.join(4_000) }
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
        if (::videoCodec.isInitialized) { runCatching { videoCodec.stop() }; runCatching { videoCodec.release() } }
        val ok = synchronized(lock) {
            val started = muxerStarted
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            started
        }
        return ok && outFile.length() > 0
    }
}
