package dev.imirror.receiver.airplay

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import dev.imirror.receiver.util.Logger

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * like Safari or a TV app says "AirPlay this video" and POSTs a URL via `/play`). We hand the URL to
 * Android's [MediaPlayer] and render to the same streaming [Surface] the mirror decoder uses.
 *
 * Distinct from screen mirroring (H.264 over a data stream): here the TV fetches and plays the media
 * itself, and the sender only drives transport (`/rate`, `/scrub`, `/stop`) + polls `/playback-info`.
 *
 * Methods are `@Synchronized` because RTSP control verbs arrive on the RTSP thread while MediaPlayer
 * callbacks fire on its own thread.
 */
class AirPlayVideoPlayer(
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
) {
    /**
     * MediaPlayer only accepts start/pause/seek once prepared, so control verbs that arrive while
     * preparing (senders send `/rate` right after `/play`) are recorded and applied in [onPrepared].
     */
    private sealed interface State {
        val mp: MediaPlayer?
        data object Idle : State { override val mp: MediaPlayer? = null }
        class Preparing(override val mp: MediaPlayer, var seekMs: Int?, var startFraction: Double, var playing: Boolean) : State
        class Ready(override val mp: MediaPlayer) : State
    }

    private var state: State = State.Idle

    /** Starts playing [url], seeking to [startPositionFraction] (0..1 of duration) once prepared. */
    @Synchronized
    fun play(url: String, startPositionFraction: Double) {
        release()
        val fraction = startPositionFraction.coerceIn(0.0, 1.0)
        Logger.i("AirPlay video: play url=$url start=$fraction")
        val player = MediaPlayer()
        state = State.Preparing(player, seekMs = null, startFraction = fraction, playing = true)
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            surfaceProvider()?.let { player.setSurface(it) }
            player.setOnPreparedListener { onPrepared(it) }
            // StreamingScreen letterboxes from StreamStats; without this a portrait video is stretched.
            player.setOnVideoSizeChangedListener { _, w, h ->
                StreamStats.videoWidth = w
                StreamStats.videoHeight = h
            }
            player.setOnCompletionListener { if (owns(it)) { Logger.i("AirPlay video: completed"); onEnded() } }
            // An error ends the session too; otherwise the TV sits on a black screen.
            player.setOnErrorListener { mp, what, extra ->
                Logger.e("AirPlay video error what=$what extra=$extra")
                if (owns(mp)) onEnded()
                true
            }
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure { Logger.e("AirPlay video: setup failed", it); release(); onEnded() }
    }

    /** False for callbacks from a player already released or replaced. */
    @Synchronized
    private fun owns(player: MediaPlayer) = state.mp === player

    @Synchronized
    private fun onPrepared(player: MediaPlayer) {
        val s = state as? State.Preparing ?: return
        if (s.mp !== player) return
        // The Surface usually doesn't exist at setDataSource() time (the Activity creates it only
        // after CONNECTED) — attach it now that prepare has completed.
        surfaceProvider()?.let { runCatching { player.setSurface(it) } }
        val seekMs = s.seekMs ?: (s.startFraction * player.duration).toInt().takeIf { it > 0 }
        seekMs?.let { runCatching { player.seekTo(it) } }
        state = State.Ready(player)
        if (s.playing) runCatching { player.start() }
        Logger.i("AirPlay video: prepared dur=${runCatching { player.duration }.getOrDefault(0)}ms playing=${s.playing}")
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    @Synchronized
    fun setRate(rate: Float) {
        when (val s = state) {
            is State.Preparing -> s.playing = rate > 0f
            is State.Ready -> runCatching {
                if (rate <= 0f) { if (s.mp.isPlaying) s.mp.pause() }
                else { if (!s.mp.isPlaying) s.mp.start() }
            }
            State.Idle -> Unit
        }
    }

    /** Seeks to [positionSec] seconds. */
    @Synchronized
    fun scrub(positionSec: Double) {
        val ms = (positionSec * 1000).toInt()
        when (val s = state) {
            is State.Preparing -> s.seekMs = ms
            is State.Ready -> runCatching { s.mp.seekTo(ms) }
            State.Idle -> Unit
        }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    @Synchronized
    fun info(): PlaybackInfo? = when (val s = state) {
        State.Idle -> null
        is State.Preparing -> PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        is State.Ready -> {
            val dur = runCatching { s.mp.duration }.getOrDefault(0)
            val pos = runCatching { s.mp.currentPosition }.getOrDefault(0)
            val playing = runCatching { s.mp.isPlaying }.getOrDefault(false)
            PlaybackInfo(dur / 1000.0, pos / 1000.0, if (playing) 1.0 else 0.0, readyToPlay = true)
        }
    }

    /** Re-attach the streaming surface (after the Activity recreates it on foreground). */
    @Synchronized
    fun attachSurface() {
        val player = state.mp ?: return
        surfaceProvider()?.let { runCatching { player.setSurface(it) } }
    }

    @Synchronized
    fun release() {
        val player = state.mp ?: return
        state = State.Idle
        runCatching { player.stop() }
        runCatching { player.release() }
    }
}
