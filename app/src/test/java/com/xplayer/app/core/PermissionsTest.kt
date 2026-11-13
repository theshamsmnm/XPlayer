package com.xplayer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Permission-denied states and OS-version differences. */
class PermissionsTest {
    @Test fun audioPermissionDependsOnSdk() {
        assertEquals("android.permission.READ_MEDIA_AUDIO", Permissions.audio(33))
        assertEquals("android.permission.READ_MEDIA_AUDIO", Permissions.audio(35))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", Permissions.audio(32))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", Permissions.audio(26))
    }

    @Test fun notificationPermissionOnlyOnAndroid13() {
        assertFalse(Permissions.notificationsRequired(32))
        assertTrue(Permissions.notificationsRequired(33))
    }

    @Test fun accessStates() {
        assertEquals(AudioAccess.GRANTED, AudioAccessResolver.resolve(true, true, false))
        assertEquals(AudioAccess.NEEDS_REQUEST, AudioAccessResolver.resolve(false, false, false))
        assertEquals(AudioAccess.DENIED, AudioAccessResolver.resolve(false, true, true))
        assertEquals(AudioAccess.DENIED_PERMANENTLY, AudioAccessResolver.resolve(false, true, false))
    }
}
