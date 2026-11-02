package com.phonoteka.viewmodel

import com.phonoteka.data.Track
import com.phonoteka.data.TrackStatus
import org.junit.Assert.*
import org.junit.Test

/** Pure queue/metadata contracts; actual MediaSession playback needs device tests. */
class PlaybackQueueTest {
    private fun local(id: Long) = Track(id, "Artist $id", "Song $id", TrackStatus.DONE,
        filePath = "content://media/external/audio/$id", source = "local")

    private fun downloaded(id: Long) = Track(id, "Artist $id", "Song $id", TrackStatus.DONE,
        filePath = "/storage/emulated/0/Android/data/com.phonoteka/files/downloads/$id.mp3",
        source = "youtube")

    @Test fun localDoneDocumentIsPlayable() { assertTrue(local(1).isLocallyPlayable()) }

    @Test fun downloadedFileIsPlayable() { assertTrue(downloaded(1).isLocallyPlayable()) }

    @Test fun pendingAndErrorBindingsAreNotPlayable() {
        assertFalse(local(1).copy(status = TrackStatus.PENDING).isLocallyPlayable())
        assertFalse(local(1).copy(status = TrackStatus.ERROR).isLocallyPlayable())
        assertFalse(downloaded(1).copy(status = TrackStatus.PENDING).isLocallyPlayable())
    }

    @Test fun remoteRawAndMissingUrisAreRejected() {
        listOf(null, "", "content://", "file:///a.mp3", "https://example.com/a.mp3")
            .forEach { assertFalse("Unexpected playable URI: $it", local(1).copy(filePath = it).isLocallyPlayable()) }
    }

    @Test fun queueRetainsCatalogueOrderAndExcludesUnboundTracks() {
        val tracks = listOf(local(1), local(2).copy(filePath = null), local(3), local(4))
        assertEquals(listOf(1L, 3L, 4L), localPlaybackQueue(local(3), tracks).map { it.id })
    }

    @Test fun downloadedTracksEnterQueue() {
        val tracks = listOf(downloaded(1), downloaded(2), local(3))
        assertEquals(listOf(1L, 2L, 3L), localPlaybackQueue(downloaded(1), tracks).map { it.id })
    }

    @Test fun selectedTrackUsesLatestFileBinding() {
        val fresh = local(2).copy(filePath = "content://documents/new")
        val queue = localPlaybackQueue(fresh, listOf(local(1), local(2)))
        assertEquals(fresh, queue[1])
    }

    @Test fun missingSelectedTrackIsAppendedExactlyOnce() {
        val queue = localPlaybackQueue(local(3), listOf(local(1), local(1), local(2)))
        assertEquals(listOf(1L, 2L, 3L), queue.map { it.id })
        assertEquals(listOf(local(3)), localPlaybackQueue(local(3), emptyList()))
    }

    @Test fun unplayableSelectionCannotStartAnotherTrack() {
        assertTrue(localPlaybackQueue(local(2).copy(filePath = null), listOf(local(1))).isEmpty())
    }

    @Test fun sessionMetadataRestoresIdentityAndLocalUri() {
        assertEquals(local(5), sessionTrack("5", "Artist 5", "Song 5", local(5).filePath))
    }

    @Test fun sessionMetadataRestoresDownloadedFileUri() {
        val track = sessionTrack("5", "Artist 5", "Song 5", downloaded(5).filePath)!!
        assertEquals(downloaded(5).filePath, track.filePath)
        assertTrue(track.isLocallyPlayable())
    }

    @Test fun invalidSessionIdentityIsNotInvented() {
        listOf("", "abc", "0", "-1").forEach { assertNull(sessionTrack(it, "A", "B", local(1).filePath)) }
    }

    @Test fun missingSessionUriStillDisplaysMetadataWithoutFakeBinding() {
        val track = sessionTrack("7", null, "Song", null)!!
        assertEquals("Song", track.displayName)
        assertNull(track.filePath)
        assertNull(track.source)
        assertFalse(track.isLocallyPlayable())
        assertNull(sessionTrack("7", "A", "B", "https://example.com/a.mp3")!!.filePath)
    }
}
