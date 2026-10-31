package com.lastwave.app.data.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaybackCandidateRaceTest {
    @Test fun fallbackDoesNotWaitForSlowDirectClients() = runTest {
        var cancelled = false
        val result = racePlaybackCandidates(
            first = { try { awaitCancellation() } finally { cancelled = true } },
            second = { "validated-newpipe" },
        )
        assertEquals("validated-newpipe", result)
        testScheduler.runCurrent()
        assertTrue(cancelled)
    }

    @Test fun authenticatedDirectStreamCanStillWin() = runTest {
        assertEquals("authenticated", racePlaybackCandidates(
            first = { "authenticated" }, second = { awaitCancellation() },
        ))
    }

    @Test fun emptyDirectResultWaitsForFallback() = runTest {
        val gate = CompletableDeferred<String?>()
        val result = async { racePlaybackCandidates(first = { null }, second = { gate.await() }) }
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        gate.complete("fallback")
        assertEquals("fallback", result.await())
    }

    @Test fun emptyFallbackWaitsForDirect() = runTest {
        val gate = CompletableDeferred<String?>()
        val result = async { racePlaybackCandidates(first = { gate.await() }, second = { null }) }
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        gate.complete("direct")
        assertEquals("direct", result.await())
    }

    @Test fun bothMissesReturnNull() = runTest {
        assertNull(racePlaybackCandidates<String>(first = { null }, second = { null }))
    }

    @Test fun winnerDoesNotWaitForBlockedFallbackCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanedUp = CompletableDeferred<Unit>()
        val result = async {
            racePlaybackCandidates(
                first = { started.await(); "direct" },
                second = {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            releaseCleanup.await()
                            cleanedUp.complete(Unit)
                        }
                    }
                },
            )
        }
        testScheduler.runCurrent()
        assertTrue(result.isCompleted)
        assertEquals("direct", result.await())
        assertFalse(cleanedUp.isCompleted)
        releaseCleanup.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(cleanedUp.isCompleted)
    }

    @Test fun fastPrimaryDoesNotStartFallback() = runTest {
        var fallbackStarted = false
        assertEquals("direct", racePlaybackCandidates(
            first = { delay(100); "direct" },
            second = { fallbackStarted = true; "fallback" },
            secondDelayMs = 1_500,
        ))
        testScheduler.runCurrent()
        assertFalse(fallbackStarted)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test fun primaryMissStartsFallbackWithoutWaitingForHedge() = runTest {
        assertEquals("fallback", racePlaybackCandidates(
            first = { delay(25); null },
            second = { "fallback" },
            secondDelayMs = 1_500,
        ))
        assertEquals(25L, testScheduler.currentTime)
    }

    @Test fun slowPrimaryStartsFallbackAtHedgeDeadline() = runTest {
        assertEquals("fallback", racePlaybackCandidates(
            first = { awaitCancellation() },
            second = { "fallback" },
            secondDelayMs = 1_500,
        ))
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test fun cancellingCallerCancelsBothWorkers() = runTest {
        val directCancelled = CompletableDeferred<Unit>()
        val fallbackCancelled = CompletableDeferred<Unit>()
        val race = launch {
            racePlaybackCandidates(
                first = { try { awaitCancellation() } finally { directCancelled.complete(Unit) } },
                second = { try { awaitCancellation() } finally { fallbackCancelled.complete(Unit) } },
            )
        }
        testScheduler.runCurrent()
        race.cancel()
        testScheduler.runCurrent()
        assertTrue(directCancelled.isCompleted)
        assertTrue(fallbackCancelled.isCompleted)
    }
}
