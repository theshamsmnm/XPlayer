package com.xplayer.app.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Custom session commands (honoured only for this app's own controllers). */
object PlaybackCommands {
    const val ACTION_START_TIMER = "com.xplayer.app.SLEEP_TIMER_START"
    const val ACTION_END_OF_TRACK = "com.xplayer.app.SLEEP_TIMER_END_OF_TRACK"
    const val ACTION_CANCEL_TIMER = "com.xplayer.app.SLEEP_TIMER_CANCEL"
    const val ARG_DURATION_MS = "duration_ms"

    val startTimer = SessionCommand(ACTION_START_TIMER, Bundle.EMPTY)
    val endOfTrack = SessionCommand(ACTION_END_OF_TRACK, Bundle.EMPTY)
    val cancelTimer = SessionCommand(ACTION_CANCEL_TIMER, Bundle.EMPTY)
}
