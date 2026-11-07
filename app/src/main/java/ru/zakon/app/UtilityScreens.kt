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

private sealed class R {
    data object Hub : R()
    data object Clients : R()
    data class CEdit(val id: String?) : R()
    data object Cases : R()
    data class KEdit(val id: String?) : R()
    data class KView(val id: String) : R()
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
    var route by remember { mutableStateOf<R>(R.Hub) }
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
                BackHandler(enabled = route !is R.Hub) {
                    route = when (val r = route) {
                        is R.CEdit -> R.Clients
                        is R.KEdit -> if (r.id != null) R.KView(r.id) else R.Cases
                        is R.KView -> R.Cases
                        R.Clients, R.Cases -> R.Hub
                        else -> R.Hub
                    }
                }
                when (val r = route) {
                    R.Hub -> HubUI(ws, st.hasPassword(),
                        onClients = { route = R.Clients },
                        onCases = { route = R.Cases },
                        onCase = { route = R.KView(it); tab = 0 },
                        onLock = { unlocked = false; pin = ""; st.clearViewCache() })
                    R.Clients -> SimpleList("Клиенты", ws.clients.map { it.id to it.name },
                        onBack = { route = R.Hub }, onAdd = { route = R.CEdit(null) }, onOpen = { route = R.CEdit(it) })
                    is R.CEdit -> ClientForm(ws.clients.find { it.id == r.id },
                        onBack = { route = R.Clients },
                        onSave = { c ->
                            val list = if (ws.clients.any { it.id == c.id }) ws.clients.map { if (it.id == c.id) c else it } else listOf(c) + ws.clients
                            save(ws.copy(clients = list)); route = R.Clients
                        },
                        onDelete = { id -> save(ws.copy(clients = ws.clients.filter { it.id != id })); route = R.Clients })
                    R.Cases -> SimpleList("Дела", ws.cases.map { it.id to "${it.title} · ${lab(CaseStatuses, it.status)}" },
                        onBack = { route = R.Hub }, onAdd = { route = R.KEdit(null) }, onOpen = { route = R.KView(it); tab = 0 })
                    is R.KEdit -> CaseForm(ws.cases.find { it.id == r.id }, ws.clients,
                        onBack = { route = if (r.id != null) R.KView(r.id!!) else R.Cases },
                        onSave = { c ->
                            val list = if (ws.cases.any { it.id == c.id }) ws.cases.map { if (it.id == c.id) c else it } else listOf(c) + ws.cases
                            save(ws.copy(cases = list)); route = R.KView(c.id); tab = 0
                        },
                        onDelete = { id ->
                            save(ws.copy(
                                cases = ws.cases.filter { it.id != id },
                                events = ws.events.filter { it.caseId != id },
                                docs = ws.docs.filter { it.caseId != id },
                                tasks = ws.tasks.filter { it.caseId != id }
                            )); route = R.Cases
                        })
                    is R.KView -> CaseView(ws, r.id, tab, { tab = it },
                        onBack = { route = R.Cases }, onEdit = { route = R.KEdit(r.id) },
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
        Row { FilterChip(type == "person", { type = "person" }, { Text("Физлицо") }); Spacer(Modifier.width(8.dp)); FilterChip(type == "org", { type = "org" }, { Text("Юрлицо") }) }
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
    var cat by remember { mutableStateOf(initial?.category ?: "civil") }
    var status by remember { mutableStateOf(initial?.status ?: "active") }
    var clientId by remember { mutableStateOf(initial?.clientId) }
    var court by remember { mutableStateOf(initial?.court ?: "") }
    var num by remember { mutableStateOf(initial?.caseNumber ?: "") }
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
            CaseCategories.forEach { (k, v) -> FilterChip(cat == k, { cat = k }, { Text(v, fontSize = 12.sp) }) }
        }
        Text("Статус", color = MutedGray, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaseStatuses.forEach { (k, v) -> FilterChip(status == k, { status = k }, { Text(v, fontSize = 12.sp) }) }
        }
        Text("Клиент", color = MutedGray, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(clientId == null, { clientId = null }, { Text("Нет") })
            clients.forEach { c -> FilterChip(clientId == c.id, { clientId = c.id }, { Text(c.name.take(20)) }) }
        }
        OutlinedTextField(num, { num = it }, label = { Text("Номер дела") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(court, { court = it }, label = { Text("Суд") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subject, { subject = it }, label = { Text("Предмет") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text("Заметки") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (title.isNotBlank()) onSave(CabCase(initial?.id ?: UUID.randomUUID().toString(), title.trim(), cat, status, clientId, court.trim(), num.trim(), subject.trim(), notes.trim()))
        }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Сохранить") }
        if (initial != null) TextButton(onClick = { onDelete(initial.id) }) { Text("Удалить дело", color = Color(0xFFB71C1C)) }
    }
}

@Composable private fun CaseView(
    ws: CabinetWorkspace, id: String, tab: Int, onTab: (Int) -> Unit,
    onBack: () -> Unit, onEdit: () -> Unit, st: CabinetStorage,
    scope: kotlinx.coroutines.CoroutineScope, busy: Boolean, onBusy: (Boolean) -> Unit,
    onErr: (String) -> Unit, onSaveWs: (CabinetWorkspace) -> Unit
) {
    val c = ws.cases.find { it.id == id }
    val ctx = LocalContext.current
    if (c == null) {
        Column(Modifier.padding(16.dp)) { TextButton(onClick = onBack) { Text("‹", color = AccentGold) }; Text("Не найдено", color = OnWhite) }
        return
    }
    val client = ws.clients.find { it.id == c.clientId }
    val events = ws.events.filter { it.caseId == id }.sortedByDescending { it.at }
    val docs = ws.docs.filter { it.caseId == id }
    val tasks = ws.tasks.filter { it.caseId == id }
    var evTitle by remember { mutableStateOf("") }
    var taskTitle by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onBusy(true)
            val enc = withContext(Dispatchers.IO) { st.importAttachment(uri, uri.lastPathSegment) }
            onBusy(false)
            if (enc == null) onErr("Ошибка файла")
            else onSaveWs(ws.copy(docs = listOf(CabDoc(UUID.randomUUID().toString(), id, (uri.lastPathSegment ?: "doc").substringAfterLast('/'), enc)) + ws.docs))
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", color = AccentGold) }
            Column(Modifier.weight(1f)) {
                Text(c.title, color = OnWhite, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${lab(CaseCategories, c.category)} · ${lab(CaseStatuses, c.status)}", color = MutedGray, fontSize = 12.sp)
            }
            TextButton(onClick = onEdit) { Text("Изм.", color = AccentGold) }
        }
        if (client != null) Text("Клиент: ${client.name}", color = AccentGold, fontSize = 13.sp)
        ScrollableTabRow(tab, edgePadding = 0.dp, containerColor = WhiteBg, contentColor = AccentGold) {
            listOf("Обзор", "Хронология", "Документы", "Задачи").forEachIndexed { i, t ->
                Tab(tab == i, { onTab(i) }, text = { Text(t, fontSize = 13.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.caseNumber.isNotBlank()) Info("Номер", c.caseNumber)
                if (c.court.isNotBlank()) Info("Суд", c.court)
                if (c.subject.isNotBlank()) Info("Предмет", c.subject)
                if (c.notes.isNotBlank()) Info("Заметки", c.notes)
                if (c.caseNumber.isBlank() && c.court.isBlank() && c.subject.isBlank() && c.notes.isBlank())
                    Text("Заполните карточку через «Изм.»", color = MutedGray)
            }
            1 -> {
                OutlinedTextField(evTitle, { evTitle = it }, label = { Text("Новое событие") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    if (evTitle.isNotBlank()) {
                        onSaveWs(ws.copy(events = listOf(CabEvent(UUID.randomUUID().toString(), id, "note", evTitle.trim())) + ws.events))
                        evTitle = ""
                    }
                }) { Text("+ Событие", color = AccentGold) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(events, key = { it.id }) { e ->
                        Column(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(10.dp)).padding(12.dp)) {
                            Text(e.title, color = OnWhite, fontWeight = FontWeight.SemiBold)
                            Text(fmt(e.at), color = MutedGray, fontSize = 12.sp)
                        }
                    }
                }
            }
            2 -> {
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !busy) { Text("+ Файл", color = AccentGold) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(docs, key = { it.id }) { d ->
                        Row(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(10.dp)).clickable {
                            st.openAttachment(d.encName, d.title)?.let { uri ->
                                try {
                                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, null); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Открыть"))
                                } catch (_: Exception) { onErr("Не открыть") }
                            }
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.title, color = OnWhite, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("✕", color = Color(0xFFB71C1C), modifier = Modifier.clickable {
                                st.deleteAttachment(d.encName)
                                onSaveWs(ws.copy(docs = ws.docs.filter { it.id != d.id }))
                            })
                        }
                    }
                }
            }
            3 -> {
                OutlinedTextField(taskTitle, { taskTitle = it }, label = { Text("Новая задача") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    if (taskTitle.isNotBlank()) {
                        onSaveWs(ws.copy(tasks = listOf(CabTask(UUID.randomUUID().toString(), id, taskTitle.trim())) + ws.tasks))
                        taskTitle = ""
                    }
                }) { Text("+ Задача", color = AccentGold) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = OnWhite, fontWeight = FontWeight.SemiBold)
                                Text(t.status, color = MutedGray, fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                onSaveWs(ws.copy(tasks = ws.tasks.map {
                                    if (it.id == t.id) it.copy(status = if (it.status == "done") "new" else "done") else it
                                }))
                            }) { Text(if (t.status == "done") "↩" else "✓", color = AccentGold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Info(t: String, b: String) =
    Column(Modifier.fillMaxWidth().background(SoftBg, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(t, color = AccentGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(b, color = OnWhite, fontSize = 14.sp)
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
