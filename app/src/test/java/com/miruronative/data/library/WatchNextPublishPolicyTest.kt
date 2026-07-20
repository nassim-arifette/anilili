package com.miruronative.data.library

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextPublishPolicyTest {
    private val episodeOne = WatchNextContent(1.0, "allanime", "sub")

    @Test
    fun `repeated progress for the same content is throttled`() {
        val previous = WatchNextPublishState(episodeOne, publishedAtMs = 1_000L)

        assertFalse(shouldPublishWatchNext(previous, episodeOne, nowMs = 30_000L, throttleMs = 60_000L))
        assertTrue(shouldPublishWatchNext(previous, episodeOne, nowMs = 61_000L, throttleMs = 60_000L))
    }

    @Test
    fun `episode changes bypass the progress throttle`() {
        val previous = WatchNextPublishState(episodeOne, publishedAtMs = 1_000L)
        val episodeTwo = episodeOne.copy(episodeNumber = 2.0)

        assertTrue(shouldPublishWatchNext(previous, episodeTwo, nowMs = 2_000L, throttleMs = 60_000L))
    }

    @Test
    fun `route changes bypass the progress throttle`() {
        val previous = WatchNextPublishState(episodeOne, publishedAtMs = 1_000L)

        assertTrue(
            shouldPublishWatchNext(
                previous,
                episodeOne.copy(provider = "kaa", category = "dub"),
                nowMs = 2_000L,
                throttleMs = 60_000L,
            ),
        )
    }

    @Test
    fun `work that has not started is rejected after a newer save`() {
        val coordinator = WatchNextPublishCoordinator()
        val episodeOneRequest = coordinator.register(anilistId = 42)
        val episodeTwoRequest = coordinator.register(anilistId = 42)
        val published = mutableListOf<Double>()

        assertTrue(coordinator.runIfLatest(episodeTwoRequest) { published += 2.0 })
        assertFalse(coordinator.runIfLatest(episodeOneRequest) { published += 1.0 })

        assertEquals(listOf(2.0), published)
    }

    @Test
    fun `newer save is the final write when an older provider call is already running`() {
        val coordinator = WatchNextPublishCoordinator()
        val episodeOneRequest = coordinator.register(anilistId = 42)
        val olderStarted = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val olderReleasedInTime = AtomicBoolean()
        val published = Collections.synchronizedList(mutableListOf<Double>())

        val olderWorker = thread {
            coordinator.runIfLatest(episodeOneRequest) {
                olderStarted.countDown()
                olderReleasedInTime.set(releaseOlder.await(2, TimeUnit.SECONDS))
                if (olderReleasedInTime.get()) published += 1.0
            }
        }
        assertTrue(olderStarted.await(2, TimeUnit.SECONDS))

        val episodeTwoRequest = coordinator.register(anilistId = 42)
        val newerWorker = thread {
            coordinator.runIfLatest(episodeTwoRequest) { published += 2.0 }
        }
        releaseOlder.countDown()
        olderWorker.join(2_000)
        newerWorker.join(2_000)

        assertFalse(olderWorker.isAlive)
        assertFalse(newerWorker.isAlive)
        assertTrue(olderReleasedInTime.get())
        assertEquals(listOf(1.0, 2.0), published)
    }
}
