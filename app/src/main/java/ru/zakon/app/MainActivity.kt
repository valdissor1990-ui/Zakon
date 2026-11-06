package ru.zakon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val store = remember { LegalStore(applicationContext) }
            DisposableEffect(store) {
                onDispose { store.close() }
            }
            MaterialTheme(colorScheme = LegalColors) { ZakonApp(store) }
        }
    }
}

private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val GlassDark = Color(0xFF0D1B2A).copy(alpha = 0.78f)

// ─── Data: all major RF codes ───────────────────────────────────────────────

private data class CodePart(val id: String, val title: String)
private data class RfCode(
    val id: String,
    val shortName: String,
    val fullName: String,
    val icon: String,
    val parts: List<CodePart> = emptyList()
)

private val AllRfCodes = listOf(
    RfCode("gk", "ГК РФ", "Гражданский кодекс", "📜", listOf(
        CodePart("gk1", "Часть 1"), CodePart("gk2", "Часть 2"),
        CodePart("gk3", "Часть 3"), CodePart("gk4", "Часть 4")
    )),
    RfCode("uk", "УК РФ", "Уголовный кодекс", "⚖️"),
    RfCode("upk", "УПК РФ", "Уголовно-процессуальный кодекс", "🔍"),
    RfCode("gpk", "ГПК РФ", "Гражданский процессуальный кодекс", "📋"),
    RfCode("apk", "АПК РФ", "Арбитражный процессуальный кодекс", "🏛️"),
    RfCode("koap", "КоАП РФ", "Кодекс об административных правонарушениях", "🚨"),
    RfCode("tk", "ТК РФ", "Трудовой кодекс", "👷"),
    RfCode("sk", "СК РФ", "Семейный кодекс", "👨‍👩‍👧"),
    RfCode("zhk", "ЖК РФ", "Жилищный кодекс", "🏠"),
    RfCode("zk", "ЗК РФ", "Земельный кодекс", "🌍"),
    RfCode("grk", "ГрК РФ", "Градостроительный кодекс", "🏗️"),
    RfCode("nk", "НК РФ", "Налоговый кодекс", "💰", listOf(
        CodePart("nk1", "Часть 1"), CodePart("nk2", "Часть 2")
    )),
    RfCode("bk", "БК РФ", "Бюджетный кодекс", "📊"),
    RfCode("vk", "ВК РФ", "Водный кодекс", "🌊"),
    RfCode("lk", "ЛК РФ", "Лесной кодекс", "🌲"),
    RfCode("air", "Воздушный кодекс", "Воздушный кодекс", "✈️"),
    RfCode("ktm", "КТМ РФ", "Кодекс торгового мореплавания", "🚢"),
    RfCode("kasi", "КАС РФ", "Кодекс административного судопроизводства", "📑"),
    RfCode("tam", "ТК ЕАЭС", "Таможенный кодекс ЕАЭС", "📦"),
    RfCode("uik", "УИК РФ", "Уголовно-исполнительный кодекс", "🔒")
)

// ─── App ────────────────────────────────────────────────────────────────────

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
                        Color.Black.copy(alpha = 0.40f),
                        Color.Black.copy(alpha = 0.22f),
                        Color.Black.copy(alpha = 0.55f)
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
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🦅", fontSize = 42.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("ЗаконЪ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text("Правовая библиотека", color = LegalGold, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    Spacer(Modifier.height(8.dp))

                    DrawerItem("🏠", "Главная", route == "home") {
                        stack.clear(); stack.add("home"); scope.launch { drawerState.close() }
                    }
                    DrawerItem("📖", "Кодексы", route == "codes" || route.startsWith("code/")) {
                        stack.clear(); stack.add("codes"); scope.launch { drawerState.close() }
                    }
                    DrawerItem("📜", "Конституция", route == "constitution") {
                        stack.clear(); stack.add("constitution"); scope.launch { drawerState.close() }
                    }
                    DrawerItem("⚖", "Федеральные законы", route.startsWith("category/law")) {
                        stack.clear(); stack.add("category/law"); scope.launch { drawerState.close() }
                    }
                    DrawerItem("★", "Избранное", route == "favorites") {
                        stack.clear(); stack.add("favorites"); scope.launch { drawerState.close() }
                    }
                    DrawerItem("⌕", "Поиск", route == "search") {
                        stack.clear(); stack.add("search"); scope.launch { drawerState.close() }
                    }
                    DrawerItem("⚙", "Настройки", route == "settings") {
                        stack.clear(); stack.add("settings"); scope.launch { drawerState.close() }
                    }
                    DrawerItem("ℹ", "О приложении", route == "about") {
                        stack.clear(); stack.add("about"); scope.launch { drawerState.close() }
                    }
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
                        title = {
                            Text(
                                when {
                                    route == "codes" -> "Кодексы РФ"
                                    route.startsWith("code/") -> {
                                        val id = route.removePrefix("code/")
                                        AllRfCodes.find { it.id == id }?.shortName ?: "Кодекс"
                                    }
                                    route == "constitution" -> "Конституция РФ"
                                    route == "search" -> "Поиск"
                                    route == "favorites" -> "Избранное"
                                    else -> "ЗаконЪ"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        },
                        navigationIcon = {
                            if (root) {
                                Text("☰", Modifier.clickable { scope.launch { drawerState.open() } }.padding(horizontal = 16.dp), color = Color.White, fontSize = 24.sp)
                            } else {
                                Text("‹", Modifier.clickable { back() }.padding(horizontal = 16.dp), color = Color.White, fontSize = 32.sp)
                            }
                        },
                        actions = {
                            Text("⚙", Modifier.clickable { stack.clear(); stack.add("settings") }.padding(horizontal = 16.dp), color = Color.White, fontSize = 22.sp)
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
                        }
                    }
                    route == "codes" -> CodesMenuScreen(padding) { codeId ->
                        val code = AllRfCodes.find { it.id == codeId }
                        if (code != null && code.parts.isNotEmpty()) {
                            navigate("code/$codeId")
                        } else {
                            // single-part code → open as document search later
                            navigate("category/code")
                        }
                    }
                    route.startsWith("code/") -> {
                        val codeId = route.removePrefix("code/")
                        val code = AllRfCodes.find { it.id == codeId }
                        if (code != null) CodePartsScreen(code, padding) { partId ->
                            navigate("category/code")
                        } else PlaceholderScreen("Кодекс", "Не найден", padding)
                    }
                    route == "constitution" -> PlaceholderScreen("Конституция РФ", "Основной закон Российской Федерации", padding)
                    route == "search" -> SearchScreen(store, query, { query = it }, padding) { navigate("article/" + it) }
                    route == "favorites" -> PlaceholderScreen("Избранное", "Здесь будут избранные статьи", padding)
                    route == "settings" -> PlaceholderScreen("Настройки", "Раздел в разработке", padding)
                    route == "about" -> PlaceholderScreen("О приложении", "ЗаконЪ — офлайн правовая библиотека России", padding)
                    route == "cabinet" -> CabinetScreen(padding)
                    route == "more" -> MoreScreen(padding) { navigate("constructor") }
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

// ─── Home ───────────────────────────────────────────────────────────────────

@Composable
private fun HomeGlassScreen(padding: PaddingValues, onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Push cards lower
        Spacer(Modifier.weight(0.55f))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard("📖", "Кодексы", Modifier.weight(1f)) { onNavigate("codes") }
                GlassCard("📜", "Конституция", Modifier.weight(1f)) { onNavigate("constitution") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard("⚖", "Федеральные\nзаконы и статьи", Modifier.weight(1f)) { onNavigate("laws") }
                GlassCard("⌕", "Поиск", Modifier.weight(1f)) { onNavigate("search") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard("★", "Избранное", Modifier.weight(1f)) { onNavigate("favorites") }
                // empty spacer to keep 2-column rhythm
                Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.weight(0.25f))
    }
}

// ─── Codes menu ─────────────────────────────────────────────────────────────

@Composable
private fun CodesMenuScreen(padding: PaddingValues, onCodeClick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "Все кодексы Российской Федерации",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(AllRfCodes) { code ->
                GlassCard(
                    icon = code.icon,
                    title = code.shortName + if (code.parts.isNotEmpty()) "\n(${code.parts.size} ч.)" else "",
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    onClick = { onCodeClick(code.id) }
                )
            }
        }
    }
}

@Composable
private fun CodePartsScreen(code: RfCode, padding: PaddingValues, onPartClick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(code.fullName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Выберите часть", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 16.dp))
        code.parts.forEach { part ->
            GlassCard(
                icon = code.icon,
                title = "${code.shortName}\n${part.title}",
                modifier = Modifier.fillMaxWidth().height(100.dp).padding(bottom = 10.dp),
                onClick = { onPartClick(part.id) }
            )
        }
    }
}

// ─── Shared glass card ──────────────────────────────────────────────────────

@Composable
private fun GlassCard(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(120.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.10f)
                    )
                )
            )
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(icon, fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun DrawerItem(icon: String, title: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) LegalGold.copy(alpha = 0.25f) else Color.Transparent
    val textColor = if (selected) LegalGold else Color.White.copy(alpha = 0.9f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 20.sp, modifier = Modifier.width(32.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = textColor, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 16.sp)
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String, padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}
