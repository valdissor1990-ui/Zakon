package ru.zakon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    Box(Modifier.fillMaxSize()) {
        // Full-screen background - Crop keeps aspect ratio, no stretch
        Image(
            painter = painterResource(id = R.drawable.grok_1793951493468),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Dark gradient overlay so text and cards stay readable
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.65f)
                        )
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.45f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = LegalGold,
                        actionIconContentColor = LegalGold
                    ),
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            Text("ЗаконЪ", fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                            if (route == "home") Text(
                                "ПРАВОВАЯ БИБЛИОТЕКА",
                                color = LegalGold,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.5.sp
                            )
                        }
                    },
                    navigationIcon = {
                        if (!root) Text(
                            "‹",
                            Modifier.clickable { back() }.padding(horizontal = 18.dp),
                            color = LegalGold,
                            fontSize = 34.sp
                        )
                    },
                    actions = {
                        RussianFlagMark()
                        Spacer(Modifier.width(16.dp))
                    }
                )
            },
            bottomBar = {
                if (root) NavigationBar(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White
                ) {
                    NavigationBarItem(
                        selected = route == "home",
                        onClick = { stack.clear(); stack.add("home") },
                        icon = { Text("§", fontSize = 21.sp) },
                        label = { Text("База") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LegalGold,
                            selectedTextColor = LegalGold,
                            unselectedIconColor = Color.White.copy(alpha = 0.7f),
                            unselectedTextColor = Color.White.copy(alpha = 0.7f),
                            indicatorColor = LegalGold.copy(alpha = 0.2f)
                        )
                    )
                    NavigationBarItem(
                        selected = route == "search",
                        onClick = { stack.clear(); stack.add("search") },
                        icon = { Text("⌕", fontSize = 22.sp) },
                        label = { Text("Поиск") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LegalGold,
                            selectedTextColor = LegalGold,
                            unselectedIconColor = Color.White.copy(alpha = 0.7f),
                            unselectedTextColor = Color.White.copy(alpha = 0.7f),
                            indicatorColor = LegalGold.copy(alpha = 0.2f)
                        )
                    )
                    NavigationBarItem(
                        selected = route == "cabinet",
                        onClick = { stack.clear(); stack.add("cabinet") },
                        icon = { Text("♙", fontSize = 20.sp) },
                        label = { Text("Кабинет") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LegalGold,
                            selectedTextColor = LegalGold,
                            unselectedIconColor = Color.White.copy(alpha = 0.7f),
                            unselectedTextColor = Color.White.copy(alpha = 0.7f),
                            indicatorColor = LegalGold.copy(alpha = 0.2f)
                        )
                    )
                    NavigationBarItem(
                        selected = route == "more",
                        onClick = { stack.clear(); stack.add("more") },
                        icon = { Text("•••", fontSize = 18.sp) },
                        label = { Text("Ещё") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LegalGold,
                            selectedTextColor = LegalGold,
                            unselectedIconColor = Color.White.copy(alpha = 0.7f),
                            unselectedTextColor = Color.White.copy(alpha = 0.7f),
                            indicatorColor = LegalGold.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        ) { padding ->
            when {
                route == "home" -> DocumentsScreen(store, padding, { navigate("document/" + it) }, { navigate("constructor") }, { navigate("category/" + it) })
                route == "search" -> SearchScreen(store, query, { query = it }, padding) { navigate("article/" + it) }
                route == "cabinet" -> CabinetScreen(padding)
                route == "more" -> MoreScreen(padding) { navigate("constructor") }
                route == "constructor" -> ConstructorScreen(padding)
                route.startsWith("category/") -> CategoryScreen(store, route.removePrefix("category/"), padding) { navigate("document/" + it) }
                route.startsWith("document/") -> DocumentScreen(store, route.removePrefix("document/"), padding) { navigate("article/" + it) }
                route.startsWith("article/") -> ArticleScreen(store, route.removePrefix("article/"), padding)
                else -> DocumentsScreen(store, padding, { navigate("document/" + it) }, { navigate("constructor") }, { navigate("category/" + it) })
            }
        }
    }
}
