package com.xplayer.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.xplayer.app.domain.model.ArtworkRef
import com.xplayer.app.domain.model.TrackItem
import com.xplayer.app.domain.sort.Reorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class QueueEntry(
    val index: Int,
    val trackId: Long,
    val title: String,
    val artist: String,
    val art: ArtworkRef?,
    val isCurrent: Boolean,
)

@Immutable
data class NowPlaying(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val art: ArtworkRef?,
    val durationMs: Long,
)

/** Immutable snapshot of the *service's* player, as seen by the UI. Never the source of truth. */
@Immutable
data class PlayerUiState(
    val connected: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
) {
    val hasQueue: Boolean get() = queue.isNotEmpty()
}

/**
 * UI-side handle to the playback service (a MediaController). All mutations are commands sent to
 * the service; this class only mirrors the service state into a [StateFlow].
 *
 * Commands issued before the controller finished connecting are queued and replayed, so e.g. tapping
 * a track immediately after launch is never lost.
 */
class PlaybackController(private val context: Context) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { return@addListener }
            controller = c
            c.addListener(listener)
            refresh(c)
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        pending.clear()
        _state.value = PlayerUiState()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { pending.addLast(block); connect() }
    }

    // ---- Playback commands -----------------------------------------------------------------------

    /** Replaces the queue with [tracks] and starts at [startIndex]. */
    fun playTracks(tracks: List<TrackItem>, startIndex: Int = 0, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        withController { c ->
            c.setMediaItems(tracks.map { MediaItems.from(it) }, startIndex.coerceIn(0, tracks.lastIndex), 0L)
            if (shuffle != null) c.shuffleModeEnabled = shuffle
            c.prepare()
            c.play()
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    /** Standard behaviour: restart the track if more than 3 s in, otherwise go to the previous one. */
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Cycles OFF -> ALL -> ONE -> OFF. */
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---- Queue ---------------------------------------------------------------------------------------

    fun playNext(tracks: List<TrackItem>) = withController { c ->
        val items = tracks.map { MediaItems.from(it) }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items); c.prepare(); c.play()
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        }
    }

    fun addToQueue(tracks: List<TrackItem>) = withController { c ->
        val items = tracks.map { MediaItems.from(it) }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items); c.prepare(); c.play()
        } else {
            c.addMediaItems(items)
        }
    }

    fun removeFromQueue(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount && from != to) c.moveMediaItem(from, to)
    }

    fun skipToQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    /** Keeps the current track and removes every other queue entry. */
    fun clearUpcoming() = withController { c ->
        if (c.mediaItemCount > 1) {
            val cur = c.currentMediaItemIndex
            if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
            if (cur > 0) c.removeMediaItems(0, cur)
        }
    }

    /**
     * Re-points queue items at new artwork URIs after the user changed artwork. ExoPlayer applies a
     * metadata-only change without interrupting playback, so the notification, lock screen and the
     * in-app players all pick up the new artwork immediately.
     */
    fun refreshArtwork(artByTrackId: Map<Long, ArtworkRef>) = withController { c ->
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            val art = item.mediaId.toLongOrNull()?.let { artByTrackId[it] } ?: continue
            val newUri = ArtworkUri.build(art)
            if (item.mediaMetadata.artworkUri == newUri) continue
            c.replaceMediaItem(
                i,
                item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(newUri).build()).build(),
            )
        }
    }

    // ---- Sleep timer (executed inside the service) -------------------------------------------------

    fun startSleepTimer(durationMs: Long) = withController { c ->
        c.sendCustomCommand(PlaybackCommands.startTimer, Bundle().apply { putLong(PlaybackCommands.ARG_DURATION_MS, durationMs) })
    }

    fun sleepAfterCurrentTrack() = withController { it.sendCustomCommand(PlaybackCommands.endOfTrack, Bundle.EMPTY) }
    fun cancelSleepTimer() = withController { it.sendCustomCommand(PlaybackCommands.cancelTimer, Bundle.EMPTY) }

    // ---- Position (polled by the UI only while visible; not part of the state flow) -----------------------

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L
    fun bufferedDurationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    // ---- State mirroring -------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }
    }

    private fun refresh(player: Player) {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        val queue = ArrayList<QueueEntry>(count)
        for (i in 0 until count) {
            val item = player.getMediaItemAt(i)
            queue += item.toEntry(i, i == current)
        }
        val currentItem = if (current in 0 until count) player.getMediaItemAt(current) else null
        val duration = player.duration.takeIf { it > 0 }
            ?: currentItem?.mediaMetadata?.extras?.getLong(MediaItems.EXTRA_DURATION)
            ?: 0L
        val now = currentItem?.let { item ->
            val m = item.mediaMetadata
            item.mediaId.toLongOrNull()?.let { id ->
                NowPlaying(
                    trackId = id,
                    title = m.title?.toString().orEmpty(),
                    artist = m.artist?.toString().orEmpty(),
                    album = m.albumTitle?.toString().orEmpty(),
                    art = ArtworkUri.parse(m.artworkUri),
                    durationMs = duration,
                )
            }
        }
        _state.update {
            PlayerUiState(
                connected = true,
                nowPlaying = now,
                isPlaying = player.isPlaying,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                queue = queue,
                currentIndex = if (current in 0 until count) current else -1,
            )
        }
    }

    private fun MediaItem.toEntry(index: Int, isCurrent: Boolean) = QueueEntry(
        index = index,
        trackId = mediaId.toLongOrNull() ?: -1L,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        art = ArtworkUri.parse(mediaMetadata.artworkUri),
        isCurrent = isCurrent,
    )

    companion object {
        /** Pure helper (tested): where an item moved by [Reorder.move] ends up in the display order. */
        fun <T> previewMove(list: List<T>, from: Int, to: Int): List<T> = Reorder.move(list, from, to)
    }
}
