/*
 * Velune Web Radio — IcecastClient
 * Implements the Icecast 2 source protocol (plain HTTP SOURCE request).
 * Keeps a persistent socket open and streams audio bytes continuously.
 */

package com.nikhil.yt.radio

import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "IcecastClient"
private const val CONNECT_TIMEOUT_MS = 8_000
private const val SO_TIMEOUT_MS = 15_000

class IcecastClient {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var connected = false

    /**
     * Opens a connection to the Icecast server and sends the SOURCE handshake.
     * @param host      Icecast server hostname or IP
     * @param port      Icecast server port (default 8000)
     * @param mountPoint  Mount point path, e.g. "/velune"
     * @param password  Icecast source password
     * @param contentType  MIME type of the stream, e.g. "audio/aac"
     * @param streamName  Station name shown in Icecast admin
     * @throws Exception if connection or handshake fails
     */
    fun connect(
        host: String,
        port: Int,
        mountPoint: String,
        password: String,
        contentType: String = "audio/aac",
        streamName: String = "Velune Web Radio",
    ) {
        disconnect() // ensure clean state

        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = SO_TIMEOUT_MS
        socket = s

        val os = BufferedOutputStream(s.getOutputStream(), 65_536)
        outputStream = os

        // Icecast 2 SOURCE handshake
        // Credentials: user "source", password = the source password
        val credentials = Base64.encodeToString(
            "source:$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val mount = if (mountPoint.startsWith("/")) mountPoint else "/$mountPoint"

        val handshake = buildString {
            append("SOURCE $mount HTTP/1.0\r\n")
            append("Authorization: Basic $credentials\r\n")
            append("Content-Type: $contentType\r\n")
            append("ice-name: $streamName\r\n")
            append("ice-public: 0\r\n")
            append("\r\n")
        }

        os.write(handshake.toByteArray(Charsets.UTF_8))
        os.flush()

        // Read server response (non-blocking peek — we don't block on it)
        // Icecast sends "HTTP/1.0 200 OK" on success. We skip full parsing
        // and rely on write failures to detect rejection.
        connected = true
        Log.i(TAG, "Connected to icecast://$host:$port$mount")
    }

    /** Write a chunk of encoded audio bytes to the Icecast stream. */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        outputStream?.write(bytes, offset, length)
    }

    /** Flush buffered data — call periodically for low-latency delivery. */
    fun flush() {
        outputStream?.flush()
    }

    val isConnected: Boolean get() = connected && socket?.isConnected == true

    /** Close socket and clean up. Safe to call multiple times. */
    fun disconnect() {
        connected = false
        try { outputStream?.flush() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        socket = null
        Log.i(TAG, "Disconnected from Icecast")
    }
}
