package ru.zakon.app

internal enum class LegalSearchMode {
    SMART,
    EXACT_REFERENCE
}

/**
 * One entry point for the search screen. Search providers remain replaceable so an
 * on-device semantic provider can be added without changing the UI or content database.
 */
internal class LegalSearchCoordinator(private val store: LegalStore) {
    fun search(query: String, mode: LegalSearchMode): List<SearchHit> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return when (mode) {
            LegalSearchMode.EXACT_REFERENCE -> store.searchExactReference(term)
            LegalSearchMode.SMART -> store.search(term)
        }
    }
}
