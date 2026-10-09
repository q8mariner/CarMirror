package com.carmirror.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hardware H.264 encoder tuned for latency, fed by a Surface (no CPU copies).
 * Output Annex-B NAL units are delivered via [onFrame] on a dedicated thread.
 *
 * Tuning: no B-frames, 1 s GOP, realtime priority, low-latency hint (API 30+),
 * and repeat-previous-frame so static screens still produce output.
 */
class LowLatencyEncoder(
    sourceWidth: Int,
    sourceHeight: Int,
    private val bitRate: Int = 8_000_000,
    private val frameRate: Int = 60,
    private val maxLongSide: Int = 1920,
    private val onFrame: (EncodedFrame) -> Unit
) {
    val width: Int
    val height: Int

    init {
        // Downscale so the long side fits common hardware encoder limits; align to 16
        // (Exynos and Snapdragon encoders are happiest with macroblock-aligned sizes).
        val scale = minOf(1f, maxLongSide.toFloat() / max(sourceWidth, sourceHeight))
        width = align16((sourceWidth * scale).roundToInt())
        height = align16((sourceHeight * scale).roundToInt())
    }

    private val thread = HandlerThread("LowLatencyEncoder").apply { start() }
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    lateinit var inputSurface: Surface
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // µs
            setInteger(MediaFormat.KEY_PRIORITY, 0)                           // realtime
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        codec.setCallback(callback, Handler(thread.looper))
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    /** Ask for an IDR frame right away (e.g. when a new receiver connects). */
    fun requestKeyFrame() {
        runCatching {
            codec.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (::inputSurface.isInitialized) inputSurface.release()
        thread.quitSafely()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // Surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also { buffer.get(it) }
                    onFrame(
                        EncodedFrame(
                            data = bytes,
                            presentationTimeUs = info.presentationTimeUs,
                            isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                            isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        )
                    )
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Codec was released while a callback was in flight.
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e("LowLatencyEncoder", "Codec error (recoverable=${e.isRecoverable})", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d("LowLatencyEncoder", "Output format: $format")
        }
    }

    private fun align16(v: Int) = max(16, v and 15.inv())
}
