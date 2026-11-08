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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

private val GlassBorder = Color.White.copy(alpha = 0.40f)
private val GlassDark = Color(0xFF0D1B2A).copy(alpha = 0.82f)
private val TextOnDark = Color(0xFFF5F7FA)
private val TextMutedOnDark = Color(0xFFC5CDD8)
private val TextOnLight = Color(0xFF1A2332)
private val TextMutedOnLight = Color(0xFF5A6577)
private val LightCard = Color(0xFFF3F5F8)
private val LightBorder = Color(0xFFD0D7E2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZakonApp(store: LegalStore) {
    val stack = remember { mutableStateListOf("home") }
    val route = stack.last()
    val isHome = route == "home"
    var query by remember { mutableStateOf("") }
    val navigate: (String) -> Unit = { stack.add(it) }
    val goHome: () -> Unit = { stack.clear(); stack.add("home") }
    val back: () -> Unit = {
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            !isHome -> goHome()
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollIndexMap = remember { mutableStateMapOf<String, Int>() }
    var cabinetBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }

    fun goBack() {
        if (route == "cabinet" && cabinetBackHandler?.invoke() == true) return
        back()
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = !isHome && drawerState.isClosed) { goBack() }
    BackHandler(enabled = isHome && drawerState.isClosed) { }

    Box(Modifier.fillMaxSize()) {
        if (isHome) {
            Image(
                painter = painterResource(id = R.drawable.grok_1793951493468),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(0.28f), Color.Black.copy(0.16f), Color.Black.copy(0.38f))
                    )
                )
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.White))
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = GlassDark,
                    drawerContentColor = TextOnDark,
                    modifier = Modifier.width(280.dp)
                ) {
                    Spacer(Modifier.height(28.dp))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconBadge("§", 48.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("ЗаконЪ", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text("Правовая библиотека", color = LegalGold, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    Spacer(Modifier.height(8.dp))
                    DrawerItem("⌂", "Главная", route == "home") { stack.clear(); stack.add("home"); scope.launch { drawerState.close() } }
                    DrawerItem("§", "Кодексы", route == "codes" || route.startsWith("code/")) { stack.clear(); stack.add("home"); stack.add("codes"); scope.launch { drawerState.close() } }
                    DrawerItem("◆", "Конституция", route.startsWith("constitution")) { stack.clear(); stack.add("home"); stack.add("constitution"); scope.launch { drawerState.close() } }
                    DrawerItem("⚖", "Федеральные законы", route.startsWith("category/law")) { stack.clear(); stack.add("home"); stack.add("category/law"); scope.launch { drawerState.close() } }
                    DrawerItem("♟", "Личный кабинет", route == "cabinet") { stack.clear(); stack.add("home"); stack.add("cabinet"); scope.launch { drawerState.close() } }
                    DrawerItem("★", "Избранное", route == "favorites") { stack.clear(); stack.add("home"); stack.add("favorites"); scope.launch { drawerState.close() } }
                    DrawerItem("⌕", "Поиск", route == "search") { stack.clear(); stack.add("home"); stack.add("search"); scope.launch { drawerState.close() } }
                    DrawerItem("⚙", "Настройки", route == "settings") { stack.clear(); stack.add("home"); stack.add("settings"); scope.launch { drawerState.close() } }
                    DrawerItem("ℹ", "О приложении", route == "about") { stack.clear(); stack.add("home"); stack.add("about"); scope.launch { drawerState.close() } }
                }
            }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = if (isHome) Color.Black.copy(alpha = 0.28f) else Color.White,
                            titleContentColor = if (isHome) TextOnDark else TextOnLight,
                            navigationIconContentColor = if (isHome) TextOnDark else TextOnLight,
                            actionIconContentColor = if (isHome) TextOnDark else TextOnLight
                        ),
                        title = { Text(titleFor(route), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            if (isHome) {
                                Text("☰", Modifier.clickable { scope.launch { drawerState.open() } }.padding(horizontal = 16.dp), fontSize = 22.sp)
                            } else {
                                Text("‹", Modifier.clickable { goBack() }.padding(horizontal = 16.dp), fontSize = 30.sp)
                            }
                        },
                        actions = {
                            Text("⚙", Modifier.clickable { navigate("settings") }.padding(horizontal = 16.dp), fontSize = 20.sp)
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
                    route.startsWith("code/") && route.contains("/articles") -> {
                        val id = route.removePrefix("code/").removeSuffix("/articles")
                        val docId = docIdForCodeId(id)
                        if (docId != null) CodeArticlesScreen(store, docId, padding, scrollIndexMap, "articles_$id") { navigate("article/$it") }
                        else PlaceholderLight("Нет статей", padding)
                    }
                    route.startsWith("code/") && route.contains("/part/") -> {
                        val partId = route.substringAfterLast("/")
                        CodeArticlesScreen(store, partId, padding, scrollIndexMap, "part_$partId") { navigate("article/$it") }
                    }
                    route.startsWith("code/") -> {
                        val id = route.removePrefix("code/")
                        val code = AllRfCodes.find { it.id == id }
                        if (code != null) CodeHubScreen(
                            code = code, padding = padding,
                            onContent = {
                                when {
                                    code.parts.isNotEmpty() -> navigate("code/$id/part/${code.parts.first().id}")
                                    docIdForCodeId(id) != null -> navigate("code/$id/articles")
                                    else -> { }
                                }
                            },
                            onParts = { navigate("code/$id/part/$it") },
                            onLiability = { navigate("code/$id/liability") }
                        ) else PlaceholderLight("Не найдено", padding)
                    }
                    route == "constitution" -> CodeArticlesScreen(store, "const", padding, scrollIndexMap, "constitution") { navigate("article/$it") }
                    route == "search" -> SearchScreen(store, query, { query = it }, padding) { navigate("article/" + it) }
                    route == "favorites" -> {
                        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Избранное", color = TextOnLight, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Сохранённые статьи появятся здесь.", color = TextMutedOnLight, textAlign = TextAlign.Center)
                        }
                    }
                    route == "settings" -> ContentUpdateScreen(store, padding)
                    route == "about" -> AboutScreen(padding)
                    route == "cabinet" -> CabinetScreen(padding) { handler -> cabinetBackHandler = handler }
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
    route.startsWith("code/") && route.endsWith("/articles") -> "Статьи"
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

private fun docIdForCodeId(id: String): String? = when (id) {
    "uk" -> "uk"; "koap" -> "koap"; "tk" -> "tk"; "upk" -> "upk"
    "gpk" -> "gpk"; "sk" -> "sk"; "zhk" -> "zhk"; "gk" -> "gk1"; "nk" -> "nk1"
    else -> null
}

@Composable
private fun CodeHubScreen(code: RfCode, padding: PaddingValues, onContent: () -> Unit, onParts: (String) -> Unit, onLiability: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(code.fullName, color = TextOnLight, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp)
        if (code.overview.isNotBlank()) Text(code.overview, color = TextMutedOnLight, fontSize = 14.sp, lineHeight = 21.sp)
        HubTile(code.symbol, "Содержание · ${code.shortName}", if (code.parts.isNotEmpty()) "Части и статьи" else "Статьи кодекса") { onContent() }
        if (code.parts.isNotEmpty()) {
            Text("Части", color = Color(0xFF8B6914), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            code.parts.forEach { part -> HubTile(code.symbol, part.title, code.shortName) { onParts(part.id) } }
        }
        if (code.hasLiability) HubTile("⚖", code.liabilityTitle, "Штрафы и виды ответственности") { onLiability() }
        Text("Справочный характер. Сверяйте с официальными источниками.", color = TextMutedOnLight, fontSize = 11.sp)
    }
}

@Composable
private fun HubTile(symbol: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(LightCard)
            .border(1.dp, LightBorder, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(symbol, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextOnLight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = TextMutedOnLight, fontSize = 12.sp)
        }
        Text("›", color = Color(0xFF8B6914), fontSize = 22.sp)
    }
}

@Composable
private fun HomeGlassScreen(padding: PaddingValues, onNavigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun CodesMenuScreen(padding: PaddingValues, scrollIndexMap: MutableMap<String, Int>, onCodeClick: (String) -> Unit) {
    val key = "codes_menu"
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val initial = scrollIndexMap[key] ?: 0
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initial)
    LaunchedEffect(gridState.isScrollInProgress) { if (!gridState.isScrollInProgress) scrollIndexMap[key] = gridState.firstVisibleItemIndex }
    DisposableEffect(Unit) { onDispose { scrollIndexMap[key] = gridState.firstVisibleItemIndex } }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Основные кодексы Российской Федерации", color = TextMutedOnLight, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            items(AllRfCodes, key = { it.id }) { code ->
                val bmp = remember(code.id) { CodeIcons.bitmapFor(ctx, code.id) }
                CodeBookCard(
                    title = code.shortName + if (code.parts.isNotEmpty()) "\n${code.parts.size} ч." else "",
                    symbol = code.symbol,
                    bitmap = bmp,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onCodeClick(code.id) }
                )
            }
        }
    }
}

@Composable
private fun CodeBookCard(
    title: String,
    symbol: String,
    bitmap: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(128.dp)
                .padding(end = 3.dp, bottom = 3.dp)
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(x = 3.dp, y = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF0E8D8))
            )
            Box(
                Modifier
                    .matchParentSize()
                    .shadow(6.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2A1810))
                    .border(1.dp, Color(0xFFC9A227).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        IconBadge(symbol, 36.dp)
                    }
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(8.dp)
                        .align(Alignment.CenterStart)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF0D0705), Color(0xFF3D2418), Color.Transparent)
                            )
                        )
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.5.dp)
                        .align(Alignment.CenterStart)
                        .offset(x = 8.dp)
                        .background(Color(0xFFC9A227).copy(alpha = 0.65f))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            color = TextOnLight,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CodeArticlesScreen(store: LegalStore, documentId: String, padding: PaddingValues, scrollIndexMap: MutableMap<String, Int>, scrollKey: String, onArticle: (String) -> Unit) {
    var articles by remember { mutableStateOf<List<LegalArticle>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    val initial = scrollIndexMap[scrollKey] ?: 0
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initial)
    LaunchedEffect(gridState.isScrollInProgress) { if (!gridState.isScrollInProgress) scrollIndexMap[scrollKey] = gridState.firstVisibleItemIndex }
    DisposableEffect(scrollKey) { onDispose { scrollIndexMap[scrollKey] = gridState.firstVisibleItemIndex } }
    LaunchedEffect(documentId) {
        withContext(Dispatchers.IO) {
            title = store.getDocument(documentId)?.title ?: documentId
            articles = store.getArticles(documentId)
        }
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, color = TextOnLight, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (articles.isEmpty()) Text("Статьи не найдены в локальной базе.", color = TextMutedOnLight)
        else LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(articles, key = { it.id }) { a -> ArticleTile(a) { onArticle(a.id) } }
        }
    }
}

@Composable
private fun ArticleTile(article: LegalArticle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val essence = article.title?.take(48) ?: article.body.replace("\n", " ").take(48)
    Box(modifier.height(92.dp).clip(RoundedCornerShape(14.dp)).background(LightCard).border(1.dp, LightBorder, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp)) {
        Column {
            Text("ст. ${article.number}", color = Color(0xFF8B6914), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(essence, color = TextOnLight, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LiabilityScreen(code: RfCode, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(code.shortName + " — " + code.liabilityTitle, color = TextOnLight, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        code.liabilityItems.forEach { item ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(LightCard)
                    .border(1.dp, LightBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(item.title, color = Color(0xFF8B6914), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(item.body, color = TextOnLight, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun GlassCard(symbol: String, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(118.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(0.28f), Color.White.copy(0.10f))))
            .border(1.2.dp, Color.White.copy(0.55f), RoundedCornerShape(18.dp))
            .shadow(10.dp, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconBadge(symbol, 42.dp)
        Spacer(Modifier.height(10.dp))
        Text(title, color = TextOnDark, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconBadge(symbol: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).shadow(4.dp, CircleShape).clip(CircleShape)
            .background(Brush.radialGradient(listOf(LegalGold.copy(0.85f), LegalGold.copy(0.35f))))
            .border(1.dp, Color.White.copy(0.3f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
private fun DrawerItem(icon: String, title: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color.White.copy(0.12f) else Color.Transparent
    val textColor = if (selected) LegalGold else TextOnDark
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = textColor, fontSize = 18.sp, modifier = Modifier.width(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = textColor, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
    }
}

@Composable
private fun PlaceholderLight(msg: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text(msg, color = TextMutedOnLight, fontSize = 15.sp)
    }
}
