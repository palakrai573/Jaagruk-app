package org.jaagruk.safety.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.jaagruk.safety.data.library.EntryType
import org.jaagruk.safety.data.library.LibraryEntry
import org.jaagruk.safety.data.library.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LibraryRepository.getInstance(app)

    // ── Filter + search state ─────────────────────────────────────────────────
    private val _selectedType  = MutableStateFlow<EntryType?>(null)
    val selectedType: StateFlow<EntryType?> = _selectedType.asStateFlow()

    private val _searchQuery   = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── Derived entries list ──────────────────────────────────────────────────
    // Reacts to both filter and search changes. flatMapLatest cancels previous
    // DB flow whenever filter or query changes — no stale data.
    val entries: StateFlow<List<LibraryEntry>> = combine(_selectedType, _searchQuery) { type, query ->
        Pair(type, query)
    }.flatMapLatest { (type, query) ->
        when {
            query.isNotBlank()  -> repo.search(query)
            type != null        -> repo.getByType(type)
            else                -> repo.getAll()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Save banner ───────────────────────────────────────────────────────────
    private val _showSavedBanner = MutableStateFlow(false)
    val showSavedBanner: StateFlow<Boolean> = _showSavedBanner.asStateFlow()

    // ── Public actions ────────────────────────────────────────────────────────

    fun setFilter(type: EntryType?) { _selectedType.value = type }

    fun setSearch(query: String) { _searchQuery.value = query }

    fun delete(entry: LibraryEntry) {
        viewModelScope.launch(Dispatchers.IO) { repo.delete(entry.id) }
    }

    /** Called by other ViewModels after a successful save to trigger the banner. */
    fun notifySaved() {
        _showSavedBanner.value = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_500)
            _showSavedBanner.value = false
        }
    }
}
