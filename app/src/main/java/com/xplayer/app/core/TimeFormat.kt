package com.xplayer.app.core

/** Formats a duration as `m:ss` or `h:mm:ss`. Negative or unset values render as `0:00`. */
fun formatDuration(millis: Long): String {
    val totalSeconds = if (millis < 0) 0 else millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
