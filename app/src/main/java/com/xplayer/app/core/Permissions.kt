package com.xplayer.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Permission differences across Android versions, in one place. */
object Permissions {
    /** READ_MEDIA_AUDIO on Android 13+, READ_EXTERNAL_STORAGE before. */
    fun audio(sdk: Int = Build.VERSION.SDK_INT): String =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audio()) == PackageManager.PERMISSION_GRANTED

    /** Runtime notification permission exists only on Android 13+. */
    fun notificationsRequired(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.TIRAMISU

    fun hasNotifications(context: Context): Boolean =
        !notificationsRequired() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

/** What the UI should show for the audio permission. */
enum class AudioAccess {
    /** Never asked: show the rationale, then the system prompt. */
    NEEDS_REQUEST,
    /** Granted: library is available. */
    GRANTED,
    /** Denied but can ask again. */
    DENIED,
    /** Denied permanently ("don't ask again"): offer to open app settings. */
    DENIED_PERMANENTLY,
}

object AudioAccessResolver {
    /**
     * @param granted current permission state
     * @param askedBefore whether the system prompt was shown at least once
     * @param shouldShowRationale `Activity.shouldShowRequestPermissionRationale`
     */
    fun resolve(granted: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): AudioAccess = when {
        granted -> AudioAccess.GRANTED
        !askedBefore -> AudioAccess.NEEDS_REQUEST
        shouldShowRationale -> AudioAccess.DENIED
        else -> AudioAccess.DENIED_PERMANENTLY
    }
}
