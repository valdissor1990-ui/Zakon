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

private val CabBg = Color.White
private val OnCab = Color(0xFF1A2332)
private val MutedGray = Color(0xFF5A6577)
private val LineGray = Color(0xFFD0D7E2)
private val AccentGold = Color(0xFF8B6914)
private val SoftBg = Color(0xFFF3F5F8)

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
    LaunchedEffect(unlocked, askPin, setPin) {
        if (!unlocked && !askPin && !setPin && st.hasPassword() && canBio && act != null && !st.isLockedOut())
            showBio(act, { unlocked = true; ws = st.loadWorkspace() }, {})
    }
    Box(Modifier.fillMaxSize().padding(padding).background(CabBg)) {
        when {
            askPin -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Личный кабинет", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                Text("Задать пароль для защиты данных?\nОтпечаток/Face ID заработают после установки пароля.", color = MutedGray, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { setPin = true; askPin = false; unlocked = false; error = null; pin = ""; pin2 = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Да, задать пароль") }
                OutlinedButton(
                    onClick = { st.markPinPromptDone(); askPin = false; unlocked = true; ws = st.loadWorkspace() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnCab)
                ) { Text("Нет, без пароля") }
            }
            setPin -> Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Новый пароль", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Минимум 6 символов. После сохранения можно входить по отпечатку.", color = MutedGray, fontSize = 13.sp)
                OutlinedTextField(
                    pin, { if (it.length <= 32) pin = it },
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = OnCab, unfocusedTextColor = OnCab,
                        focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray,
                        focusedLabelColor = AccentGold, unfocusedLabelColor = MutedGray
                    )
                )
                OutlinedTextField(
                    pin2, { if (it.length <= 32) pin2 = it },
                    label = { Text("Повтор") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = OnCab, unfocusedTextColor = OnCab,
                        focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray,
                        focusedLabelColor = AccentGold, unfocusedLabelColor = MutedGray
                    )
                )
                error?.let { Text(it, color = Color(0xFFC62828)) }
                Button(
                    onClick = {
                        error = null
                        when {
                            pin.length < 6 -> error = "Минимум 6 символов"
                            pin != pin2 -> error = "Пароли не совпадают"
                            else -> {
                                try {
                                    st.setPassword(pin)
                                    setPin = false
                                    unlocked = true
                                    ws = st.loadWorkspace()
                                    pin = ""; pin2 = ""
                                } catch (e: Exception) {
                                    error = e.message ?: "Не удалось сохранить пароль"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Сохранить пароль") }
                TextButton(onClick = {
                    st.markPinPromptDone(); setPin = false; unlocked = true; ws = st.loadWorkspace(); pin = ""; pin2 = ""
                }) { Text("Без пароля", color = MutedGray) }
            }
            !unlocked -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Личный кабинет", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    pin, { if (it.length <= 32) pin = it },
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !st.isLockedOut(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = OnCab, unfocusedTextColor = OnCab,
                        focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray,
                        focusedLabelColor = AccentGold, unfocusedLabelColor = MutedGray
                    )
                )
                if (st.isLockedOut()) Text("Подождите ${st.lockRemainingMs() / 1000} с", color = Color(0xFFC62828))
                error?.let { Text(it, color = Color(0xFFC62828)) }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.Default) { st.checkPassword(pin) }
                            busy = false
                            if (ok) { unlocked = true; ws = st.loadWorkspace(); pin = ""; error = null }
                            else error = "Неверный пароль"
                        }
                    },
                    enabled = !busy && !st.isLockedOut(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(if (busy) "…" else "Войти") }
                if (canBio) {
                    OutlinedButton(
                        onClick = {
                            val a = act
                            if (a == null) error = "Нет Activity"
                            else showBio(a, { unlocked = true; ws = st.loadWorkspace(); error = null }, { error = it })
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnCab)
                    ) { Text("Отпечаток / Face ID") }
                } else {
                    Text("Биометрия недоступна на этом устройстве", color = MutedGray, fontSize = 12.sp)
                }
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
                    CabRoute.Hub -> HubUI(ws, st.hasPassword(), canBio,
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

@Composable private fun HubUI(ws: CabinetWorkspace, hasPw: Boolean, canBio: Boolean, onClients: () -> Unit, onCases: () -> Unit, onCase: (String) -> Unit, onLock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Личный кабинет", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    when {
                        hasPw && canBio -> "Пароль и биометрия · только на устройстве"
                        hasPw -> "Защищён паролем · только на устройстве"
                        else -> "Без пароля · только на устройстве"
                    },
                    color = MutedGray, fontSize = 12.sp
                )
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
                    Text(c.title, color = OnCab, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${lab(CaseCategories, c.category)} · ${lab(CaseStatuses, c.status)}", color = MutedGray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun Stat(t: String, v: String, m: Modifier, onClick: () -> Unit) =
    Column(m.border(1.dp, LineGray, RoundedCornerShape(12.dp)).background(SoftBg, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(t, color = MutedGray, fontSize = 12.sp)
    }

@Composable private fun MenuRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnCab, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, color = MutedGray, fontSize = 12.sp)
        }
        Text("›", color = AccentGold, fontSize = 20.sp)
    }
}

@Composable private fun SimpleList(title: String, items: List<Pair<String, String>>, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Text(title, color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Text("+ Добавить", color = AccentGold) }
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) Text("Пока пусто", color = MutedGray)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.first }) { (id, label) ->
                Text(label, color = OnCab, modifier = Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable { onOpen(id) }.padding(12.dp))
            }
        }
    }
}

@Composable private fun ClientForm(initial: CabClient?, onBack: () -> Unit, onSave: (CabClient) -> Unit, onDelete: (String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
        Text(if (initial == null) "Новый клиент" else "Клиент", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(name, { name = it }, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = OnCab, unfocusedTextColor = OnCab, focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray))
        Button(onClick = { if (name.isNotBlank()) onSave(CabClient(id = initial?.id ?: UUID.randomUUID().toString(), name = name.trim())) },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        if (initial != null) TextButton(onClick = { onDelete(initial.id) }) { Text("Удалить", color = Color(0xFFC62828)) }
    }
}

@Composable private fun CaseForm(initial: CabCase?, clients: List<CabClient>, onBack: () -> Unit, onSave: (CabCase) -> Unit, onDelete: (String) -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
        Text(if (initial == null) "Новое дело" else "Дело", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = OnCab, unfocusedTextColor = OnCab, focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray))
        Button(onClick = { if (title.isNotBlank()) onSave(CabCase(id = initial?.id ?: UUID.randomUUID().toString(), title = title.trim(), clientId = initial?.clientId, category = initial?.category ?: "other", status = initial?.status ?: "active")) },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        if (initial != null) TextButton(onClick = { onDelete(initial.id) }) { Text("Удалить", color = Color(0xFFC62828)) }
    }
}

@Composable private fun CaseView(ws: CabinetWorkspace, caseId: String, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit, onEdit: () -> Unit, st: CabinetStorage, scope: kotlinx.coroutines.CoroutineScope, busy: Boolean, onBusy: (Boolean) -> Unit, onErr: (String?) -> Unit, onSaveWs: (CabinetWorkspace) -> Unit) {
    val c = ws.cases.find { it.id == caseId }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Spacer(Modifier.weight(1f))
            Text(c?.title ?: "Дело", color = OnCab, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text("Изменить", color = AccentGold) }
        }
        Text("Карточка дела", color = MutedGray, fontSize = 13.sp)
    }
}

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("О приложении", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("ЗаконЪ · версия 0.2.0", color = AccentGold, fontWeight = FontWeight.SemiBold)
        Text(
            "Офлайн-справочник кодексов и законов РФ. Личный кабинет: клиенты, дела. Все данные только на устройстве.",
            color = MutedGray, fontSize = 14.sp, lineHeight = 20.sp
        )
        Text("Автор", color = AccentGold, fontWeight = FontWeight.Bold)
        Text("Исаев Владислав Андреевич", color = OnCab)
        Text("Сотрудничество: Valdissor@mail.ru", color = MutedGray, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Справочный характер. Сверяйте тексты с официальными источниками (pravo.gov.ru).",
            color = MutedGray.copy(alpha = 0.8f), fontSize = 12.sp
        )
    }
}

@Composable
internal fun ConstructorScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Конструктор документов", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("Шаблоны заявлений появятся в следующих обновлениях.", color = MutedGray, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
