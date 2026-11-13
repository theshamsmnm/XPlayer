package com.xplayer.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.xplayer.app.MainActivity
import com.xplayer.app.R
import com.xplayer.app.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The single source of truth for playback. The activity only *controls* this service through a
 * MediaController; playback, queue, sleep timer, audio focus and noisy-audio handling all live here
 * and keep working when the UI is destroyed.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var sleepTimer: SleepTimer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null
    private var positionJob: Job? = null
    private var lastRecordedMediaId: String? = null
    private var errorStreak = 0

    override fun onCreate() {
        super.onCreate()
        val container = appContainer

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pauses/ducks on calls, other apps, assistant
            )
            .setHandleAudioBecomingNoisy(true) // pauses when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        sleepTimer = SleepTimer(
            scope = scope,
            target = object : SleepTarget {
                override fun pause() = player.pause()
                override fun setPauseAtEndOfItem(enabled: Boolean) {
                    player.pauseAtEndOfMediaItems = enabled
                }
            },
            now = { SystemClock.elapsedRealtime() },
        )
        scope.launch { sleepTimer.state.collect { container.sleepTimerState.value = it } }

        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .setBitmapLoader(ArtworkBitmapLoader(container.artwork, scope))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also {
                it.setSmallIcon(R.drawable.ic_notification)
            },
        )

        scope.launch { restorePlayback() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops playback only if nothing is playing; music keeps going otherwise. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            persistNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistNow()
        sleepTimer.cancel()
        positionJob?.cancel()
        persistJob?.cancel()
        player.removeListener(playerListener)
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Session callback ----------------------------------------------------------------------

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val own = controller.packageName == packageName
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                if (own) {
                    add(PlaybackCommands.startTimer)
                    add(PlaybackCommands.endOfTrack)
                    add(PlaybackCommands.cancelTimer)
                }
            }.build()
            val playerCommands = Player.Commands.Builder().addAllCommands().apply {
                if (!own) {
                    // External controllers (system UI, Bluetooth, other apps) get transport control only.
                    remove(Player.COMMAND_SET_MEDIA_ITEM)
                    remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                PlaybackCommands.ACTION_START_TIMER -> {
                    val duration = args.getLong(PlaybackCommands.ARG_DURATION_MS, 0L)
                    if (duration <= 0L) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    sleepTimer.startCountdown(duration)
                }
                PlaybackCommands.ACTION_END_OF_TRACK -> sleepTimer.startEndOfTrack()
                PlaybackCommands.ACTION_CANCEL_TIMER -> sleepTimer.cancel()
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Media-button "play" after the process was killed: bring back the saved queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val restored = loadRestoredQueue()
                ?: throw UnsupportedOperationException("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(restored.items, restored.index, restored.positionMs)
        }
    }

    // ---- Restoration -------------------------------------------------------------------------------

    private class Restored(val items: List<MediaItem>, val index: Int, val positionMs: Long)

    private suspend fun loadRestoredQueue(): Restored? {
        val container = appContainer
        val saved = container.queueStore.restore() ?: return null
        val tracks = container.music.tracksByIds(saved.trackIds)
        if (tracks.isEmpty()) return null
        val index = QueueStore.remapIndex(saved.trackIds, saved.index, tracks.map { it.id })
        val position = if (tracks[index].id == saved.trackIds.getOrNull(saved.index)) saved.positionMs else 0L
        return Restored(tracks.map { MediaItems.from(it) }, index, position)
    }

    private suspend fun restorePlayback() {
        val container = appContainer
        val settings = container.settings.current()
        player.shuffleModeEnabled = settings.shuffle
        player.repeatMode = settings.repeatMode
        if (!settings.resumePlayback || player.mediaItemCount > 0) return
        val restored = loadRestoredQueue() ?: return
        if (player.mediaItemCount == 0) { // a controller may have started something meanwhile
            player.setMediaItems(restored.items, restored.index, restored.positionMs)
            player.prepare() // stays paused: restoring never starts audio by itself
        }
    }

    // ---- Player events -----------------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                errorStreak = 0
                recordPlayIfNew()
                startPositionSaver()
            } else {
                positionJob?.cancel()
                persistPositionNow()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastRecordedMediaId = null
            if (player.isPlaying) recordPlayIfNew()
            schedulePersistQueue()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            schedulePersistQueue()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            appContainer.appScope.launch { appContainer.settings.setShuffle(shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            appContainer.appScope.launch { appContainer.settings.setRepeatMode(repeatMode) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleepTimer.onItemEnded()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // A file that vanished or is unreadable must not stall the queue: skip to the next one.
            errorStreak++
            if (player.hasNextMediaItem() && errorStreak <= player.mediaItemCount) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private fun recordPlayIfNew() {
        val item = player.currentMediaItem ?: return
        if (item.mediaId == lastRecordedMediaId) return
        lastRecordedMediaId = item.mediaId
        val id = item.mediaId.toLongOrNull() ?: return
        appContainer.appScope.launch { appContainer.music.recordPlay(id) }
    }

    private fun schedulePersistQueue() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistQueueNow()
        }
    }

    private fun currentTrackIds(): List<Long> = buildList {
        for (i in 0 until player.mediaItemCount) {
            player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { add(it) }
        }
    }

    private fun persistQueueNow() {
        val ids = currentTrackIds()
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        appContainer.appScope.launch {
            appContainer.queueStore.saveQueue(ids)
            appContainer.queueStore.savePosition(index, position)
        }
    }

    private fun persistPositionNow() {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        appContainer.appScope.launch { appContainer.queueStore.savePosition(index, position) }
    }

    private fun startPositionSaver() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                delay(POSITION_SAVE_INTERVAL_MS)
                persistPositionNow()
            }
        }
    }

    /** Synchronous final flush so the last queue/position survives the service being destroyed. */
    private fun persistNow() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val ids = currentTrackIds()
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        runBlocking(Dispatchers.IO) {
            appContainer.queueStore.saveQueue(ids)
            appContainer.queueStore.savePosition(index, position)
        }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 750L
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
