package com.xplayer.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the timer is doing, observed by the UI. */
sealed interface SleepTimerState {
    data object Off : SleepTimerState
    /** Counting down; [remainingMs] is refreshed about once a second. */
    data class Counting(val remainingMs: Long, val totalMs: Long) : SleepTimerState
    /** Playback stops when the current track finishes. */
    data object EndOfTrack : SleepTimerState
}

/** The minimal player surface the timer needs (keeps the timer unit-testable without ExoPlayer). */
interface SleepTarget {
    fun pause()
    /** Ask the player to pause when the current media item ends (true) or to stop doing so (false). */
    fun setPauseAtEndOfItem(enabled: Boolean)
}

/**
 * Runs inside the playback service, so it works with the UI gone and the screen off. The clock is
 * based on [now] (a monotonic source in production), so delays that run late still fire correctly.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val target: SleepTarget,
    private val now: () -> Long,
    private val tickMs: Long = 1_000L,
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state

    private var job: Job? = null

    fun startCountdown(durationMs: Long) {
        require(durationMs > 0) { "duration must be positive" }
        cancel()
        val deadline = now() + durationMs
        _state.value = SleepTimerState.Counting(durationMs, durationMs)
        job = scope.launch {
            while (true) {
                val remaining = deadline - now()
                if (remaining <= 0) break
                _state.value = SleepTimerState.Counting(remaining, durationMs)
                delay(minOf(tickMs, remaining))
            }
            target.pause()
            _state.value = SleepTimerState.Off
        }
    }

    fun startEndOfTrack() {
        cancel()
        target.setPauseAtEndOfItem(true)
        _state.value = SleepTimerState.EndOfTrack
    }

    /** Called by the service when the player paused at the end of an item. */
    fun onItemEnded() {
        if (_state.value == SleepTimerState.EndOfTrack) {
            target.setPauseAtEndOfItem(false)
            _state.value = SleepTimerState.Off
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value == SleepTimerState.EndOfTrack) target.setPauseAtEndOfItem(false)
        _state.value = SleepTimerState.Off
    }
}
