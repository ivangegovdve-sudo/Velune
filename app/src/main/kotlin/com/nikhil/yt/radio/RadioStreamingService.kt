/*
 * Velune Web Radio — RadioStreamingService
 * Foreground service that encodes PCM from RadioBridge and pushes it
 * to an Icecast server as a continuous AAC-LC (ADTS) stream.
 *
 * Start via:
 *   Intent(context, RadioStreamingService::class.java)
 *     .putExtra(EXTRA_HOST, "myserver.com")
 *     .putExtra(EXTRA_PORT, 8000)
 *     .putExtra(EXTRA_MOUNT, "/velune")
 *     .putExtra(EXTRA_PASSWORD, "hackme")
 *
 * Stop via:
 *   Intent(context, RadioStreamingService::class.java)
 *     .setAction(ACTION_STOP)
 */

package com.nikhil.yt.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nikhil.yt.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val TAG = "RadioStreamingService"
const val ACTION_STOP = "com.nikhil.yt.radio.STOP"
const val EXTRA_HOST = "radio_host"
const val EXTRA_PORT = "radio_port"
const val EXTRA_MOUNT = "radio_mount"
const val EXTRA_PASSWORD = "radio_password"
const val EXTRA_BITRATE = "radio_bitrate"
private const val CHANNEL_ID = "velune_radio_stream"
private const val NOTIF_ID = 9876

class RadioStreamingService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var streamJob: Job? = null

    private val encoder = RadioEncoder()
    private val icecast = IcecastClient()
    private var metadataPublisher: IcecastMetadataPublisher? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            stopSelf()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 8000)
        val mount = intent.getStringExtra(EXTRA_MOUNT) ?: "/velune"
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 128_000)

        startForeground(NOTIF_ID, buildNotification("Connecting to $host:$port…"))
        RadioBridge.publishStatus(RadioBridge.Status.Connecting, "Connecting to $host:$port…")
        startStreaming(host, port, mount, password, bitrate)
        return START_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        serviceJob.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Core streaming loop
    // -------------------------------------------------------------------------

    private fun startStreaming(
        host: String, port: Int, mount: String, password: String, bitrate: Int,
    ) {
        streamJob?.cancel()
        streamJob = scope.launch {
            // Wait up to 3 s for the audio processor to report a format
            var waited = 0
            while (RadioBridge.audioFormat == null && waited < 30) {
                delay(100)
                waited++
            }
            val fmt = RadioBridge.audioFormat
            if (fmt == null) {
                Log.e(TAG, "No audio format available — is Velune playing?")
                val msg = "Start playback in Velune first, then try again."
                updateNotification(msg)
                RadioBridge.publishError(msg)
                return@launch
            }

            val (sampleRate, channelCount, _) = fmt
            encoder.start(sampleRate, channelCount, bitrate)

            val mountPath = if (mount.startsWith("/")) mount else "/$mount"
            val listenerUrl = "http://$host:$port$mountPath"

            try {
                icecast.connect(host, port, mount, password)
                RadioBridge.pcmQueue.clear()
                RadioBridge.publishStreamStarted(listenerUrl)
                updateNotification("⏺ Streaming to $listenerUrl")
                Log.i(TAG, "Streaming started — ${sampleRate}Hz ch=$channelCount → $listenerUrl")

                metadataPublisher = IcecastMetadataPublisher(host, port, mount, password)
                    .also { it.start() }

                runStreamLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Stream error", e)
                val msg = "Error: ${e.message ?: e.javaClass.simpleName}"
                updateNotification(msg)
                RadioBridge.publishError(msg)
            } finally {
                metadataPublisher?.stop()
                metadataPublisher = null
                encoder.stop()
                icecast.disconnect()
                RadioBridge.publishStreamStopped()
            }
        }
    }

    private suspend fun CoroutineScope.runStreamLoop() {
        var bytesSent = 0L
        var flushCounter = 0

        while (isActive) {
            // Block up to 200 ms waiting for PCM data
            val pcm = RadioBridge.pcmQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue

            val encoded = encoder.encode(pcm) ?: continue
            icecast.write(encoded)
            bytesSent += encoded.size

            // Flush every ~10 chunks for low latency without overwhelming the socket
            if (++flushCounter >= 10) {
                icecast.flush()
                flushCounter = 0
                RadioBridge.publishBytesSent(bytesSent)
            }
        }
        icecast.flush()
        Log.i(TAG, "Stream loop ended. Total bytes sent: $bytesSent")
    }

    private fun stopStreaming() {
        metadataPublisher?.stop()
        metadataPublisher = null
        streamJob?.cancel()
        streamJob = null
        encoder.stop()
        icecast.disconnect()
        RadioBridge.pcmQueue.clear()
        RadioBridge.publishStreamStopped("Stopped")
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Web Radio Streaming",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Velune web radio broadcast status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.graphic_eq)
            .setContentTitle("Velune Web Radio")
            .setContentText(status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }
}
