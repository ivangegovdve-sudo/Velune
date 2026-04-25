/*
 * Velune Web Radio — IcecastMetadataPublisher
 *
 * Pushes the now-playing track to Icecast's /admin/metadata endpoint
 * whenever it changes. The static listener page reads this via the
 * standard /status-json.xsl response.
 *
 * To preserve the YouTube id without a second channel, we encode it
 * as a suffix the listener page knows how to strip:
 *
 *     "Track Title — Artist [yt:VIDEO_ID]"
 *
 * Standard Icecast clients (VLC, browsers) just see the human-readable
 * portion; our listener page parses the suffix to build a YouTube link.
 */

package com.nikhil.yt.radio

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "IcecastMetadataPublisher"
private const val CONNECT_TIMEOUT_MS = 6_000
private const val READ_TIMEOUT_MS = 6_000

class IcecastMetadataPublisher(
    private val host: String,
    private val port: Int,
    private val mount: String,
    private val sourcePassword: String,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    /**
     * Begin observing [RadioBridge.state] and pushing metadata
     * each time the displayed track changes.
     */
    fun start() {
        stop()
        job = scope.launch {
            RadioBridge.state
                .map { it.track }
                .distinctUntilChanged()
                .filter { it.title.isNotBlank() }
                .collect { track ->
                    runCatching { push(track) }
                        .onFailure { Log.w(TAG, "metadata push failed: ${it.message}") }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Format the track for display. Encodes the YouTube id as a parseable suffix. */
    private fun formatSong(track: RadioBridge.State.Track): String {
        val display = buildString {
            append(track.title.ifBlank { "Velune Web Radio" })
            if (track.artist.isNotBlank()) append(" — ").append(track.artist)
        }
        val ytSuffix = track.videoId?.takeIf { it.isNotBlank() }?.let { " [yt:$it]" }.orEmpty()
        return display + ytSuffix
    }

    /** POST song= to /admin/metadata using HTTP basic auth (user "source"). */
    private fun push(track: RadioBridge.State.Track) {
        if (host.isBlank()) return
        val song = formatSong(track)
        val mountPath = if (mount.startsWith("/")) mount else "/$mount"
        val urlString = buildString {
            append("http://$host:$port/admin/metadata")
            append("?mode=updinfo")
            append("&mount=").append(URLEncoder.encode(mountPath, "UTF-8"))
            append("&song=").append(URLEncoder.encode(song, "UTF-8"))
        }
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            val creds = Base64.encodeToString(
                "source:$sourcePassword".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            setRequestProperty("Authorization", "Basic $creds")
            setRequestProperty("User-Agent", "Velune-Radio/1.0")
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                Log.d(TAG, "metadata pushed: $song")
            } else {
                Log.w(TAG, "metadata push HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
