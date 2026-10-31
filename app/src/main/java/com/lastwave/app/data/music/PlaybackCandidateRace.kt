package com.lastwave.app.data.music

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** An empty result is a miss; only a playable candidate wins and cancels the other path. */
internal suspend fun <T> racePlaybackCandidates(
    first: suspend () -> T?,
    second: suspend () -> T?,
    secondDelayMs: Long = 0,
): T? {
    val context = currentCoroutineContext()
    context.ensureActive()
    // NewPipe can block in synchronous HTTP even after cancellation. Linking
    // its job to this call would make coroutineScope wait for that loser before
    // returning the playable winner. Cancel both workers explicitly on every exit.
    val workers = CoroutineScope(context.minusKey(Job) + SupervisorJob())
    val firstMiss = CompletableDeferred<Unit>()
    val a = workers.async {
        first().also { if (it == null) firstMiss.complete(Unit) }
    }
    val b = workers.async {
        if (secondDelayMs > 0) withTimeoutOrNull(secondDelayMs) { firstMiss.await() }
        second()
    }
    try {
        return select {
            a.onAwait { it ?: b.await() }
            b.onAwait { it ?: a.await() }
        }
    } finally {
        workers.cancel()
    }
}
