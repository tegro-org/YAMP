package org.tegro.yamp.presentation.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.domain.media.Track
import kotlinx.coroutines.flow.map
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.data.repository.SearchHistoryRepository
import org.tegro.yamp.data.provider.ComposedSearch
import org.tegro.yamp.data.provider.ProviderComposition
import org.tegro.yamp.domain.provider.Outcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The search tab: search as you type, recent searches, and the charts shown before a query. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val composition: ProviderComposition,
    private val historyRepository: SearchHistoryRepository,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _found = MutableStateFlow(ComposedSearch())
    /** Artists and albums matching the query (tracks are in [results]). */
    val found: StateFlow<ComposedSearch> = _found.asStateFlow()

    private val _results = MutableStateFlow<List<Track>>(emptyList())
    val results: StateFlow<List<Track>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val history: StateFlow<List<String>> = historyRepository.history
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val weeklyHits: StateFlow<List<Track>> = libraryRepository.weeklyHits.map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, libraryRepository.weeklyHits.value.map { it.toTrack() })
    val trendTracks: StateFlow<List<Track>> = libraryRepository.trendTracks.map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, libraryRepository.trendTracks.value.map { it.toTrack() })

    fun loadMoreHits() = libraryRepository.loadMoreHits()

    fun loadMoreTrends() = libraryRepository.loadMoreTrends()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _query.value = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length > 2) {
            searchJob = viewModelScope.launch {
                try {
                    delay(300L) // typing pause
                    _isSearching.value = true
                    when (val found = composition.search(trimmed)) {
                        is Outcome.Ok -> {
                            _found.value = found.value
                            _results.value = found.value.tracks.map { it.item }
                        }
                        is Outcome.Err -> Log.e("SearchViewModel", "Search error: ${found.error}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SearchViewModel", "Search error", e)
                } finally {
                    _isSearching.value = false
                }
            }
        } else {
            _found.value = ComposedSearch()
            _results.value = emptyList()
            _isSearching.value = false
        }
    }

    /**
     * Keeps the query in the recent searches. Called when the user commits to it (the keyboard's search
     * key, or opening a result), not on every keystroke — "ра", "рад" are not searches.
     */
    fun rememberQuery() {
        val text = _query.value
        viewModelScope.launch { historyRepository.add(text) }
    }

    fun removeFromHistory(query: String) {
        viewModelScope.launch { historyRepository.remove(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clear() }
    }
}
