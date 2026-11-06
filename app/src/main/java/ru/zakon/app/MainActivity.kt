package ru.zakon.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val store = remember { LegalStore(applicationContext) }
            DisposableEffect(store) { onDispose { store.close() } }
            MaterialTheme(colorScheme = LegalColors) { ZakonApp(store) }
        }
    }
}

private val GlassBorder = Color.White.copy(alpha = 0.38f)
private val GlassDark = Color(0xFF0D1B2A).copy(alpha = 0.80f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZakonApp(store: LegalStore) {
    val stack = remember { mutableStateListOf("home") }
    val route = stack.last()
    val root = stack.size == 1
    var query by remember { mutableStateOf("") }
    val navigate: (String) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Exact scroll restoration: firstVisibleItemIndex per list route
    val scrollIndexMap = remember { mutableStateMapOf<String, Int>() }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = !root && drawerState.isClosed) { back() }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.grok_1793951493468),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.42f),
                        Color.Black.copy(alpha = 0.24f),
                        Color.Black.copy(alpha = 0.58f)
                    )
                )
            )
        )

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = GlassDark,
                    drawerContentColor = Color.White,
                    modifier = Modifier.width(280.dp)
                ) {
                    Spacer(Modifier.height(28.dp))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("§", color = LegalGold, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("ЗаконЪ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text("Правовая библиотека", color = LegalGold, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    Spacer(Modifier.height(8.dp))
                    DrawerItem("⌂", "Главная", route == "home") { stack.clear(); stack.add("home"); scope.launch { drawerState.close() } }
                    DrawerItem("§", "Кодексы", route == "codes" || route.startsWith("code/")) { stack.clear(); stack.add("codes"); scope.launch { drawerState.close() } }
                    DrawerItem("◆", "Конституция", route.startsWith("constitution")) { stack.clear(); stack.add("constitution"); scope.launch { drawerState.close() } }
                    DrawerItem("⚖", "Федеральные законы", route.startsWith("category/law")) { stack.clear(); stack.add("category/law"); scope.launch { drawerState.close() } }
                    DrawerItem("♟", "Личный кабинет", route == "cabinet") { stack.clear(); stack.add("cabinet"); scope.launch { drawerState.close() } }
                    DrawerItem("★", "Избранное", route == "favorites") { stack.clear(); stack.add("favorites"); scope.launch { drawerState.close() } }
                    DrawerItem("⌕", "Поиск", route == "search") { stack.clear(); stack.add("search"); scope.launch { drawerState.close() } }
                    DrawerItem("⚙", "Настройки", route == "settings") { stack.clear(); stack.add("settings"); scope.launch { drawerState.close() } }
                    DrawerItem("ℹ", "О приложении", route == "about") { stack.clear(); stack.add("about"); scope.launch { drawerState.close() } }
                }
            }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Black.copy(alpha = 0.35f),
                            titleContentColor = Color.White,
                            navigationIconContentColor = Color.White,
                            actionIconContentColor = Color.White
                        ),
                        title = { Text(titleFor(route), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            if (root) {
                                Text("☰", Modifier.clickable { scope.launch { drawerState.open() } }.padding(horizontal = 16.dp), color = Color.White, fontSize = 22.sp)
                            } else {
                                Text("‹", Modifier.clickable { back() }.padding(horizontal = 16.dp), color = Color.White, fontSize = 30.sp)
                            }
                        },
                        actions = {
                            Text("⚙", Modifier.clickable { navigate("settings") }.padding(horizontal = 16.dp), color = Color.White, fontSize = 20.sp)
                        }
                    )
                }
            ) { padding ->
                when {
                    route == "home" -> HomeGlassScreen(padding) { target ->
                        when (target) {
                            "codes" -> navigate("codes")
                            "constitution" -> navigate("constitution")
                            "laws" -> navigate("category/law")
                            "search" -> navigate("search")
                            "favorites" -> navigate("favorites")
                            "cabinet" -> navigate("cabinet")
                        }
                    }
                    route == "codes" -> CodesMenuScreen(padding, scrollIndexMap) { navigate("code/$it") }
                    route.startsWith("code/") && route.endsWith("/liability") -> {
                        val id = route.removePrefix("code/").removeSuffix("/liability")
                        val code = AllRfCodes.find { it.id == id }
                        if (code != null) LiabilityScreen(code, padding) else PlaceholderLight("Не найдено", padding)
                    }
                    route.startsWith("code/") && route.contains("/part/") -> {
                        val partId = route.substringAfterLast("/")
                        CodeArticlesScreen(store, partId, padding, scrollIndexMap, "part_$partId") { navigate("article/$it") }
                    }
                    route.startsWith("code/") -> {
                        val id = route.removePrefix("code/")
                        val code = AllRfCodes.find { it.id == id }
                        if (code != null) CodeDetailScreen(
                            store, code, padding,
                            onPart = { navigate("code/$id/part/$it") },
                            onLiability = { navigate("code/$id/liability") },
                            onArticle = { navigate("article/$it") }
                        ) else PlaceholderLight("Не найдено", padding)
                    }
                    route == "constitution" -> CodeArticlesScreen(store, "const", padding, scrollIndexMap, "constitution") { navigate("article/$it") }
                    route == "search" -> SearchScreen(store, query, { query = it }, padding) { navigate("article/" + it) }
                    route == "favorites" -> PlaceholderLight("Избранное", padding, "Сохранённые статьи появятся здесь.")
                    route == "settings" -> PlaceholderLight("Настройки", padding, "Раздел в разработке.")
                    route == "about" -> AboutScreen(padding)
                    route == "cabinet" -> CabinetScreen(padding)
                    route == "constructor" -> ConstructorScreen(padding)
                    route.startsWith("category/") -> CategoryScreen(store, route.removePrefix("category/"), padding) { navigate("document/" + it) }
                    route.startsWith("document/") -> DocumentScreen(store, route.removePrefix("document/"), padding) { navigate("article/" + it) }
                    route.startsWith("article/") -> ArticleScreen(store, route.removePrefix("article/"), padding)
                    else -> HomeGlassScreen(padding) { }
                }
            }
        }
    }
}

private fun titleFor(route: String): String = when {
    route == "codes" -> "Кодексы РФ"
    route.startsWith("code/") && route.endsWith("/liability") -> "Ответственность"
    route.startsWith("code/") -> AllRfCodes.find { route.contains(it.id) }?.shortName ?: "Кодекс"
    route == "constitution" -> "Конституция РФ"
    route == "search" -> "Поиск"
    route == "favorites" -> "Избранное"
    route == "cabinet" -> "Личный кабинет"
    route == "about" -> "О приложении"
    route == "settings" -> "Настройки"
    route.startsWith("article/") -> "Статья"
    else -> "ЗаконЪ"
}

@Composable
private fun HomeGlassScreen(padding: PaddingValues, onNavigate: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.62f))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard("§", "Кодексы", Modifier.weight(1f)) { onNavigate("codes") }
                GlassCard("◆", "Конституция", Modifier.weight(1f)) { onNavigate("constitution") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard("⚖", "Федеральные\nзаконы", Modifier.weight(1f)) { onNavigate("laws") }
                GlassCard("⌕", "Поиск", Modifier.weight(1f)) { onNavigate("search") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard("★", "Избранное", Modifier.weight(1f)) { onNavigate("favorites") }
                GlassCard("♟", "Личный\nкабинет", Modifier.weight(1f)) { onNavigate("cabinet") }
            }
        }
        Spacer(Modifier.weight(0.18f))
    }
}

@Composable
private fun CodesMenuScreen(
    padding: PaddingValues,
    scrollIndexMap: MutableMap<String, Int>,
    onCodeClick: (String) -> Unit
) {
    val key = "codes_menu"
    val initial = scrollIndexMap[key] ?: 0
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initial)
    LaunchedEffect(gridState.isScrollInProgress) {
        if (!gridState.isScrollInProgress) {
            scrollIndexMap[key] = gridState.firstVisibleItemIndex
        }
    }
    DisposableEffect(Unit) {
        onDispose { scrollIndexMap[key] = gridState.firstVisibleItemIndex }
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Основные кодексы Российской Федерации", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            items(AllRfCodes, key = { it.id }) { code ->
                GlassCard(
                    symbol = code.symbol,
                    title = code.shortName + if (code.parts.isNotEmpty()) "\n${code.parts.size} ч." else "",
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                    onClick = { onCodeClick(code.id) }
                )
            }
        }
    }
}

private fun docIdForCode(code: RfCode): String? = when (code.id) {
    "uk" -> "uk"
    "koap" -> "koap"
    "tk" -> "tk"
    "upk" -> "upk"
    "gpk" -> "gpk"
    "sk" -> "sk"
    "zhk" -> "zhk"
    else -> null
}

@Composable
private fun CodeDetailScreen(
    store: LegalStore,
    code: RfCode,
    padding: PaddingValues,
    onPart: (String) -> Unit,
    onLiability: () -> Unit,
    onArticle: (String) -> Unit
) {
    val scroll = rememberScrollState()
    var articles by remember { mutableStateOf<List<LegalArticle>>(emptyList()) }
    val directDoc = docIdForCode(code)
    LaunchedEffect(code.id) {
        if (directDoc != null) articles = withContext(Dispatchers.IO) { store.getArticles(directDoc) }
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(code.fullName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp)
        if (code.overview.isNotBlank()) Text(code.overview, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 21.sp)
        if (code.parts.isNotEmpty()) {
            Text("Части", color = LegalGold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            code.parts.forEach { part -> GlassRow("§  ${part.title}") { onPart(part.id) } }
        }
        if (code.hasLiability) {
            Text("Ответственность", color = LegalGold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            GlassRow("⚖  ${code.liabilityTitle}") { onLiability() }
        }
        if (articles.isNotEmpty()) {
            Text("Статьи", color = LegalGold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            ArticleTiles(articles, onArticle)
        } else if (directDoc == null && code.parts.isEmpty()) {
            Text("Тексты статей для этого кодекса будут добавлены в базу.", color = Color.White.copy(0.55f), fontSize = 12.sp)
        }
        Text("Справочный характер. Сверяйте с официальными источниками.", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
    }
}

@Composable
private fun CodeArticlesScreen(
    store: LegalStore,
    documentId: String,
    padding: PaddingValues,
    scrollIndexMap: MutableMap<String, Int>,
    scrollKey: String,
    onArticle: (String) -> Unit
) {
    var articles by remember { mutableStateOf<List<LegalArticle>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    val initial = scrollIndexMap[scrollKey] ?: 0
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initial)
    LaunchedEffect(gridState.isScrollInProgress) {
        if (!gridState.isScrollInProgress) {
            scrollIndexMap[scrollKey] = gridState.firstVisibleItemIndex
        }
    }
    DisposableEffect(scrollKey) {
        onDispose { scrollIndexMap[scrollKey] = gridState.firstVisibleItemIndex }
    }
    LaunchedEffect(documentId) {
        withContext(Dispatchers.IO) {
            title = store.getDocument(documentId)?.title ?: documentId
            articles = store.getArticles(documentId)
        }
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (articles.isEmpty()) Text("Статьи не найдены в локальной базе.", color = Color.White.copy(0.7f))
        else LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(articles, key = { it.id }) { a -> ArticleTile(a) { onArticle(a.id) } }
        }
    }
}

@Composable
private fun ArticleTiles(articles: List<LegalArticle>, onArticle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        articles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a -> ArticleTile(a, Modifier.weight(1f)) { onArticle(a.id) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ArticleTile(article: LegalArticle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val essence = article.title?.take(48) ?: article.body.replace("\n", " ").take(48)
    Box(
        modifier.height(88.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp)
    ) {
        Column {
            Text("ст. ${article.number}", color = LegalGold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(essence, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LiabilityScreen(code: RfCode, padding: PaddingValues) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(code.shortName + " — " + code.liabilityTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        code.liabilityItems.forEach { item ->
            Column(Modifier.fillMaxWidth().background(Color.White.copy(0.10f), RoundedCornerShape(14.dp)).border(1.dp, GlassBorder, RoundedCornerShape(14.dp)).padding(14.dp)) {
                Text(item.title, color = LegalGold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(item.body, color = Color.White.copy(0.88f), fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun GlassCard(symbol: String, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(100.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(0.20f), Color.White.copy(0.08f))))
            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(symbol, color = LegalGold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun GlassRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(0.12f))
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("›", color = LegalGold, fontSize = 20.sp)
    }
}

@Composable
private fun DrawerItem(icon: String, title: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) LegalGold.copy(alpha = 0.22f) else Color.Transparent
    val textColor = if (selected) LegalGold else Color.White.copy(alpha = 0.9f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = textColor, fontSize = 18.sp, modifier = Modifier.width(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = textColor, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
    }
}

@Composable
private fun PlaceholderLight(title: String, padding: PaddingValues, subtitle: String = "") {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = Color.White.copy(0.7f), textAlign = TextAlign.Center)
        }
    }
}
