package com.lastwave.app.data.feed

import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.HomePage
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeedRepositoryProgressTest {
    @Test
    fun availableSongsAppearWhileNewReleasesAreStillLoading() = runBlocking {
        val api = mockk<InnerTubeMusicApi>(relaxed = true)
        val auth = mockk<YtMusicAuthManager>()
        val settings = mockk<SettingsPreferences>()
        coEvery { auth.awaitLoadedConnection() } returns YtConnection()
        every { settings.settings } returns MutableStateFlow(MiscSettings(podcastsEnabled = false))
        val slowReleases = CompletableDeferred<List<YouTubePlaylistSummary>>()
        val song = YouTubeMusicTrack("video", "Song", "Artist")
        coEvery { api.fetchNewReleases() } coAnswers { slowReleases.await() }
        coEvery { api.fetchHomeSongs() } returns listOf(song)
        coEvery { api.fetchHomePage() } returns HomePage()
        val repository = FeedRepository(
            api, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), auth, mockk(relaxed = true),
            mockk(relaxed = true), settings,
        )
        val preview = CompletableDeferred<FeedData>()
        val load = launch {
            repository.loadFeed(null) { data ->
                if (data.quickPicks.isNotEmpty()) preview.complete(data)
            }
        }
        try {
            val data = withTimeout(5_000L) { preview.await() }
            assertEquals(listOf(song), data.quickPicks)
            assertFalse("The unrelated slow request must still be pending", slowReleases.isCompleted)
            assertFalse("Preview must precede the full feed", load.isCompleted)
        } finally {
            load.cancelAndJoin()
        }
    }
}
