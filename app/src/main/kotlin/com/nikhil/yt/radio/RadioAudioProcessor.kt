/*
 * Velune Web Radio — RadioAudioProcessor
 * A passthrough ExoPlayer AudioProcessor that taps decoded PCM audio
 * and forwards it to RadioBridge for streaming, without affecting playback.
 */

package com.nikhil.yt.radio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class RadioAudioProcessor : AudioProcessor {

    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        this.inputAudioFormat = inputAudioFormat
        if (inputAudioFormat != AudioFormat.NOT_SET) {
            RadioBridge.audioFormat = Triple(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                inputAudioFormat.encoding,
            )
        }
        // Pure passthrough: output format equals input format
        return inputAudioFormat
    }

    /**
     * Active whenever we have a valid format.
     * Keeping it always-active ensures we never miss a streaming window.
     */
    override fun isActive(): Boolean = inputAudioFormat != AudioFormat.NOT_SET
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val remaining = inputBuffer.remaining()
        val bytes = ByteArray(remaining)

        // Read all bytes from inputBuffer (advances its position, signalling consumed)
        inputBuffer.get(bytes)

        // --- STREAMING TAP ---
        // Copy to queue only when streaming is active; offer() is non-blocking,
        // silently drops the chunk if the queue is full (encoder fell behind).
        if (RadioBridge.isStreaming.get()) {
            RadioBridge.pcmQueue.offer(bytes)
        }

        // --- PASSTHROUGH ---
        // Re-wrap the same bytes into a new buffer for ExoPlayer to render.
        outputBuffer = ByteBuffer
            .allocate(remaining)
            .order(ByteOrder.nativeOrder())
            .put(bytes)
            .also { it.flip() }
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        RadioBridge.audioFormat = null
    }
}
