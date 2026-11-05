package ru.zakon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LegalNavy = Color(0xFF17283F)
private val LegalNavyLight = Color(0xFF243B58)
private val LegalGold = Color(0xFFC49A52)
private val LegalPaper = Color(0xFFF5F3EE)
private val LegalSurface = Color(0xFFFFFEFC)
private val LegalLine = Color(0xFFE5E1D8)
private val LegalMuted = Color(0xFF6D7784)
private val LegalColors = lightColorScheme(
    primary = LegalNavy, secondary = LegalGold, background = LegalPaper,
    surface = LegalSurface, onPrimary = Color.White, onSecondary = LegalNavy,
    onBackground = LegalNavy, onSurface = LegalNavy, outline = LegalLine,
    onSurfaceVariant = LegalMuted
)

private data class LibrarySection(val title: String, val subtitle: String, val symbol: String, val kind: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = LegalColors) { ZakonApp(LegalStore(this)) } }
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
        containerColor = LegalPaper,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LegalNavy, titleContentColor = Color.White,
                    navigationIconContentColor = LegalGold, actionIconContentColor = LegalGold
                ),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text("ЗаконЪ", fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                        if (route == "home") Text("ПРАВОВАЯ БИБЛИОТЕКА", color = LegalGold, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                    }
                },
                navigationIcon = {
                    if (!root) Text("‹", Modifier.clickable { back() }.padding(horizontal = 18.dp), color = LegalGold, fontSize = 34.sp)
                }
            )
        },
        bottomBar = {
            if (root) NavigationBar(containerColor = LegalSurface, contentColor = LegalNavy) {
                NavigationBarItem(route == "home", { stack.clear(); stack.add("home") }, icon = { Text("§", fontSize = 21.sp) }, label = { Text("База") })
                NavigationBarItem(route == "search", { stack.clear(); stack.add("search") }, icon = { Text("⌕", fontSize = 22.sp) }, label = { Text("Поиск") })
                NavigationBarItem(route == "cabinet", { stack.clear(); stack.add("cabinet") }, icon = { Text("♙", fontSize = 20.sp) }, label = { Text("Кабинет") })
                NavigationBarItem(route == "more", { stack.clear(); stack.add("more") }, icon = { Text("•••", fontSize = 18.sp) }, label = { Text("Ещё") })
            }
        }
    ) { padding ->
        when {
            route == "home" -> DocumentsScreen(store, padding, { navigate("document/" + it) }, { navigate("constructor") })
            route == "search" -> SearchScreen(store, query, { query = it }, padding) { navigate("article/" + it) }
            route == "cabinet" -> CabinetScreen(padding)
            route == "more" -> MoreScreen(padding) { navigate("constructor") }
            route == "constructor" -> ConstructorScreen(padding)
            route.startsWith("document/") -> DocumentScreen(store, route.removePrefix("document/"), padding) { navigate("article/" + it) }
            route.startsWith("article/") -> ArticleScreen(store, route.removePrefix("article/"), padding)
            else -> DocumentsScreen(store, padding, { navigate("document/" + it) }, { navigate("constructor") })
        }
    }
}

@Composable
private fun DocumentsScreen(store: LegalStore, padding: PaddingValues, onOpen: (String) -> Unit, onConstructor: () -> Unit) {
    var docs by remember { mutableStateOf<List<LegalDocument>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { docs = withContext(Dispatchers.IO) { store.getDocuments() }; loading = false }
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
                        Text("Законодательство России
в единой офлайн-библиотеке", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("Тексты законов • редакции • поиск", color = Color(0xFFD7DEE7), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onOpenFirstDocument(docs, onOpen) }, border = BorderStroke(1.dp, LegalGold), colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold)) { Text("Открыть библиотеку") }
                            OutlinedButton(onClick = onConstructor, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Конструктор") }
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
                    items(sections) { section ->
                        LibraryTile(section) {
                            // Categories are presented as shortcuts; the installed database remains the source of truth.
                            onOpenFirstDocument(docs, onOpen)
                        }
                    }
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
            } else if (docs.isEmpty()) {
                item { EmptyContent() }
            } else {
                items(docs, key = { it.id }) { doc -> DocumentCard(doc) { onOpen(doc.id) } }
            }
        }
    }
}

private fun onOpenFirstDocument(docs: List<LegalDocument>, onOpen: (String) -> Unit) {
    docs.firstOrNull()?.let { onOpen(it.id) }
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
private fun SearchScreen(store: LegalStore, query: String, onQuery: (String) -> Unit, padding: PaddingValues, onOpen: (String) -> Unit) {
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
        Text("Поиск по законодательству", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Название документа, номер статьи или фраза из текста", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
            label = { Text("Что ищем?") }, singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
        LaunchedEffect(query) {
            searching = query.isNotBlank()
            hits = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { store.search(query.trim()) }
            searching = false
        }
        Spacer(Modifier.height(14.dp))
        when {
            query.isBlank() -> Text("Поиск работает по установленной локальной правовой базе.")
            searching -> CircularProgressIndicator(color = LegalGold)
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
private fun DocumentScreen(store: LegalStore, id: String, padding: PaddingValues, onOpen: (String) -> Unit) {
    var doc by remember { mutableStateOf<LegalDocument?>(null) }
    var articles by remember { mutableStateOf<List<LegalArticle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(id) {
        loading = true
        val pair = withContext(Dispatchers.IO) { store.getDocument(id) to store.getArticles(id) }
        doc = pair.first
        articles = pair.second
        loading = false
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 16.dp)) {
        when {
            loading -> CircularProgressIndicator(color = LegalGold)
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
private fun ArticleScreen(store: LegalStore, id: String, padding: PaddingValues) {
    var article by remember { mutableStateOf<LegalArticle?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(id) {
        loading = true
        article = withContext(Dispatchers.IO) { store.getArticle(id) }
        loading = false
    }
    Column(
        Modifier.fillMaxSize().padding(padding).background(LegalPaper)
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp)
    ) {
        when {
            loading -> CircularProgressIndicator(color = LegalGold)
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
private fun CabinetScreen(padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Личный кабинет", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Ваше рабочее пространство на устройстве", color = LegalMuted)
        CabinetTile("Закладки", "Сохранённые статьи и документы", "☆")
        CabinetTile("История", "Недавно открытые материалы", "◷")
        CabinetTile("Мои материалы", "Черновики и подготовленные документы", "▤")
        Surface(color = LegalSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LegalLine)) {
            Text(
                "Локальные профили и синхронизация пока не подключены. Данные этого раздела не отправляются на сервер.",
                Modifier.padding(16.dp), color = LegalMuted, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CabinetTile(title: String, subtitle: String, symbol: String) {
    Surface(color = LegalSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LegalGold.copy(alpha = 0.5f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = LegalGold, fontSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = LegalMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MoreScreen(padding: PaddingValues, onConstructor: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Инструменты и настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Surface(
            Modifier.fillMaxWidth().clickable(onClick = onConstructor),
            color = LegalSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LegalGold)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✎", color = LegalGold, fontSize = 26.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Конструктор документов", fontWeight = FontWeight.Bold)
                    Text("Подготовка собственного черновика", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("О приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("ЗаконЪ · версия 0.1.0")
        Text("Правовая база, обновления и проверка редакций будут доступны после подключения подписанных выпусков.")
    }
}

@Composable
private fun ConstructorScreen(padding: PaddingValues) {
    var documentName by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Конструктор документов", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Создайте черновик. Это локальный редактор-заготовка, не юридическая консультация и не готовый процессуальный документ.", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(documentName, { documentName = it }, Modifier.fillMaxWidth(), label = { Text("Название документа") }, shape = RoundedCornerShape(12.dp))
        OutlinedTextField(recipient, { recipient = it }, Modifier.fillMaxWidth(), label = { Text("Кому адресован") }, shape = RoundedCornerShape(12.dp))
        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text("Текст и обстоятельства") }, minLines = 6, shape = RoundedCornerShape(12.dp))
        Button(onClick = { preview = !preview }, colors = ButtonDefaults.buttonColors(containerColor = LegalNavy)) {
            Text(if (preview) "Скрыть предварительный просмотр" else "Предварительный просмотр")
        }
        if (preview) {
            Surface(color = LegalSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LegalGold.copy(alpha = 0.7f))) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(documentName.ifBlank { "Название документа" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(recipient.ifBlank { "Адресат" }, color = LegalMuted)
                    HorizontalDivider()
                    Text(body.ifBlank { "Текст документа" }, style = MaterialTheme.typography.bodyMedium)
                    Text("ЧЕРНОВИК", color = LegalGold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("Сохранение, экспорт в PDF/DOCX и шаблоны документов будут добавлены отдельным этапом.", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyContent() {
    Surface(
        color = LegalSurface, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, LegalGold.copy(alpha = 0.55f))
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Правовая база пока не установлена", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Интерфейс готов к подключению проверенных текстов, но документы ещё не загружены. Вымышленные нормы не показываются.", color = LegalMuted)
        }
    }
}
