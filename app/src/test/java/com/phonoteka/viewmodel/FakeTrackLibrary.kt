package com.phonoteka.viewmodel

import android.net.Uri
import com.phonoteka.data.LocalImportReport
import com.phonoteka.data.Track
import com.phonoteka.data.TrackCandidate
import com.phonoteka.data.TrackLibrary
import com.phonoteka.data.TrackStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/**
 * Deterministic in-memory [TrackLibrary]. Exposes the backing [tracks] flow so tests can push
 * live updates (Room-like behaviour) and counts every import call to assert de-duplication.
 *
 * Search/download methods are stubbed — the ViewModel tests that use this fake do not exercise
 * the network layer; they verify list/filter/pagination behaviour only.
 */
class FakeTrackLibrary(initial: List<Track> = emptyList()) : TrackLibrary {

    val tracks = MutableStateFlow(initial)

    var importFromAssetsCalls = 0
        private set
    var importLocalFilesCalls = 0
        private set

    /** Number of upcoming [importFromAssets] calls that should fail before succeeding. */
    var failImports: Int = 0

    /** When set, [importLocalFiles] throws it instead of returning [localImportReport]. */
    var localImportFailure: Exception? = null

    /** When > 0, [importLocalFiles] suspends for this long so tests can hold the call in flight. */
    var localImportDelayMs: Long = 0

    var localImportReport: LocalImportReport = LocalImportReport(linked = 0, skipped = 0, failed = 0)

    override fun getAllTracks(): Flow<List<Track>> = tracks

    override fun totalCount(): Flow<Int> = tracks.map { it.size }

    override fun doneCount(): Flow<Int> = tracks.map { list -> list.count { it.status == TrackStatus.DONE } }

    override fun errorCount(): Flow<Int> = tracks.map { list -> list.count { it.status == TrackStatus.ERROR } }

    override fun reviewCount(): Flow<Int> = tracks.map { list -> list.count { it.status == TrackStatus.REVIEW } }

    override suspend fun importFromAssets(): Int {
        importFromAssetsCalls++
        if (failImports > 0) {
            failImports--
            throw IOException("import boom")
        }
        return tracks.value.size
    }

    override suspend fun importLocalFiles(uris: List<Uri>): LocalImportReport {
        importLocalFilesCalls++
        if (localImportDelayMs > 0) delay(localImportDelayMs)
        localImportFailure?.let { throw it }
        return localImportReport
    }

    // --- Search & download stubs (not exercised by list/filter tests) ---

    private val candidatesMap = mutableMapOf<Long, List<TrackCandidate>>()

    override fun getCandidates(trackId: Long): Flow<List<TrackCandidate>> =
        MutableStateFlow(candidatesMap[trackId] ?: emptyList())

    override suspend fun searchTrack(trackId: Long, source: String): List<TrackCandidate> {
        // Simulate: move track to READY status
        val list = tracks.value.toMutableList()
        val idx = list.indexOfFirst { it.id == trackId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(status = TrackStatus.READY)
            tracks.value = list
        }
        return candidatesMap[trackId] ?: emptyList()
    }

    override suspend fun selectCandidate(trackId: Long, candidateId: String) {
        // No-op for tests
    }

    override suspend fun autoSelectCandidate(trackId: Long) {
        // No-op for tests
    }

    override suspend fun downloadTrack(trackId: Long, onProgress: (Long) -> Unit): File {
        // Simulate: move track to DONE
        val list = tracks.value.toMutableList()
        val idx = list.indexOfFirst { it.id == trackId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                status = TrackStatus.DONE,
                filePath = "/tmp/fake_$trackId.mp3",
                source = "youtube"
            )
            tracks.value = list
        }
        return File("/tmp/fake_$trackId.mp3")
    }

    override suspend fun resetErrorsToPending() {
        val list = tracks.value.map {
            if (it.status == TrackStatus.ERROR) it.copy(status = TrackStatus.PENDING, lastError = null) else it
        }
        tracks.value = list
    }

    override suspend fun addTrack(artist: String, title: String): Long {
        val nextId = (tracks.value.maxOfOrNull { it.id } ?: 0L) + 1
        tracks.value = tracks.value + Track(
            id = nextId, artist = artist, title = title,
            searchQuery = "$artist — $title"
        )
        return nextId
    }

    override suspend fun importTextFile(uri: Uri): Int {
        // Stub — tests don't exercise text file import
        return 0
    }

    override suspend fun clearAllTracks() {
        tracks.value = emptyList()
    }
}
