package dev.imirror.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

private const val CHANNEL_ID = "share_to_tv"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "dev.imirror.sender.STOP"
private const val IDLE_LIMIT_MS = 10 * 60 * 1000L

/**
 * Foreground service so the work outlives the invisible share activity. A photo is one upload.
 * A video runs a [MediaServer] on the phone and tells the TV to pull from it until stopped or idle.
 */
class SendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var server: MediaServer? = null
    private var tv: TvEndpoint? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var lastActivityAt = SystemClock.elapsedRealtime()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }
        val item = Shareable.from(intent?.type, intent?.data)
        if (item == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A new share replaces whatever was running.
        job?.cancel()
        closeServer()
        startForeground("Sending to TV", withStop = false)
        job = scope.launch {
            try {
                when (item) {
                    is Shareable.Photo -> sendPhoto(item)
                    is Shareable.Video -> playVideo(item)
                }
            } catch (e: Exception) {
                if (!isActive) return@launch
                toast("TV: ${e.message ?: e.javaClass.simpleName}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closeServer()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun sendPhoto(item: Shareable.Photo) {
        val endpoint = findTv(this) ?: return fail("TV not found")
        TvClient.putPhoto(endpoint, encodePhotoForTv(contentResolver, item.uri))
        stopSelf()
    }

    private suspend fun playVideo(item: Shareable.Video) {
        val endpoint = findTv(this) ?: return fail("TV not found")
        tv = endpoint
        val length = contentResolver.openFileDescriptor(item.uri, "r")?.use { it.statSize }
            ?.takeIf { it >= 0 } ?: return fail("Can't read this video")
        val phone = phoneAddressFacing(endpoint) ?: return fail("Not on the TV's Wi-Fi")

        val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        lastActivityAt = SystemClock.elapsedRealtime()
        val media = MediaServer(
            token = token,
            mime = item.mime,
            length = length,
            openChannel = { openChannel(item.uri) },
            onActivity = { lastActivityAt = SystemClock.elapsedRealtime() },
        )
        media.start()
        server = media
        holdAwake()

        TvClient.play(endpoint, "http://${phone.hostAddress}:${media.port}/$token")
        startForeground("Playing on TV", withStop = true)

        // delay() throws if a newer share cancels this job, so only an idle timeout reaches stopPlayback().
        while (SystemClock.elapsedRealtime() - lastActivityAt < IDLE_LIMIT_MS) delay(30_000)
        stopPlayback()
    }

    /** AutoCloseInputStream closes the descriptor together with the channel. */
    private fun openChannel(uri: Uri) =
        ParcelFileDescriptor.AutoCloseInputStream(
            checkNotNull(contentResolver.openFileDescriptor(uri, "r")) { "provider returned no file" },
        ).channel

    /** Stops the TV and tears down; runs on [scope] so it survives cancelling the playback job. */
    private fun stopPlayback() {
        job?.cancel()
        val endpoint = tv
        closeServer()
        scope.launch {
            if (endpoint != null) runCatching { TvClient.stop(endpoint) }
            stopSelf()
        }
    }

    private fun closeServer() {
        server?.close()
        server = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    /** The TV pulls video from us; with the screen off the CPU and Wi-Fi would otherwise doze mid-stream. */
    @Suppress("DEPRECATION", "WakelockTimeout") // WIFI_MODE_FULL_HIGH_PERF is deprecated but still the lock that prevents Wi-Fi power save.
    private fun holdAwake() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShareToTv:serve").apply { acquire() } // released in closeServer(), which every exit path reaches
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ShareToTv:serve").apply { acquire() }
    }

    private fun fail(message: String) {
        toast(message)
        stopSelf()
    }

    private fun toast(message: String) = main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun startForeground(text: String, withStop: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Share to TV", NotificationManager.IMPORTANCE_LOW))
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (withStop) {
            val stop = PendingIntent.getService(
                this, 0, Intent(this, SendService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Stop", stop)
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        /** The read grant rides on both data and clipData so the service keeps access after the share activity is gone. */
        fun intentFor(context: Context, item: Shareable): Intent {
            val (uri, mime) = when (item) {
                is Shareable.Photo -> item.uri to "image/*"
                is Shareable.Video -> item.uri to item.mime
            }
            return Intent(context, SendService::class.java).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
