package ru.zakon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZakonApp(LegalStore(this)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZakonApp(store: LegalStore) {
    val stack = remember { mutableStateListOf("home") }
    val route = stack.last()
    val root = stack.size == 1
    var query by remember { mutableStateOf("") }
    val navigate: (String) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = !root) { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when {
                    route == "home" -> "ЗаконЪ"
                    route == "search" -> "Поиск"
                    route == "settings" -> "Настройки"
                    route.startsWith("document/") -> "Документ"
                    else -> "Статья"
                }) },
                navigationIcon = {
                    if (!root) Text("‹", Modifier.clickable { back() }.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineMedium)
                }
            )
        },
        bottomBar = {
            if (root) NavigationBar {
                NavigationBarItem(route == "home", { stack.clear(); stack.add("home") }, icon = { Text("§") }, label = { Text("Законы") })
                NavigationBarItem(route == "search", { stack.clear(); stack.add("search") }, icon = { Text("⌕") }, label = { Text("Поиск") })
                NavigationBarItem(route == "settings", { stack.clear(); stack.add("settings") }, icon = { Text("⚙") }, label = { Text("Ещё") })
            }
        }
    ) { padding ->
        when {
            route == "home" -> DocumentsScreen(store, padding) { navigate("document/" + it) }
            route == "search" -> SearchScreen(store, query, { query = it }, padding) { navigate("article/" + it) }
            route == "settings" -> SettingsScreen(padding)
            route.startsWith("document/") -> DocumentScreen(store, route.removePrefix("document/"), padding) { navigate("article/" + it) }
            route.startsWith("article/") -> ArticleScreen(store, route.removePrefix("article/"), padding)
            else -> DocumentsScreen(store, padding) { navigate("document/" + it) }
        }
    }
}

@Composable
private fun DocumentsScreen(store: LegalStore, padding: PaddingValues, onOpen: (String) -> Unit) {
    var docs by remember { mutableStateOf<List<LegalDocument>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { docs = withContext(Dispatchers.IO) { store.getDocuments() }; loading = false }
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        Text("Законодательство Российской Федерации", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Офлайн-справочник с указанием редакций и источников.")
        Spacer(Modifier.height(16.dp))
        when {
            loading -> Text("Загрузка каталога…")
            docs.isEmpty() -> EmptyContent()
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(docs, key = { it.id }) { doc ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(doc.id) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(doc.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(listOf(doc.documentType, doc.number, doc.legalStatus).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(store: LegalStore, query: String, onQuery: (String) -> Unit, padding: PaddingValues, onOpen: (String) -> Unit) {
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), label = { Text("Название, номер статьи или текст") })
        LaunchedEffect(query) { hits = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { store.search(query.trim()) } }
        Spacer(Modifier.height(12.dp))
        if (query.isBlank()) Text("Введите запрос для поиска по установленной правовой базе.")
        else if (hits.isEmpty()) Text("Совпадений нет. Возможно, база ещё не установлена.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits, key = { it.articleId }) { hit ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(hit.articleId) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(hit.documentTitle, style = MaterialTheme.typography.labelMedium)
                        Text("Статья " + hit.articleNumber + (hit.articleTitle?.let { " — " + it } ?: ""), fontWeight = FontWeight.SemiBold)
                        Text(hit.preview, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentScreen(store: LegalStore, id: String, padding: PaddingValues, onOpen: (String) -> Unit) {
    var doc by remember { mutableStateOf<LegalDocument?>(null) }
    var articles by remember { mutableStateOf<List<LegalArticle>>(emptyList()) }
    LaunchedEffect(id) {
        val pair = withContext(Dispatchers.IO) { store.getDocument(id) to store.getArticles(id) }
        doc = pair.first; articles = pair.second
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        if (doc == null) Text("Документ не найден в установленной базе.")
        else {
            Text(doc!!.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Статус: " + doc!!.legalStatus + " · Проверка: " + doc!!.verificationStatus, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            if (articles.isEmpty()) Text("В этом документе пока нет загруженных статей.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(articles, key = { it.id }) { article ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(article.id) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Статья " + article.number, fontWeight = FontWeight.SemiBold)
                            article.title?.let { Text(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleScreen(store: LegalStore, id: String, padding: PaddingValues) {
    var article by remember { mutableStateOf<LegalArticle?>(null) }
    LaunchedEffect(id) { article = withContext(Dispatchers.IO) { store.getArticle(id) } }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp)) {
        if (article == null) Text("Статья не найдена в установленной базе.")
        else {
            Text("Статья " + article!!.number, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            article!!.title?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(16.dp))
            Text(article!!.body, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Text("Сведения об источнике и редакции будут показаны после загрузки проверенного выпуска базы.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyContent() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Правовая база пока не установлена", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Каталог и чтение готовы к подключению данных, но проверенные тексты законов ещё не загружены. Вымышленные нормы не показываются.")
    }
}

@Composable
private fun SettingsScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("О приложении", style = MaterialTheme.typography.titleLarge)
        Text("ЗаконЪ · версия 0.1.0")
        Text("После установки правовой базы чтение и поиск будут работать без интернета.")
        Text("Обновление будет проверять целостность файла и сохранять предыдущую рабочую версию при ошибке.")
    }
}
