package com.xplayer.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Playback state restoration: where to resume after unavailable tracks were dropped from a saved queue. */
class QueueStoreTest {
    @Test fun keepsCurrentTrackWhenStillPresent() {
        assertEquals(1, QueueStore.remapIndex(listOf(10, 20, 30), 2, listOf(10, 30)))
    }

    @Test fun movesToNextSurvivingTrackWhenCurrentIsGone() {
        assertEquals(1, QueueStore.remapIndex(listOf(10, 20, 30, 40), 1, listOf(10, 30, 40)))
    }

    @Test fun fallsBackToLastWhenNothingFollows() {
        assertEquals(1, QueueStore.remapIndex(listOf(10, 20, 30), 2, listOf(10, 20)))
    }

    @Test fun emptyQueueIsZero() {
        assertEquals(0, QueueStore.remapIndex(listOf(1), 0, emptyList()))
    }
}
