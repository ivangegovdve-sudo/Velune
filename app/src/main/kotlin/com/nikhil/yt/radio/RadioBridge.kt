/*
 * Velune Web Radio — RadioBridge
 * Thread-safe shared state between RadioAudioProcessor (audio thread),
 * RadioStreamingService (IO thread), and the Compose UI (main thread).
 *
 * Compose code observes [state] for live updates without polling.
 * Audio code uses the lock-free atomics / queue.
 */

package com.nikhil.yt.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton bridge connecting the audio processor tap to the streaming service
 * and exposing live state to the UI.
 */
object RadioBridge {

    // ---------------------------------------------------------------- Audio
    /** Bounded queue of raw PCM byte chunks. Drops oldest if full to avoid OOM. */
    val pcmQueue = LinkedBlockingQueue<ByteArray>(300)

    /** Set to true by RadioStreamingService when actively streaming. Kept for
     *  backwards-compat call sites; UI prefers [state]. */
    val isStreaming = AtomicBoolean(false)

    /** Audio format captured during AudioProcessor.configure(). Triple = (sampleRate, channelCount, encoding). */
    @Volatile
    var audioFormat: Triple<Int, Int, Int>? = null

    // -------------------------------------------------------------- Track meta
    /** Song title for the Icecast stream metadata — updated from PlayerConnection. */
    @Volatile
    var currentTrackTitle: String = "Velune Web Radio"

    @Volatile
    var currentTrackArtist: String = ""

    /** YouTube video id, when known — used by listener page to build a watch link. */
    @Volatile
    var currentVideoId: String? = null

    /** Artwork URL (https) for the current track — optional. */
    @Volatile
    var currentArtworkUrl: String? = null

    // ------------------------------------------------------------ Live UI state
    /** All status this stream can be in. */
    enum class Status { Idle, Connecting, Streaming, Error }

    /**
     * Snapshot of stream state. Immutable so Compose recomposes cleanly.
     *
     * @param status        coarse state for icon/colour decisions
     * @param message       human-readable status line ("Streaming to …", "Error: …")
     * @param startedAtMs   System.currentTimeMillis() when the current stream started, or 0
     * @param bytesSent     bytes pushed to Icecast since stream start (0 when idle)
     * @param listenerUrl   pre-computed http URL listeners should open, or "" when not configured
     */
    data class State(
        val status: Status = Status.Idle,
        val message: String = "Idle",
        val startedAtMs: Long = 0L,
        val bytesSent: Long = 0L,
        val listenerUrl: String = "",
        val track: Track = Track(),
    ) {
        data class Track(
            val title: String = "",
            val artist: String = "",
            val videoId: String? = null,
            val artworkUrl: String? = null,
        )
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Publish a new status. Safe to call from any thread. */
    fun publishStatus(status: Status, message: String) {
        _state.value = _state.value.copy(status = status, message = message)
        isStreaming.set(status == Status.Streaming)
    }

    /** Mark the start of an actively streaming session. */
    fun publishStreamStarted(listenerUrl: String) {
        _state.value = _state.value.copy(
            status = Status.Streaming,
            message = "Live",
            startedAtMs = System.currentTimeMillis(),
            bytesSent = 0L,
            listenerUrl = listenerUrl,
        )
        isStreaming.set(true)
    }

    fun publishStreamStopped(reason: String = "Idle") {
        _state.value = _state.value.copy(
            status = Status.Idle,
            message = reason,
            startedAtMs = 0L,
            bytesSent = 0L,
        )
        isStreaming.set(false)
    }

    fun publishError(message: String) {
        _state.value = _state.value.copy(status = Status.Error, message = message)
        isStreaming.set(false)
    }

    fun publishBytesSent(total: Long) {
        // Skip needless emissions while idle.
        if (_state.value.status == Status.Streaming) {
            _state.value = _state.value.copy(bytesSent = total)
        }
    }

    /** Update now-playing snapshot. UI + metadata publisher both observe this. */
    fun publishTrack(title: String, artist: String, videoId: String?, artworkUrl: String?) {
        currentTrackTitle = title.ifBlank { "Velune Web Radio" }
        currentTrackArtist = artist
        currentVideoId = videoId
        currentArtworkUrl = artworkUrl
        _state.value = _state.value.copy(
            track = State.Track(title, artist, videoId, artworkUrl)
        )
    }

    /** Clear queue and reset streaming flag — call when stopping. */
    fun reset() {
        isStreaming.set(false)
        pcmQueue.clear()
        publishStreamStopped()
    }
}
