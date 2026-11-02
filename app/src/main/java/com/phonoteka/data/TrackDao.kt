package com.phonoteka.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY orderIndex ASC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM candidates WHERE trackId = :trackId ORDER BY score DESC")
    fun getCandidates(trackId: Long): Flow<List<CandidateEntity>>

    @Query("SELECT * FROM candidates WHERE trackId = :trackId ORDER BY score DESC")
    suspend fun snapshotCandidates(trackId: Long): List<CandidateEntity>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    fun getTrackById(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE status = :status ORDER BY orderIndex ASC")
    fun getByStatus(status: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE artist LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' OR searchQuery LIKE '%' || :query || '%' ORDER BY orderIndex ASC")
    fun search(query: String): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks")
    fun totalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE status = 'done'")
    fun doneCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE status = 'error'")
    fun errorCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks WHERE status = 'ready'")
    /** «Проверка» на главной: кандидат найден (ready), трек ждёт проверки/скачивания. */
    fun reviewCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSingle(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(candidates: List<CandidateEntity>)

    @Query("DELETE FROM candidates WHERE trackId = :trackId")
    suspend fun clearCandidates(trackId: Long)

    @Query("UPDATE tracks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE tracks SET status = :status, filePath = :filePath, source = :source, sha256 = :sha256, durationSec = :duration, candidateArtist = :cArtist, candidateTitle = :cTitle WHERE id = :id")
    suspend fun updateDone(id: Long, status: String, filePath: String?, source: String?, sha256: String?, duration: Int?, cArtist: String?, cTitle: String?)

    @Query("UPDATE tracks SET status = :status, lastError = :error WHERE id = :id")
    suspend fun updateError(id: Long, status: String, error: String?)

    @Query("UPDATE tracks SET status = :status, candidateArtist = :cArtist, candidateTitle = :cTitle WHERE id = :id")
    suspend fun updateCandidate(id: Long, status: String, cArtist: String?, cTitle: String?)

    @Query("UPDATE tracks SET status = :status, selectedCandidateId = :candidateId, manual = :manual WHERE id = :id")
    suspend fun selectCandidate(id: Long, status: String, candidateId: String?, manual: Boolean)

    @Query("DELETE FROM tracks")
    suspend fun clearAll()

    @Query("SELECT MAX(orderIndex) FROM tracks")
    suspend fun maxOrderIndex(): Int?

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun countRows(): Int

    @Query("SELECT * FROM tracks ORDER BY orderIndex ASC")
    suspend fun snapshot(): List<TrackEntity>

    @Query("SELECT * FROM candidates")
    suspend fun snapshotAllCandidates(): List<CandidateEntity>

    @Transaction
    suspend fun replaceLibrary(tracks: List<TrackEntity>, candidates: List<CandidateEntity>) {
        clearAll()
        if (tracks.isNotEmpty()) insertAll(tracks)
        if (candidates.isNotEmpty()) insertCandidates(candidates)
    }

    @Transaction
    suspend fun insertInitialIfEmpty(tracks: List<TrackEntity>): Int {
        if (countRows() != 0) return 0
        insertAll(tracks)
        return tracks.size
    }

    @Query("UPDATE tracks SET status = 'done', filePath = :uri, source = 'local', sha256 = NULL, durationSec = :duration, candidateArtist = :artist, candidateTitle = :title, lastError = NULL WHERE id = :id")
    suspend fun attachLocal(id: Long, uri: String, duration: Int, artist: String?, title: String?): Int

    @Query("UPDATE tracks SET status = 'pending', filePath = NULL, source = NULL, sha256 = NULL, durationSec = NULL, candidateArtist = NULL, candidateTitle = NULL, lastError = NULL WHERE sha256 GLOB 'fake_sha256_*'")
    suspend fun clearKnownDemoFiles()

    @Query("UPDATE tracks SET status = 'pending', lastError = NULL WHERE status = 'error'")
    suspend fun resetErrors()
}
