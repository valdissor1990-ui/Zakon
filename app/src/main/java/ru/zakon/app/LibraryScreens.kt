package ru.zakon.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class LibrarySection(val title: String, val subtitle: String, val symbol: String, val kind: String)

@Composable
internal fun DocumentsScreen(
    store: LegalStore,
    padding: PaddingValues,
    onOpen: (String) -> Unit,
    onConstructor: () -> Unit,
    onSection: (String) -> Unit
) {
    var docs by remember { mutableStateOf<List<LegalDocument>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        loading = true
        try {
            docs = withContext(Dispatchers.IO) { store.getDocuments() }
            loadError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.localizedMessage ?: "Не удалось прочитать локальную базу."
        } finally {
            loading = false
        }
    }
    val sections = listOf(
        LibrarySection("Кодексы", "ГК, УК, КоАП и другие", "§", "code"),
        LibrarySection("Федеральные законы", "Основные законы РФ", "⚖", "law"),
        LibrarySection("Конституция", "Основной закон государства", "◆", "constitution"),
        LibrarySection("Документы", "Вся правовая библиотека", "▤", "all")
    )

    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val columns = when {
            maxWidth >= 1100.dp -> 4
            maxWidth >= 700.dp -> 3
            else -> 2
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = LegalNavy),
                    border = BorderStroke(1.dp, LegalGold.copy(alpha = 0.75f))
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("РОССИЙСКАЯ ФЕДЕРАЦИЯ", color = LegalGold, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("⚖", color = LegalGold, fontSize = 27.sp)
                        }
                        Text("ЗаконЪ", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text("Законодательство России\nв единой офлайн-библиотеке", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("Тексты законов • редакции • поиск", color = Color(0xFFD7DEE7), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onConstructor, border = BorderStroke(1.dp, LegalGold), colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold)) { Text("Конструктор документов") }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Разделы библиотеки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Выберите тип нормативного документа", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = if (columns == 2) 360.dp else 520.dp),
                    userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sections) { section -> LibraryTile(section) { onSection(section.kind) } }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Все документы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (loading) "Загрузка каталога…" else "В базе: ${docs.size}", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("ОФЛАЙН", color = LegalGold, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            if (loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = LegalGold) }
            } else if (loadError != null) {
                item { EmptyContent(message = "Не удалось загрузить каталог: $loadError") }
            } else if (docs.isEmpty()) {
                item { EmptyContent() }
            } else {
                items(docs, key = { it.id }) { doc -> DocumentCard(doc) { onOpen(doc.id) } }
            }
        }
    }
}

@Composable
private fun LibraryTile(section: LibrarySection, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(132.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = LegalSurface),
        border = BorderStroke(1.dp, LegalGold.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(section.symbol, color = LegalGold, fontSize = 26.sp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(section.title, color = LegalNavy, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(section.subtitle, color = LegalMuted, style = MaterialTheme.typography.labelSmall, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
internal fun CategoryScreen(
    store: LegalStore,
    kind: String,
    padding: PaddingValues,
    onOpen: (String) -> Unit
) {
    var documents by remember { mutableStateOf<List<LegalDocument>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(kind, store) {
        loading = true
        try {
            documents = withContext(Dispatchers.IO) { store.getDocuments() }
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.localizedMessage ?: "Не удалось загрузить документы."
        } finally {
            loading = false
        }
    }

    val title = when (kind) {
        "code" -> "Кодексы Российской Федерации"
        "law" -> "Федеральные законы"
        "constitution" -> "Конституция Российской Федерации"
        else -> "Все документы"
    }
    val filtered = when (kind) {
        "code" -> documents.filter {
            it.documentType.contains("кодекс", ignoreCase = true) ||
                it.title.contains("кодекс", ignoreCase = true)
        }
        "law" -> documents.filter {
            it.documentType.contains("закон", ignoreCase = true) ||
                it.title.contains("федеральный закон", ignoreCase = true)
        }
        "constitution" -> documents.filter { it.title.contains("конституц", ignoreCase = true) }
        else -> documents
    }

    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LegalNavy)
        when {
            loading -> CircularProgressIndicator(color = LegalGold)
            error != null -> Text("Ошибка загрузки: $error", color = MaterialTheme.colorScheme.error)
            filtered.isEmpty() -> EmptyContent(
                if (documents.isEmpty()) null else "В этом разделе пока нет документов."
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(filtered, key = { it.id }) { doc -> DocumentCard(doc) { onOpen(doc.id) } }
            }
        }
    }
}

@Composable
private fun DocumentCard(doc: LegalDocument, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LegalSurface),
        border = BorderStroke(1.dp, LegalLine),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(LegalNavy, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text("§", color = LegalGold, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = LegalNavy)
                Text(listOf(doc.documentType, doc.number, doc.legalStatus).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = LegalMuted)
            }
            Text("›", color = LegalGold, fontSize = 28.sp)
        }
    }
}

@Composable
internal fun SearchScreen(store: LegalStore, query: String, onQuery: (String) -> Unit, padding: PaddingValues, onOpen: (String) -> Unit) {
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
        Text("Поиск по законодательству", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Название документа, номер статьи или фраза из текста", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
            label = { Text("Что ищем?") }, singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
        LaunchedEffect(query, store) {
            val term = query.trim()
            if (term.isBlank()) {
                hits = emptyList()
                searchError = null
                searching = false
            } else {
                searching = true
                try {
                    delay(250)
                    hits = withContext(Dispatchers.IO) { store.search(term) }
                    searchError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    hits = emptyList()
                    searchError = error.localizedMessage ?: "Ошибка поиска по локальной базе."
                } finally {
                    searching = false
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            query.isBlank() -> Text("Поиск работает по установленной локальной правовой базе.")
            searching -> CircularProgressIndicator(color = LegalGold)
            searchError != null -> Text("Не удалось выполнить поиск: $searchError", color = MaterialTheme.colorScheme.error)
            hits.isEmpty() -> Text("Совпадений нет. Проверьте запрос или установите выпуск правовой базы.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(hits, key = { it.articleId }) { hit ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpen(hit.articleId) },
                        colors = CardDefaults.cardColors(containerColor = LegalSurface),
                        border = BorderStroke(1.dp, LegalLine), shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(hit.documentTitle, color = LegalMuted, style = MaterialTheme.typography.labelMedium)
                            Text("Статья ${hit.articleNumber}" + (hit.articleTitle?.let { " — $it" } ?: ""), fontWeight = FontWeight.Bold)
                            Text(hit.preview, style = MaterialTheme.typography.bodySmall, color = LegalMuted, maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DocumentScreen(store: LegalStore, id: String, padding: PaddingValues, onOpen: (String) -> Unit) {
    var doc by remember { mutableStateOf<LegalDocument?>(null) }
    var articles by remember { mutableStateOf<List<LegalArticle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id, store) {
        loading = true
        try {
            val pair = withContext(Dispatchers.IO) { store.getDocument(id) to store.getArticles(id) }
            doc = pair.first
            articles = pair.second
            loadError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.localizedMessage ?: "Не удалось прочитать документ."
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp)) {
        when {
            loading -> CircularProgressIndicator(color = LegalGold)
            loadError != null -> Text("Ошибка загрузки документа: $loadError", color = MaterialTheme.colorScheme.error)
            doc == null -> Text("Документ не найден в установленной базе.")
            else -> {
                Text(doc!!.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LegalNavy)
                Spacer(Modifier.height(8.dp))
                Surface(color = LegalSurface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, LegalLine)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Статус: ${doc!!.legalStatus}", style = MaterialTheme.typography.bodySmall)
                        Text("Проверка источников: ${doc!!.verificationStatus}", style = MaterialTheme.typography.bodySmall, color = LegalMuted)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Оглавление", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (articles.isEmpty()) Text("В этом документе пока нет загруженных статей.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(articles, key = { it.id }) { article ->
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpen(article.id) },
                            colors = CardDefaults.cardColors(containerColor = LegalSurface),
                            border = BorderStroke(1.dp, LegalLine), shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(article.number, color = LegalGold, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 62.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Статья ${article.number}", fontWeight = FontWeight.SemiBold)
                                    article.title?.let { Text(it, color = LegalMuted, style = MaterialTheme.typography.bodySmall) }
                                }
                                Text("›", color = LegalGold, fontSize = 24.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArticleScreen(store: LegalStore, id: String, padding: PaddingValues) {
    var article by remember { mutableStateOf<LegalArticle?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id, store) {
        loading = true
        try {
            article = withContext(Dispatchers.IO) { store.getArticle(id) }
            loadError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.localizedMessage ?: "Не удалось прочитать статью."
        } finally {
            loading = false
        }
    }
    Column(
        Modifier.fillMaxSize().padding(padding).background(LegalPaper)
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp)
    ) {
        when {
            loading -> CircularProgressIndicator(color = LegalGold)
            loadError != null -> Text("Ошибка загрузки статьи: $loadError", color = MaterialTheme.colorScheme.error)
            article == null -> Text("Статья не найдена в установленной базе.")
            else -> {
                Text("НОРМАТИВНЫЙ ТЕКСТ", color = LegalGold, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Статья ${article!!.number}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LegalNavy)
                article!!.title?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.titleLarge, color = LegalNavy, fontWeight = FontWeight.Medium) }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = LegalGold.copy(alpha = 0.6f), thickness = 1.dp)
                Spacer(Modifier.height(20.dp))
                Text(article!!.body, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp, fontSize = 17.sp), color = Color(0xFF293443))
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = LegalLine)
                Spacer(Modifier.height(12.dp))
                Text("Сведения об источнике и редакции появятся после установки проверенного выпуска базы.", style = MaterialTheme.typography.bodySmall, color = LegalMuted)
            }
        }
    }
}


@Composable
private fun EmptyContent(message: String? = null) {
    Surface(
        color = LegalSurface, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, LegalGold.copy(alpha = 0.55f))
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (message == null) "Правовая база пока не установлена" else "Проблема с правовой базой", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(message ?: "Интерфейс готов к подключению проверенных текстов, но документы ещё не загружены. Вымышленные нормы не показываются.", color = LegalMuted)
        }
    }
}
