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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

private const val TAG = "ShareToTv"
private const val CHANNEL_ID = "share_to_tv"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "dev.imirror.sender.STOP"

/** What we have on the TV. Only the main thread reads or writes it, and the notification follows it. */
private sealed interface OnTv {
    data object Nothing : OnTv
    data object Sending : OnTv
    data class Photo(val tv: TvEndpoint) : OnTv
    class Video(val playback: VideoPlayback) : OnTv
}

/**
 * Foreground service so the work outlives the invisible share activity. A photo is one upload, then the
 * notification stays as its Close button. A video holds a [VideoPlayback] until the TV finishes or Stop.
 * A new share replaces whatever is on the TV.
 */
class SendService : Service() {
    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var onTv: OnTv = OnTv.Nothing

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        val item = Shareable.from(intent?.type, intent?.data)
        if (item == null) {
            if (onTv == OnTv.Nothing) stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        enter(OnTv.Sending)
        job = scope.launch {
            try {
                when (item) {
                    is Shareable.Photo -> sendPhoto(item)
                    is Shareable.Video -> playVideo(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "send failed", e)
                toast(if (e is ShareError) e.message.orEmpty() else "TV: ${e.message ?: e.javaClass.simpleName}")
                enter(OnTv.Nothing)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        (onTv as? OnTv.Video)?.playback?.close()
    }

    private suspend fun sendPhoto(item: Shareable.Photo) {
        val tv = findTv(this) ?: throw ShareError("TV not found")
        withContext(Dispatchers.IO) { TvSession.putPhoto(tv, encodePhotoForTv(contentResolver, item.uri)) }
        enter(OnTv.Photo(tv))
    }

    private suspend fun playVideo(item: Shareable.Video) {
        val tv = findTv(this) ?: throw ShareError("TV not found")
        // Not cancellable, so a share replaced mid-start still gets its playback back to close.
        val playback = withContext(NonCancellable + Dispatchers.IO) { VideoPlayback.start(this@SendService, tv, item) }
        if (!currentCoroutineContext().isActive) {
            playback.close()
            return
        }
        enter(OnTv.Video(playback))
        playback.awaitEnd()
        enter(OnTv.Nothing)
    }

    /** Takes our video or photo off the TV and ends the service. */
    private fun stop() {
        job?.cancel()
        val photo = (onTv as? OnTv.Photo)?.tv
        enter(OnTv.Nothing) // closing a video's connection already ends it on the TV
        // The service is going away, so a plain thread rather than its scope.
        if (photo != null) thread { runCatching { TvSession.clearPhoto(photo) } }
    }

    /** The only writer of [onTv]: releases the old state's resources and shows the new state's notification. */
    private fun enter(next: OnTv) {
        (onTv as? OnTv.Video)?.playback?.close()
        onTv = next
        when (next) {
            OnTv.Nothing -> stopSelf()
            OnTv.Sending -> startForeground("Sending to TV", stopLabel = null)
            is OnTv.Photo -> startForeground("Showing on TV", stopLabel = "Close")
            is OnTv.Video -> startForeground("Playing on TV", stopLabel = "Stop")
        }
    }

    private fun toast(message: String) = main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    /**
     * [stopLabel] adds that action; tapping or dismissing the notification does the same, since some
     * phones (vivo) collapse low-priority notifications and hide their buttons. Null for none.
     */
    private fun startForeground(text: String, stopLabel: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Share to TV", NotificationManager.IMPORTANCE_LOW))
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (stopLabel != null) {
            val stop = PendingIntent.getService(
                this, 0, Intent(this, SendService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, stopLabel, stop)
                .setContentText("Tap to ${stopLabel.lowercase()}")
                .setContentIntent(stop)
                .setDeleteIntent(stop)
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
