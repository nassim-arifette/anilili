package com.miruronative.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    NEWEST("NEWEST"),
    POPULAR("POPULAR"),
    MOVIES("MOVIES"),
    TOP_RATED("TOP RATED"),
}

data class HomeData(
    val spotlight: List<Media>,
    val newest: List<Media>,
    val popular: List<Media>,
    val movies: List<Media>,
    val topRated: List<Media>,
) {
    fun tab(tab: HomeTab): List<Media> = when (tab) {
        HomeTab.NEWEST -> newest
        HomeTab.POPULAR -> popular
        HomeTab.MOVIES -> movies
        HomeTab.TOP_RATED -> topRated
    }
}

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    var selectedTab by mutableStateOf(HomeTab.POPULAR)
        private set

    init { load() }

    fun selectTab(tab: HomeTab) { selectedTab = tab }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            DiagnosticsLog.event("Home load start force=$force")
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            try {
                // Sections load independently: one failed AniList query must not cancel the
                // other four, so a flaky network degrades to missing rows instead of an error.
                val data = coroutineScope {
                    val spotlight = async { runCatching { repo.trending(force = force).items } }
                    val newest = async { runCatching { repo.recentlyReleased(force = force).items } }
                    val popular = async { runCatching { repo.popular(force = force).items } }
                    val movies = async {
                        runCatching {
                            repo.discover(DiscoverFilters(format = "MOVIE", sort = "POPULARITY_DESC"), force = force).items
                        }
                    }
                    val topRated = async { runCatching { repo.topRated(force = force).items } }
                    val sections = listOf(spotlight, newest, popular, movies, topRated).map { it.await() }
                    sections.forEach { section ->
                        section.exceptionOrNull()?.let {
                            it.rethrowIfCancellation()
                            DiagnosticsLog.throwable("Home section failed", it)
                        }
                    }
                    if (sections.all { it.isFailure }) throw sections.first().exceptionOrNull()!!
                    HomeData(
                        spotlight = sections[0].getOrDefault(emptyList()),
                        newest = sections[1].getOrDefault(emptyList()),
                        popular = sections[2].getOrDefault(emptyList()),
                        movies = sections[3].getOrDefault(emptyList()),
                        topRated = sections[4].getOrDefault(emptyList()),
                    )
                }
                DiagnosticsLog.event(
                    "Home load success spotlight=${data.spotlight.size} newest=${data.newest.size} " +
                        "popular=${data.popular.size} movies=${data.movies.size} topRated=${data.topRated.size}",
                )
                _state.value = UiState.Success(data)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Home load failed", e)
                _state.value = UiState.Error(e.message ?: "Failed to load home")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh() = load(force = true)
}
