package com.phonoteka.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.phonoteka.data.ErrorType
import com.phonoteka.data.LocalImportReport
import com.phonoteka.data.Track
import com.phonoteka.data.TrackLibrary
import com.phonoteka.data.TrackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TrackListViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        store = ViewModelStore()
    }

    /** Clearing the store cancels viewModelScope, so no collector/import job leaks between tests. */
    @After
    fun tearDown() {
        store.clear()
    }

    private fun track(
        id: Long,
        artist: String,
        title: String,
        status: TrackStatus = TrackStatus.PENDING,
        searchQuery: String = ""
    ) = Track(id = id, artist = artist, title = title, status = status, searchQuery = searchQuery)

    private fun create(lib: TrackLibrary): TrackListViewModel =
        ViewModelProvider(store, TrackListViewModel.Factory(lib))[TrackListViewModel::class.java]

    @Test
    fun addTrackDraftRestoresFromSavedStateHandle() = runTest(mainRule.testDispatcher) {
        val handle = SavedStateHandle()
        val first = TrackListViewModel(FakeTrackLibrary(emptyList()), handle)
        first.saveAddTrackDraft("Muse", "Starlight")
        assertEquals("Muse", handle.get<String>("lab9.trackDraft.artist"))
        assertEquals("Starlight", handle.get<String>("lab9.trackDraft.title"))

        val restored = TrackListViewModel(FakeTrackLibrary(emptyList()), SavedStateHandle(mapOf(
            "lab9.trackDraft.artist" to handle.get<String>("lab9.trackDraft.artist"),
            "lab9.trackDraft.title" to handle.get<String>("lab9.trackDraft.title")
        )))
        runCurrent()
        assertEquals(AddTrackDraft("Muse", "Starlight"), restored.addTrackDraft.value)
    }

    /** Subscribes while-subscribed uiState eagerly (Unconfined) and records every emission. */
    private fun TestScope.collectStates(vm: TrackListViewModel): MutableList<ListUiState> {
        val states = mutableListOf<ListUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { states.add(it) }
        }
        return states
    }

    private fun List<ListUiState>.success(): ListUiState.Success {
        val last = last()
        assertTrue("expected Success but was $last", last is ListUiState.Success)
        return last as ListUiState.Success
    }

    // --- initial load -------------------------------------------------------

    @Test
    fun uiState_startsLoading_thenSuccess() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        val vm = create(lib)
        val states = collectStates(vm)

        advanceUntilIdle()

        assertEquals(ListUiState.Loading, states.first())
        val success = states.success()
        assertEquals(1, success.tracks.size)
        assertEquals(1, success.totalMatches)
        assertFalse(success.isRefreshing)
        assertEquals(1, lib.importFromAssetsCalls)
    }

    @Test
    fun emptyLibrary_showsEmpty_notSuccess() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(emptyList())
        val vm = create(lib)
        val states = collectStates(vm)

        advanceUntilIdle()

        assertEquals(ListUiState.Loading, states.first())
        assertTrue("expected Empty but was ${states.last()}", states.last() is ListUiState.Empty)
    }

    // --- query / status -----------------------------------------------------

    @Test
    fun searchQuery_updatesImmediately_butFilteringIsDebounced300ms() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Alpha", "One"), track(2, "Beta", "Two")))
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()
        assertEquals(2, states.success().tracks.size)

        vm.onSearchQueryChange("Alpha")
        assertEquals("Alpha", vm.searchQuery.value) // raw text is synchronous

        runCurrent()
        assertEquals(2, states.success().tracks.size) // not filtered yet
        advanceTimeBy(299)
        assertEquals(2, states.success().tracks.size) // still inside debounce window

        advanceUntilIdle()
        val filtered = states.success()
        assertEquals(1, filtered.tracks.size)
        assertEquals(1L, filtered.tracks.first().id)
        assertEquals(1, filtered.totalMatches)
    }

    @Test
    fun queryAndStatusFilter_areAppliedTogether() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(
            listOf(
                track(1, "Alpha", "One", TrackStatus.DONE),
                track(2, "Alpha", "Two", TrackStatus.ERROR),
                track(3, "Beta", "Three", TrackStatus.DONE)
            )
        )
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        vm.onSearchQueryChange("Alpha")
        vm.onStatusFilterChange(TrackStatus.DONE)
        advanceUntilIdle()

        val result = states.success()
        assertEquals(1, result.tracks.size)
        assertEquals(1L, result.tracks.first().id)
        assertEquals(1, result.totalMatches)
    }

    @Test
    fun unmatchedStatusFilter_yieldsEmpty() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(
            listOf(
                track(1, "Alpha", "One", TrackStatus.DONE),
                track(2, "Beta", "Two", TrackStatus.DONE)
            )
        )
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()
        assertEquals(2, states.success().tracks.size)

        vm.onStatusFilterChange(TrackStatus.REVIEW) // no track carries this status
        advanceUntilIdle()

        val last = states.last()
        assertTrue("expected Empty but was $last", last is ListUiState.Empty)
    }

    @Test
    fun searchQueryField_matchesCaseInsensitively_andCombinesWithStatus() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(
            listOf(
                track(1, "Alpha", "One", TrackStatus.ERROR, searchQuery = "Special Query"),
                track(2, "Beta", "Two", TrackStatus.ERROR, searchQuery = "other"),
                track(3, "Gamma", "Three", TrackStatus.DONE, searchQuery = "Special Query")
            )
        )
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        vm.onSearchQueryChange("special") // lower-case query vs mixed-case stored value
        vm.onStatusFilterChange(TrackStatus.ERROR)
        advanceUntilIdle()

        val result = states.success()
        assertEquals(1, result.tracks.size)
        assertEquals(1L, result.tracks.first().id)
        assertEquals(1, result.totalMatches)
    }

    // --- live Room-like updates --------------------------------------------

    @Test
    fun libraryUpdate_isReflectedWithoutRefresh_noStaleCache() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "One")))
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()
        assertEquals(1, states.success().tracks.size)

        lib.tracks.value = listOf(track(1, "Artist", "One"), track(2, "Artist", "Two"))
        advanceUntilIdle()

        assertEquals(2, states.success().tracks.size)
        assertEquals(1, lib.importFromAssetsCalls) // live update, not a re-import
    }

    // --- pure helpers -------------------------------------------------------

    @Test
    fun filterTracks_matchesDisplayNameAndSearchQuery_andStatus() {
        val tracks = listOf(
            track(1, "Alpha", "One", TrackStatus.DONE),
            track(2, "Beta", "Two", TrackStatus.ERROR, searchQuery = "special query"),
            track(3, "Gamma", "Three", TrackStatus.DONE)
        )

        assertEquals(listOf(1L, 3L), TrackListViewModel.filterTracks(tracks, "", TrackStatus.DONE).map { it.id })
        assertEquals(listOf(2L), TrackListViewModel.filterTracks(tracks, "special", null).map { it.id })
        assertEquals(listOf(1L), TrackListViewModel.filterTracks(tracks, "alpha — one", null).map { it.id })
        assertEquals(emptyList<Long>(), TrackListViewModel.filterTracks(tracks, "   ", TrackStatus.REVIEW).map { it.id })
    }

    @Test
    fun retryDelay_isExponential_andCappedAt8s() {
        assertEquals(0L, TrackListViewModel.retryDelay(0))
        assertEquals(0L, TrackListViewModel.retryDelay(1))
        assertEquals(1000L, TrackListViewModel.retryDelay(2))
        assertEquals(2000L, TrackListViewModel.retryDelay(3))
        assertEquals(4000L, TrackListViewModel.retryDelay(4))
        assertEquals(8000L, TrackListViewModel.retryDelay(5))
        assertEquals(8000L, TrackListViewModel.retryDelay(6))
        assertEquals(8000L, TrackListViewModel.retryDelay(50))
    }

    // --- refresh de-duplication & paging -----------------------------------

    @Test
    fun repeatedRefresh_importsOnlyOnce_whileLoadInFlight() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()
        assertEquals(1, lib.importFromAssetsCalls)

        vm.refresh()
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()

        assertEquals(2, lib.importFromAssetsCalls) // the two extra taps were de-duplicated
        assertEquals(1, states.success().tracks.size)
    }

    @Test
    fun loadMore_pagesBy100_thenQueryResetsPage() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary((1..250).map { track(it.toLong(), "Artist", "Song $it") })
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        assertEquals(100, states.success().tracks.size)
        assertEquals(250, states.success().totalMatches)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(200, states.success().tracks.size)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(250, states.success().tracks.size)

        vm.onSearchQueryChange("Artist")
        advanceUntilIdle()
        val afterQuery = states.success()
        assertEquals(100, afterQuery.tracks.size) // page limit reset to the first page
        assertEquals(250, afterQuery.totalMatches)
    }

    @Test
    fun statusChange_afterLoadMore_resetsPageLimitTo100() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary((1..250).map { track(it.toLong(), "Artist", "Song $it", TrackStatus.DONE) })
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        assertEquals(100, states.success().tracks.size)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(200, states.success().tracks.size)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(250, states.success().tracks.size)

        vm.onStatusFilterChange(TrackStatus.DONE)
        advanceUntilIdle()

        val after = states.success()
        assertEquals(100, after.tracks.size) // status change reset the page limit to the first page
        assertEquals(250, after.totalMatches)
    }

    @Test
    fun playbackCatalogue_isIndependentOfQueryStatusAndPage() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary((1..250).map { track(it.toLong(), "Artist", "Song $it") })
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        assertEquals(250, vm.playbackCatalogue().size)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(250, vm.playbackCatalogue().size) // paging does not shrink the catalogue

        vm.onSearchQueryChange("Song 1")
        vm.onStatusFilterChange(TrackStatus.REVIEW) // matches nothing
        advanceUntilIdle()

        assertTrue("expected Empty but was ${states.last()}", states.last() is ListUiState.Empty)
        assertEquals(250, vm.playbackCatalogue().size) // catalogue ignores query, status and page
    }

    // --- error / retry ------------------------------------------------------

    @Test
    fun importFailure_showsError_thenRetrySucceeds() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        lib.failImports = 1
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        val error = states.last()
        assertTrue("expected Error but was $error", error is ListUiState.Error)
        error as ListUiState.Error
        assertEquals("import boom", error.message)
        assertEquals(1, error.retryCount)
        assertEquals(0L, error.nextRetryDelayMs)
        assertEquals(1, lib.importFromAssetsCalls)

        vm.retry()
        advanceUntilIdle()

        assertEquals(1, states.success().tracks.size)
        assertEquals(2, lib.importFromAssetsCalls)
    }

    @Test
    fun retry_waitsOutBackoffBeforeCallingImport() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        lib.failImports = 2
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        var error = states.last() as ListUiState.Error
        assertEquals(1, error.retryCount)
        assertEquals(0L, error.nextRetryDelayMs)

        vm.retry()
        advanceUntilIdle()
        error = states.last() as ListUiState.Error
        assertEquals(2, error.retryCount)
        assertEquals(1000L, error.nextRetryDelayMs)
        assertEquals(2, lib.importFromAssetsCalls)

        vm.retry()
        runCurrent()
        assertEquals(2, lib.importFromAssetsCalls) // scheduled but not yet executed
        advanceTimeBy(999)
        assertEquals(2, lib.importFromAssetsCalls) // backoff not elapsed

        advanceUntilIdle()
        assertEquals(3, lib.importFromAssetsCalls)
        assertEquals(1, states.success().tracks.size)
    }

    @Test
    fun clearWhileBackoffScheduled_cancelsFutureImport() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        lib.failImports = 2
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        var error = states.last() as ListUiState.Error
        assertEquals(1, error.retryCount)
        assertEquals(0L, error.nextRetryDelayMs)
        assertEquals(ErrorType.STORAGE_ERROR, error.errorType) // IOException maps to STORAGE_ERROR

        vm.retry()
        advanceUntilIdle()
        error = states.last() as ListUiState.Error
        assertEquals(2, error.retryCount)
        assertEquals(1000L, error.nextRetryDelayMs)
        assertEquals(2, lib.importFromAssetsCalls)

        vm.retry() // schedules a 1s backoff retry
        runCurrent()
        assertEquals(2, lib.importFromAssetsCalls) // scheduled, not yet executed

        store.clear() // cancels viewModelScope while the backoff timer is still pending
        advanceUntilIdle()
        assertEquals(2, lib.importFromAssetsCalls) // the future import never ran
    }

    @Test
    fun repeatedRetry_whileBackoffPending_importsOnlyOnce() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        lib.failImports = 2
        val vm = create(lib)
        val states = collectStates(vm)
        advanceUntilIdle()

        var error = states.last() as ListUiState.Error
        assertEquals(1, error.retryCount)

        vm.retry()
        advanceUntilIdle()
        error = states.last() as ListUiState.Error
        assertEquals(2, error.retryCount)
        assertEquals(1000L, error.nextRetryDelayMs)
        assertEquals(2, lib.importFromAssetsCalls)

        vm.retry() // schedules the 1s backoff retry
        vm.retry() // ignored: a load is already pending
        vm.retry() // ignored: a load is already pending
        runCurrent()
        assertEquals(2, lib.importFromAssetsCalls) // still waiting out the backoff

        advanceUntilIdle()
        assertEquals(3, lib.importFromAssetsCalls) // exactly one retry ran, not three
        assertEquals(1, states.success().tracks.size)
    }

    // --- local audio import -------------------------------------------------

    @Test
    fun importAudio_failure_surfacesMessageAndClearsBusy() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        val vm = create(lib)
        advanceUntilIdle()
        lib.localImportFailure = IOException("no access")

        vm.importAudio(listOf(mock<Uri>()))
        advanceUntilIdle()

        val state = vm.localImport.value
        assertFalse(state.busy)
        assertEquals("no access", state.message)
        assertEquals(1, lib.importLocalFilesCalls)
    }

    @Test
    fun importAudio_success_reportsMessage_andIgnoresEmptySelection() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        lib.localImportReport = LocalImportReport(linked = 2, skipped = 1, failed = 0)
        val vm = create(lib)
        advanceUntilIdle()

        vm.importAudio(emptyList())
        advanceUntilIdle()
        assertEquals(0, lib.importLocalFilesCalls) // empty selection is a no-op

        vm.importAudio(listOf(mock<Uri>()))
        advanceUntilIdle()

        val state = vm.localImport.value
        assertFalse(state.busy)
        assertTrue("unexpected message: ${state.message}", state.message!!.contains("Привязано: 2"))
        assertEquals(1, lib.importLocalFilesCalls)
    }

    @Test
    fun importAudio_duplicateWhileInFlight_isIgnored_clearCancelsAndReleasesBusy() = runTest(mainRule.testDispatcher) {
        val lib = FakeTrackLibrary(listOf(track(1, "Artist", "Song")))
        lib.localImportDelayMs = 1_000 // hold the import in flight
        val vm = create(lib)
        advanceUntilIdle()

        val uris = listOf(mock<Uri>())
        vm.importAudio(uris)
        runCurrent()
        assertTrue(vm.localImport.value.busy)
        assertEquals(1, lib.importLocalFilesCalls)

        vm.importAudio(uris)
        vm.importAudio(uris)
        runCurrent()
        assertEquals(1, lib.importLocalFilesCalls) // duplicates ignored while busy

        store.clear() // cancels the in-flight import
        advanceUntilIdle()
        assertEquals(1, lib.importLocalFilesCalls) // cancelled work never completed
        assertFalse(vm.localImport.value.busy)     // finally released the busy flag
    }
}
