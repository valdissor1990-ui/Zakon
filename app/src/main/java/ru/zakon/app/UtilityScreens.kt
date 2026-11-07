package ru.zakon.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val WhiteBg = Color(0xFFFFFFFF)
private val OnWhite = Color(0xFF1A1A1A)
private val MutedGray = Color(0xFF6B6B6B)
private val LineGray = Color(0xFFE0E0E0)
private val AccentGold = Color(0xFF8A6A1A)
private val SoftBg = Color(0xFFF7F7F5)

private fun Context.findAct(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

private fun bioOk(ctx: Context): Boolean {
    val bm = BiometricManager.from(ctx)
    val s = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    if (s == BiometricManager.BIOMETRIC_SUCCESS) return true
    return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
}

private fun showBio(act: FragmentActivity, ok: () -> Unit, err: (String) -> Unit) {
    val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = ok()
        override fun onAuthenticationError(code: Int, msg: CharSequence) {
            if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) err(msg.toString())
        }
    })
    try {
        val bm = BiometricManager.from(act)
        val auth = if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS)
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        else BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Вход в личный кабинет").setSubtitle("Подтвердите личность")
                .setAllowedAuthenticators(auth).setNegativeButtonText("Пароль").build()
        )
    } catch (e: Exception) { err(e.message ?: "Ошибка") }
}

private sealed class CabRoute {
    data object Hub : CabRoute()
    data object Clients : CabRoute()
    data class CEdit(val id: String?) : CabRoute()
    data object Cases : CabRoute()
    data class KEdit(val id: String?) : CabRoute()
    data class KView(val id: String) : CabRoute()
}

@Composable
internal fun CabinetScreen(padding: PaddingValues) {
    val ctx = LocalContext.current
    val act = remember(ctx) { ctx.findAct() }
    val st = remember { CabinetStorage(ctx) }
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(!st.hasPassword()) }
    var askPin by remember { mutableStateOf(!st.hasVerifier() && !st.isPinPromptDone()) }
    var setPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ws by remember { mutableStateOf(if (!st.hasPassword()) st.loadWorkspace() else CabinetWorkspace()) }
    var route by remember { mutableStateOf<CabRoute>(CabRoute.Hub) }
    var tab by remember { mutableStateOf(0) }
    val canBio = remember(ctx) { bioOk(ctx) }
    fun save(n: CabinetWorkspace) { ws = n; st.saveWorkspace(n) }
    DisposableEffect(Unit) { onDispose { st.clearViewCache() } }
    LaunchedEffect(unlocked, askPin) {
        if (!unlocked && !askPin && st.hasPassword() && canBio && act != null && !st.isLockedOut())
            showBio(act, { unlocked = true; ws = st.loadWorkspace() }, {})
    }
    Box(Modifier.fillMaxSize().padding(padding).background(WhiteBg)) {
        when {
            askPin -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Личный кабинет", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                Text("Задать пароль для защиты данных?\nПозже — Настройки → Безопасность.", color = MutedGray, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { setPin = true; askPin = false }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Да, задать пароль") }
                OutlinedButton(onClick = { st.markPinPromptDone(); askPin = false; unlocked = true; ws = st.loadWorkspace() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Нет, без пароля") }
            }
            setPin && !unlocked -> Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
                Text("Новый пароль", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                OutlinedTextField(pin, { if (it.length <= 32) pin = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pin2, { if (it.length <= 32) pin2 = it }, label = { Text("Повтор") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = Color(0xFFB71C1C)) }
                Button(onClick = {
                    error = null
                    when {
                        pin.length < 6 -> error = "Минимум 6 символов"
                        pin != pin2 -> error = "Не совпадают"
                        else -> { st.setPassword(pin); setPin = false; unlocked = true; ws = st.loadWorkspace(); pin = ""; pin2 = "" }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Сохранить") }
                TextButton(onClick = { st.markPinPromptDone(); setPin = false; unlocked = true; ws = st.loadWorkspace() }) { Text("Без пароля", color = MutedGray) }
            }
            !unlocked -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Личный кабинет", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                OutlinedTextField(pin, { if (it.length <= 32) pin = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !st.isLockedOut(), modifier = Modifier.fillMaxWidth())
                if (st.isLockedOut()) Text("Подождите ${st.lockRemainingMs() / 1000} с", color = Color(0xFFB71C1C))
                error?.let { Text(it, color = Color(0xFFB71C1C)) }
                Button(onClick = {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { st.checkPassword(pin) }
                        busy = false
                        if (ok) { unlocked = true; ws = st.loadWorkspace(); pin = "" } else error = "Неверный пароль"
                    }
                }, enabled = !busy && !st.isLockedOut(), colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Войти") }
                OutlinedButton(onClick = {
                    val a = act
                    if (a == null) error = "Нет Activity"
                    else if (!canBio) error = "Биометрия недоступна"
                    else showBio(a, { unlocked = true; ws = st.loadWorkspace() }, { error = it })
                }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Биометрия") }
            }
            else -> {
                BackHandler(enabled = route !is CabRoute.Hub) {
                    route = when (val r = route) {
                        is CabRoute.CEdit -> CabRoute.Clients
                        is CabRoute.KEdit -> if (r.id != null) CabRoute.KView(r.id) else CabRoute.Cases
                        is CabRoute.KView -> CabRoute.Cases
                        CabRoute.Clients, CabRoute.Cases -> CabRoute.Hub
                        else -> CabRoute.Hub
                    }
                }
                when (val r = route) {
                    CabRoute.Hub -> HubUI(ws, st.hasPassword(),
                        onClients = { route = CabRoute.Clients },
                        onCases = { route = CabRoute.Cases },
                        onCase = { route = CabRoute.KView(it); tab = 0 },
                        onLock = { unlocked = false; pin = ""; st.clearViewCache() })
                    CabRoute.Clients -> SimpleList("Клиенты", ws.clients.map { it.id to it.name },
                        onBack = { route = CabRoute.Hub }, onAdd = { route = CabRoute.CEdit(null) }, onOpen = { route = CabRoute.CEdit(it) })
                    is CabRoute.CEdit -> ClientForm(ws.clients.find { it.id == r.id },
                        onBack = { route = CabRoute.Clients },
                        onSave = { c ->
                            val list = if (ws.clients.any { it.id == c.id }) ws.clients.map { if (it.id == c.id) c else it } else listOf(c) + ws.clients
                            save(ws.copy(clients = list)); route = CabRoute.Clients
                        },
                        onDelete = { id -> save(ws.copy(clients = ws.clients.filter { it.id != id })); route = CabRoute.Clients })
                    CabRoute.Cases -> SimpleList("Дела", ws.cases.map { it.id to "${it.title} · ${lab(CaseStatuses, it.status)}" },
                        onBack = { route = CabRoute.Hub }, onAdd = { route = CabRoute.KEdit(null) }, onOpen = { route = CabRoute.KView(it); tab = 0 })
                    is CabRoute.KEdit -> CaseForm(ws.cases.find { it.id == r.id }, ws.clients,
                        onBack = { route = if (r.id != null) CabRoute.KView(r.id!!) else CabRoute.Cases },
                        onSave = { c ->
                            val list = if (ws.cases.any { it.id == c.id }) ws.cases.map { if (it.id == c.id) c else it } else listOf(c) + ws.cases
                            save(ws.copy(cases = list)); route = CabRoute.KView(c.id); tab = 0
                        },
                        onDelete = { id ->
                            save(ws.copy(
                                cases = ws.cases.filter { it.id != id },
                                events = ws.events.filter { it.caseId != id },
                                docs = ws.docs.filter { it.caseId != id },
                                tasks = ws.tasks.filter { it.caseId != id }
                            )); route = CabRoute.Cases
                        })
                    is CabRoute.KView -> CaseView(ws, r.id, tab, { tab = it },
                        onBack = { route = CabRoute.Cases }, onEdit = { route = CabRoute.KEdit(r.id) },
                        st = st, scope = scope, busy = busy, onBusy = { busy = it }, onErr = { error = it }, onSaveWs = { save(it) })
                }
            }
        }
    }
}

@Composable private fun HubUI(ws: CabinetWorkspace, hasPw: Boolean, onClients: () -> Unit, onCases: () -> Unit, onCase: (String) -> Unit, onLock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Личный кабинет", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(if (hasPw) "Защищён · только на устройстве" else "Без пароля · только на устройстве", color = MutedGray, fontSize = 12.sp)
            }
            if (hasPw) TextButton(onClick = onLock) { Text("Выйти", color = MutedGray) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Клиенты", "${ws.clients.size}", Modifier.weight(1f), onClients)
            Stat("Дела", "${ws.cases.size}", Modifier.weight(1f), onCases)
            Stat("Задачи", "${ws.tasks.count { it.status != "done" }}", Modifier.weight(1f), onCases)
        }
        Spacer(Modifier.height(12.dp))
        Text("Разделы", color = AccentGold, fontWeight = FontWeight.SemiBold)
        MenuRow("Клиенты", "Карточки и контакты", onClients)
        MenuRow("Дела", "Хронология, документы, задачи", onCases)
        Spacer(Modifier.height(12.dp))
        Text("Недавние дела", color = AccentGold, fontWeight = FontWeight.SemiBold)
        if (ws.cases.isEmpty()) Text("Создайте клиента и дело", color = MutedGray)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ws.cases.take(8), key = { it.id }) { c ->
                Column(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable { onCase(c.id) }.padding(12.dp)) {
                    Text(c.title, color = OnWhite, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${lab(CaseCategories, c.category)} · ${lab(CaseStatuses, c.status)}", color = MutedGray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun Stat(t: String, v: String, m: Modifier, onClick: () -> Unit) =
    Column(m.border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp)) {
        Text(t, color = MutedGray, fontSize = 11.sp); Text(v, color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }

@Composable private fun MenuRow(t: String, s: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(t, color = OnWhite, fontWeight = FontWeight.SemiBold); Text(s, color = MutedGray, fontSize = 12.sp) }
        Text("›", color = AccentGold, fontSize = 20.sp)
    }

@Composable private fun SimpleList(title: String, items: List<Pair<String, String>>, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Text(title, color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Text("+", color = AccentGold) }
        }
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Пусто", color = MutedGray) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.first }) { (id, label) ->
                Text(label, color = OnWhite, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable { onOpen(id) }.padding(14.dp))
            }
        }
    }
}

@Composable private fun ClientForm(initial: CabClient?, onBack: () -> Unit, onSave: (CabClient) -> Unit, onDelete: (String) -> Unit) {
    var type by remember { mutableStateOf(initial?.type ?: "person") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Text(if (initial == null) "Новый клиент" else "Клиент", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Row { FilterChip(selected = type == "person", onClick = { type = "person" }, label = { Text("Физлицо") }); Spacer(Modifier.width(8.dp)); FilterChip(selected = type == "org", onClick = { type = "org" }, label = { Text("Юрлицо") }) }
        OutlinedTextField(name, { name = it }, label = { Text("ФИО / название") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("Телефон") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text("Заметки") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (name.isNotBlank()) onSave(CabClient(initial?.id ?: UUID.randomUUID().toString(), type, name.trim(), phone.trim(), email.trim(), notes.trim())) },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Сохранить") }
        if (initial != null) TextButton(onClick = { onDelete(initial.id) }) { Text("Удалить", color = Color(0xFFB71C1C)) }
    }
}

@Composable private fun CaseForm(initial: CabCase?, clients: List<CabClient>, onBack: () -> Unit, onSave: (CabCase) -> Unit, onDelete: (String) -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "civil") }
    var status by remember { mutableStateOf(initial?.status ?: "active") }
    var clientId by remember { mutableStateOf(initial?.clientId) }
    var court by remember { mutableStateOf(initial?.court ?: "") }
    var caseNumber by remember { mutableStateOf(initial?.caseNumber ?: "") }
    var subject by remember { mutableStateOf(initial?.subject ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Text(if (initial == null) "Новое дело" else "Дело", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
        Text("Категория", color = MutedGray, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaseCategories.forEach { (k, v) -> FilterChip(selected = category == k, onClick = { category = k }, label = { Text(v) }) }
        }
        Text("Статус", color = MutedGray, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaseStatuses.forEach { (k, v) -> FilterChip(selected = status == k, onClick = { status = k }, label = { Text(v) }) }
        }
        if (clients.isNotEmpty()) {
            Text("Клиент", color = MutedGray, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = clientId == null, onClick = { clientId = null }, label = { Text("—") })
                clients.forEach { c -> FilterChip(selected = clientId == c.id, onClick = { clientId = c.id }, label = { Text(c.name) }) }
            }
        }
        OutlinedTextField(court, { court = it }, label = { Text("Суд") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(caseNumber, { caseNumber = it }, label = { Text("Номер дела") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subject, { subject = it }, label = { Text("Предмет") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text("Заметки") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (title.isNotBlank()) onSave(CabCase(initial?.id ?: UUID.randomUUID().toString(), title.trim(), category, status, clientId, court.trim(), caseNumber.trim(), subject.trim(), notes.trim()))
        }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Сохранить") }
        if (initial != null) TextButton(onClick = { onDelete(initial.id) }) { Text("Удалить дело", color = Color(0xFFB71C1C)) }
    }
}

@Composable private fun CaseView(
    ws: CabinetWorkspace, caseId: String, tab: Int, onTab: (Int) -> Unit,
    onBack: () -> Unit, onEdit: () -> Unit,
    st: CabinetStorage, scope: kotlinx.coroutines.CoroutineScope, busy: Boolean,
    onBusy: (Boolean) -> Unit, onErr: (String) -> Unit, onSaveWs: (CabinetWorkspace) -> Unit
) {
    val c = ws.cases.find { it.id == caseId } ?: return
    val events = ws.events.filter { it.caseId == caseId }.sortedByDescending { it.at }
    val docs = ws.docs.filter { it.caseId == caseId }
    val tasks = ws.tasks.filter { it.caseId == caseId }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onBusy(true)
        scope.launch {
            val name = withContext(Dispatchers.IO) { st.importAttachment(uri, uri.lastPathSegment) }
            onBusy(false)
            if (name == null) onErr("Не удалось добавить файл")
            else {
                val doc = CabDoc(UUID.randomUUID().toString(), caseId, uri.lastPathSegment ?: "Файл", name)
                onSaveWs(ws.copy(docs = listOf(doc) + ws.docs))
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Column(Modifier.weight(1f)) {
                Text(c.title, color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${lab(CaseCategories, c.category)} · ${lab(CaseStatuses, c.status)}", color = MutedGray, fontSize = 12.sp)
            }
            TextButton(onClick = onEdit) { Text("Изменить", color = AccentGold) }
        }
        TabRow(selectedTabIndex = tab, containerColor = WhiteBg, contentColor = AccentGold) {
            listOf("Хронология", "Документы", "Задачи").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(t, fontSize = 13.sp) })
            }
        }
        when (tab) {
            0 -> {
                Row { TextButton(onClick = {
                    val e = CabEvent(UUID.randomUUID().toString(), caseId, "note", "Заметка", "", System.currentTimeMillis())
                    onSaveWs(ws.copy(events = listOf(e) + ws.events))
                }) { Text("+ Событие", color = AccentGold) } }
                if (events.isEmpty()) Text("Нет событий", color = MutedGray)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(events, key = { it.id }) { e ->
                        Column(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(10.dp)).padding(12.dp)) {
                            Text(e.title, color = OnWhite, fontWeight = FontWeight.SemiBold)
                            if (e.body.isNotBlank()) Text(e.body, color = MutedGray, fontSize = 13.sp)
                            Text(fmt(e.at), color = MutedGray, fontSize = 11.sp)
                        }
                    }
                }
            }
            1 -> {
                Row {
                    TextButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !busy) { Text("+ Файл", color = AccentGold) }
                }
                if (docs.isEmpty()) Text("Нет документов", color = MutedGray)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(docs, key = { it.id }) { d ->
                        Row(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.title, color = OnWhite, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                val uri = st.openAttachment(d.encName, d.title)
                                if (uri != null) {
                                    try {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    } catch (_: Exception) { onErr("Нет приложения для просмотра") }
                                } else onErr("Не удалось открыть")
                            }) { Text("Открыть", color = AccentGold) }
                            TextButton(onClick = {
                                st.deleteAttachment(d.encName)
                                onSaveWs(ws.copy(docs = ws.docs.filter { it.id != d.id }))
                            }) { Text("✕", color = Color(0xFFB71C1C)) }
                        }
                    }
                }
            }
            2 -> {
                Row { TextButton(onClick = {
                    val t = CabTask(UUID.randomUUID().toString(), caseId, "Новая задача")
                    onSaveWs(ws.copy(tasks = listOf(t) + ws.tasks))
                }) { Text("+ Задача", color = AccentGold) } }
                if (tasks.isEmpty()) Text("Нет задач", color = MutedGray)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = OnWhite, fontWeight = FontWeight.SemiBold)
                                Text(lab(listOf("new" to "Новая", "doing" to "В работе", "done" to "Выполнена", "cancel" to "Отменена"), t.status), color = MutedGray, fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                val ns = if (t.status == "done") "new" else "done"
                                onSaveWs(ws.copy(tasks = ws.tasks.map { if (it.id == t.id) it.copy(status = ns, updatedAt = System.currentTimeMillis()) else it }))
                            }) { Text(if (t.status == "done") "↩" else "✓", color = AccentGold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("О приложении", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("ЗаконЪ · 0.2.0", color = AccentGold, fontWeight = FontWeight.SemiBold)
        Text("Кабинет: клиенты, дела, хронология, документы, задачи. Данные только на устройстве.", color = MutedGray, fontSize = 14.sp)
        Text("Исаев Владислав Андреевич", color = OnWhite)
        Text("Valdissor@mail.ru", color = MutedGray, fontSize = 13.sp)
    }
}

@Composable
internal fun MoreScreen(padding: PaddingValues, onConstructor: () -> Unit) {
    Column(Modifier.padding(padding).padding(18.dp)) { Text("Инструменты", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
}

@Composable
internal fun ConstructorScreen(padding: PaddingValues) {
    Column(Modifier.padding(padding).padding(18.dp)) { Text("Конструктор", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
}
