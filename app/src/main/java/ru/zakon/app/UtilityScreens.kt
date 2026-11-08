package ru.zakon.app

// TEMP: file was corrupted by partial push. Restoring minimal compiling stub then full content.
// This placeholder will be replaced immediately.

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable

@Composable
internal fun CabinetScreen(padding: PaddingValues, onProvideBack: ((() -> Boolean) -> Unit)? = null) {
}

@Composable
internal fun AboutScreen(padding: PaddingValues) {
}

@Composable
internal fun ConstructorScreen(padding: PaddingValues) {
}

@Composable
internal fun ContentUpdateScreen(store: LegalStore, padding: PaddingValues) {
}

@Composable
internal fun SearchScreen(store: LegalStore, query: String, onQuery: (String) -> Unit, padding: PaddingValues, onArticle: (String) -> Unit) {
}

@Composable
internal fun CategoryScreen(store: LegalStore, category: String, padding: PaddingValues, onDoc: (String) -> Unit) {
}

@Composable
internal fun DocumentScreen(store: LegalStore, docId: String, padding: PaddingValues, onArticle: (String) -> Unit) {
}

@Composable
internal fun ArticleScreen(store: LegalStore, articleId: String, padding: PaddingValues) {
}
