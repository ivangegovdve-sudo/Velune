/*
 * Velune Web Radio — RadioEncoder
 * Wraps Android MediaCodec to encode raw PCM → AAC-LC with ADTS framing,
 * ready to push directly into an Icecast stream.
 *
 * Usage:
 *   val encoder = RadioEncoder()
 *   encoder.start(sampleRate = 44100, channelCount = 2, bitrate = 128_000)
 *   val adtsFrame: ByteArray? = encoder.encode(pcmChunk)
 *   encoder.stop()
 */

package com.nikhil.yt.radio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "RadioEncoder"
private const val MIME = "audio/mp4a-latm"   // AAC-LC
private const val TIMEOUT_US = 5_000L        // 5 ms dequeue timeout

class RadioEncoder {

    private var codec: MediaCodec? = null
    private var sampleRate = 44100
    private var channelCount = 2

    /** Configure and start the MediaCodec encoder. */
    fun start(sampleRate: Int, channelCount: Int, bitrate: Int = 128_000) {
        stop()
        this.sampleRate = sampleRate
        this.channelCount = channelCount

        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        codec = MediaCodec.createEncoderByType(MIME).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
            Log.i(TAG, "Encoder started: ${sampleRate}Hz ch=$channelCount bps=$bitrate")
        }
    }

    /**
     * Feed a PCM chunk and drain all available encoded output.
     * Returns concatenated ADTS-framed AAC frames, or null if nothing ready yet.
     * The returned bytes are safe to write directly to an Icecast socket.
     */
    fun encode(pcmBytes: ByteArray): ByteArray? {
        val c = codec ?: return null
        val result = mutableListOf<ByteArray>()

        // --- Feed input ---
        val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx >= 0) {
            val buf: ByteBuffer = c.getInputBuffer(inIdx)!!
            buf.clear()
            val toCopy = minOf(pcmBytes.size, buf.capacity())
            buf.put(pcmBytes, 0, toCopy)
            c.queueInputBuffer(inIdx, 0, toCopy, System.nanoTime() / 1000, 0)
        }

        // --- Drain output ---
        val info = MediaCodec.BufferInfo()
        var outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)
        while (outIdx >= 0) {
            val outBuf: ByteBuffer = c.getOutputBuffer(outIdx)!!
            val frameBytes = ByteArray(info.size)
            outBuf.position(info.offset)
            outBuf.get(frameBytes)
            c.releaseOutputBuffer(outIdx, false)

            // Prepend 7-byte ADTS header so browsers/players understand the frame
            result.add(buildAdtsHeader(info.size) + frameBytes)
            outIdx = c.dequeueOutputBuffer(info, 0)
        }

        return if (result.isEmpty()) null
        else result.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    /** Stop and release the encoder. Safe to call even if not started. */
    fun stop() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    // ---- ADTS framing -------------------------------------------------------

    private fun buildAdtsHeader(frameDataSize: Int): ByteArray {
        val totalLength = frameDataSize + 7  // 7-byte header, no CRC
        val srIdx = sampleRateIndex(sampleRate)
        val ch = channelCount

        // Bit layout (no CRC variant):
        //  syncword 12b | ID 1b | layer 2b | protection_absent 1b
        //  | profile 2b | sr_idx 4b | private 1b | ch_conf 3b
        //  | originality 1b | home 1b | copyright_id 1b | copyright_start 1b
        //  | frame_length 13b | adts_fullness 11b | num_blocks 2b
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),   // MPEG-4, layer=0, no CRC
            ((0x01 shl 6) or (srIdx shl 2) or (ch shr 2)).toByte(),
            (((ch and 0x3) shl 6) or (totalLength shr 11)).toByte(),
            ((totalLength shr 3) and 0xFF).toByte(),
            (((totalLength and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private fun sampleRateIndex(rate: Int) = when (rate) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
        44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
        16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
        7350  -> 12; else -> 4   // default to 44100 index
    }
}
