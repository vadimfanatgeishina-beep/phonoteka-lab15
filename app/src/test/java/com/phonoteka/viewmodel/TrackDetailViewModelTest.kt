package com.phonoteka.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.phonoteka.data.PhonotekaRepository
import com.phonoteka.data.Track
import com.phonoteka.data.TrackStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Unit tests for [TrackDetailViewModel].
 *
 * [PhonotekaRepository] is a final Kotlin class; it is mocked with Mockito 5 (inline mock maker)
 * via mockito-kotlin. The repository API is a mix of a cold [kotlinx.coroutines.flow.Flow]
 * (`getTrackById`) and suspend calls (`importFromAssets`, `attachLocalFile`, `updateStatus`),
 * so stubbing uses `whenever(...).thenReturn(...)` for the flow and `doSuspendableAnswer { ... }`
 * to gate/inspect the suspend calls deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var store: ViewModelStore
    private lateinit var repo: PhonotekaRepository
    private val uri: Uri = mock()

    @Before
    fun setUp() {
        store = ViewModelStore()
        repo = mock()
        whenever(repo.getTrackById(any())).thenReturn(flowOf(null))
        whenever(repo.getCandidates(any())).thenReturn(flowOf(emptyList()))
    }

    /** Clearing the store cancels viewModelScope, so no collector/attach job leaks between tests. */
    @After
    fun tearDown() {
        store.clear()
    }

    private fun track(
        id: Long = 1L,
        status: TrackStatus = TrackStatus.DONE
    ) = Track(
        id = id,
        artist = "Artist",
        title = "Song",
        status = status,
        filePath = "content://local/$id",
        source = "local"
    )

    private fun create(trackId: Long = 1L): TrackDetailViewModel =
        ViewModelProvider(store, TrackDetailViewModel.Factory(repo, trackId))[TrackDetailViewModel::class.java]

    /** Subscribes the while-subscribed uiState eagerly (Unconfined) and records every emission. */
    private fun TestScope.collectStates(vm: TrackDetailViewModel): MutableList<DetailUiState> {
        val states = mutableListOf<DetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { states.add(it) }
        }
        return states
    }

    private fun List<DetailUiState>.success(): DetailUiState.Success {
        val last = last()
        assertTrue("expected Success but was $last", last is DetailUiState.Success)
        return last as DetailUiState.Success
    }

    // --- uiState: loading / success / not found -----------------------------

    @Test
    fun uiState_startsLoading_thenSuccess() = runTest(mainRule.testDispatcher) {
        whenever(repo.importFromAssets()).thenReturn(0)
        whenever(repo.getTrackById(1L)).thenReturn(flowOf(track(id = 1L)))
        val vm = create(trackId = 1L)
        val states = collectStates(vm)

        advanceUntilIdle()

        assertEquals(DetailUiState.Loading, states.first())
        assertEquals(1L, states.success().track.id)
        verify(repo, times(1)).importFromAssets()
    }

    @Test
    fun uiState_missingTrack_showsNotFound() = runTest(mainRule.testDispatcher) {
        whenever(repo.importFromAssets()).thenReturn(0)
        whenever(repo.getTrackById(7L)).thenReturn(flowOf<Track?>(null))
        val vm = create(trackId = 7L)
        val states = collectStates(vm)

        advanceUntilIdle()

        assertEquals(DetailUiState.Loading, states.first())
        assertTrue("expected NotFound but was ${states.last()}", states.last() is DetailUiState.NotFound)
        verify(repo, times(1)).importFromAssets()
    }

    @Test
    fun uiState_successOnlyAfterInitialImportCompletes() = runTest(mainRule.testDispatcher) {
        val importGate = CompletableDeferred<Int>()
        val order = mutableListOf<String>()
        whenever(repo.importFromAssets()).doSuspendableAnswer {
            importGate.await()
            order.add("import")
            0
        }
        whenever(repo.getTrackById(1L)).thenReturn(
            flow {
                order.add("track")
                emit(track(id = 1L))
            }
        )
        val vm = create(trackId = 1L)
        val states = collectStates(vm)

        // The import is still in flight: the track flow must not be collected yet.
        advanceUntilIdle()
        assertEquals(DetailUiState.Loading, states.last())
        assertTrue("upstream collected before import completed: $order", order.isEmpty())
        verify(repo, times(1)).importFromAssets()

        importGate.complete(0)
        advanceUntilIdle()

        assertEquals(listOf("import", "track"), order)
        assertEquals(1L, states.success().track.id)
    }

    // --- uiState: error -----------------------------------------------------

    @Test
    fun importFailure_showsError() = runTest(mainRule.testDispatcher) {
        whenever(repo.importFromAssets()).doSuspendableAnswer { throw IOException("import boom") }
        whenever(repo.getTrackById(1L)).thenReturn(flowOf(track(id = 1L)))
        val vm = create(trackId = 1L)
        val states = collectStates(vm)

        advanceUntilIdle()

        val last = states.last()
        assertTrue("expected Error but was $last", last is DetailUiState.Error)
        assertEquals("import boom", (last as DetailUiState.Error).message)
    }

    @Test
    fun trackFlowFailure_showsError() = runTest(mainRule.testDispatcher) {
        whenever(repo.importFromAssets()).thenReturn(0)
        whenever(repo.getTrackById(1L)).thenReturn(flow { throw IOException("flow boom") })
        val vm = create(trackId = 1L)
        val states = collectStates(vm)

        advanceUntilIdle()

        val last = states.last()
        assertTrue("expected Error but was $last", last is DetailUiState.Error)
        assertEquals("flow boom", (last as DetailUiState.Error).message)
    }

    // --- attachAudio: double-tap de-duplication -----------------------------

    @Test
    fun attachAudio_secondTapWhileFirstInFlight_isIgnored() = runTest(mainRule.testDispatcher) {
        val attachGate = CompletableDeferred<Unit>()
        whenever(repo.attachLocalFile(eq(1L), any())).doSuspendableAnswer { attachGate.await() }
        val vm = create(trackId = 1L)

        vm.attachAudio(uri)
        runCurrent() // let the launched coroutine reach the (gated) repository call
        assertTrue("expected busy while attach is in flight", vm.isAttaching.value)

        vm.attachAudio(uri) // double tap: de-duplicated synchronously by the AtomicBoolean
        runCurrent()
        verify(repo, times(1)).attachLocalFile(1L, uri)

        attachGate.complete(Unit)
        advanceUntilIdle()

        verify(repo, times(1)).attachLocalFile(1L, uri)
        assertFalse(vm.isAttaching.value)
        assertNull(vm.error.value)
    }

    // --- attachAudio: failure / cancellation --------------------------------

    @Test
    fun attachAudio_failure_surfacesError_clearsBusy_andNeverUpdatesStatus() = runTest(mainRule.testDispatcher) {
        whenever(repo.attachLocalFile(eq(1L), any()))
            .doSuspendableAnswer { throw IOException("attach boom") }
        val vm = create(trackId = 1L)

        vm.attachAudio(uri)
        advanceUntilIdle()

        assertEquals("attach boom", vm.error.value)
        assertFalse("busy flag must be cleared on failure", vm.isAttaching.value)
        // The ViewModel must only surface the error; it never mutates the track status itself.
        verify(repo, never()).updateStatus(eq(1L), any(), anyOrNull())
    }

    @Test
    fun attachAudio_cancellation_clearsBusy() = runTest(mainRule.testDispatcher) {
        val attachGate = CompletableDeferred<Unit>()
        whenever(repo.attachLocalFile(eq(1L), any())).doSuspendableAnswer { attachGate.await() }
        val vm = create(trackId = 1L)

        vm.attachAudio(uri)
        runCurrent()
        assertTrue(vm.isAttaching.value)

        store.clear() // cancels viewModelScope while the repository call is suspended
        advanceUntilIdle()

        assertFalse("busy flag must not get stuck after cancellation", vm.isAttaching.value)
        assertNull(vm.error.value)
    }

    // --- clearError ---------------------------------------------------------

    @Test
    fun clearError_resetsSurfacedError() = runTest(mainRule.testDispatcher) {
        whenever(repo.attachLocalFile(eq(1L), any()))
            .doSuspendableAnswer { throw IOException("boom") }
        val vm = create(trackId = 1L)

        vm.attachAudio(uri)
        advanceUntilIdle()
        assertEquals("boom", vm.error.value)

        vm.clearError()
        assertNull(vm.error.value)
    }
}
