package com.xplayer.app.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private class FakeTarget : SleepTarget {
        var pauses = 0
        var pauseAtEnd = false
        override fun pause() { pauses++ }
        override fun setPauseAtEndOfItem(enabled: Boolean) { pauseAtEnd = enabled }
    }

    @Test fun countdownPausesExactlyOnceAndReportsRemainingTime() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, target, now = { currentTime })
        timer.startCountdown(10_000)
        advanceTimeBy(1)
        val s = timer.state.value as SleepTimerState.Counting
        assertTrue(s.remainingMs in 9_000..10_000)
        advanceTimeBy(9_000)
        assertEquals(0, target.pauses)
        advanceTimeBy(1_100)
        assertEquals(1, target.pauses)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test fun cancelStopsTheCountdown() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, target, now = { currentTime })
        timer.startCountdown(5_000)
        advanceTimeBy(2_000)
        timer.cancel()
        advanceTimeBy(10_000)
        assertEquals(0, target.pauses)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test fun restartingReplacesThePreviousTimer() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, target, now = { currentTime })
        timer.startCountdown(2_000)
        advanceTimeBy(1_000)
        timer.startCountdown(10_000)
        advanceTimeBy(5_000)
        assertEquals(0, target.pauses)
        advanceTimeBy(6_000)
        assertEquals(1, target.pauses)
    }

    @Test fun endOfTrackArmsAndClearsThePlayerFlag() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, target, now = { currentTime })
        timer.startEndOfTrack()
        assertTrue(target.pauseAtEnd)
        assertEquals(SleepTimerState.EndOfTrack, timer.state.value)
        timer.onItemEnded()
        assertFalse(target.pauseAtEnd)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test fun cancellingEndOfTrackDisarmsThePlayer() = runTest {
        val target = FakeTarget()
        val timer = SleepTimer(backgroundScope, target, now = { currentTime })
        timer.startEndOfTrack()
        timer.cancel()
        assertFalse(target.pauseAtEnd)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveDuration() {
        SleepTimer(TestScope(), FakeTarget(), now = { 0 }).startCountdown(0)
    }
}
