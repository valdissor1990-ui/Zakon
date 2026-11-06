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
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val GlassWhite = Color.White.copy(alpha = 0.18f)
private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val GlassDark = Color(0xFF0D1B2A).copy(alpha = 0.75f)

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

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !root && drawerState.isClosed) { back() }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.grok_1793951493468),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.25f),
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
                        Text(
                            "ЗаконЪ",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                        Text(
                            "Правовая библиотека",
                            color = LegalGold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    Spacer(Modifier.height(8.dp))

                    DrawerItem("🏠", "Главная", route == "home") {
                        stack.clear(); stack.add("home")
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem("📖", "Кодексы", route.startsWith("category/code")) {
                        stack.clear(); stack.add("category/code")
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem("⚖", "Статьи и законы", route.startsWith("category/law")) {
                        stack.clear(); stack.add("category/law")
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem("★", "Избранное", route == "favorites") {
                        stack.clear(); stack.add("favorites")
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem("⌕", "Поиск", route == "search") {
                        stack.clear(); stack.add("search")
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem("⚙", "Настройки", route == "settings") {
                        stack.clear(); stack.add("settings")
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem("ℹ", "О приложении", route == "about") {
                        stack.clear(); stack.add("about")
                        scope.launch { drawerState.close() }
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
                                "ЗаконЪ",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                letterSpacing = 0.5.sp
                            )
                        },
                        navigationIcon = {
                            Text(
                                "☰",
                                Modifier
                                    .clickable { scope.launch { drawerState.open() } }
                                    .padding(horizontal = 16.dp),
                                color = Color.White,
                                fontSize = 24.sp
                            )
                        },
                        actions = {
                            Text(
                                "⚙",
                                Modifier
                                    .clickable {
                                        stack.clear(); stack.add("settings")
                                    }
                                    .padding(horizontal = 16.dp),
                                color = Color.White,
                                fontSize = 22.sp
                            )
                        }
                    )
                }
            ) { padding ->
                when {
                    route == "home" -> HomeGlassScreen(padding) { target ->
                        when (target) {
                            "codes" -> navigate("category/code")
                            "laws" -> navigate("category/law")
                            "search" -> navigate("search")
                            "favorites" -> navigate("favorites")
                        }
                    }
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
private fun HomeGlassScreen(padding: PaddingValues, onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.35f))

        // 2x2 glass cards
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassCard(
                    icon = "📖",
                    title = "Кодексы\nРоссии",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("codes") }
                )
                GlassCard(
                    icon = "⚖",
                    title = "Статьи\nи законы",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("laws") }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassCard(
                    icon = "⌕",
                    title = "Поиск\nпо базе",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("search") }
                )
                GlassCard(
                    icon = "★",
                    title = "Избранное",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("favorites") }
                )
            }
        }

        Spacer(Modifier.weight(0.45f))
    }
}

@Composable
private fun GlassCard(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(130.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.10f)
                    )
                )
            )
            .border(1.dp, GlassBorder, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(icon, fontSize = 32.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String, padding: PaddingValues) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}
