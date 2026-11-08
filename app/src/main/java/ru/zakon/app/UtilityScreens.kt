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
internal fun CabinetScreen(padding: PaddingValues, onProvideBack: ((() -> Boolean) -> Unit)? = null) {
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

    fun popCabinet(): Boolean {
        if (setPin) {
            setPin = false
            askPin = !st.isPinPromptDone()
            pin = ""; pin2 = ""; error = null
            return true
        }
        if (!unlocked) return false
        return when (val r = route) {
            is CabRoute.CEdit -> { route = CabRoute.Clients; true }
            is CabRoute.KEdit -> {
                route = if (r.id != null) CabRoute.KView(r.id) else CabRoute.Cases
                true
            }
            is CabRoute.KView -> { route = CabRoute.Cases; true }
            CabRoute.Clients, CabRoute.Cases -> { route = CabRoute.Hub; true }
            else -> false
        }
    }
    LaunchedEffect(route, unlocked, setPin, askPin) {
        onProvideBack?.invoke { popCabinet() }
    }
    DisposableEffect(Unit) {
        onDispose { onProvideBack?.invoke { false } }
    }
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
                    enabled = !busy,
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
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = OnCab, unfocusedTextColor = OnCab,
                        focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray,
                        focusedLabelColor = AccentGold, unfocusedLabelColor = MutedGray
                    )
                )
                error?.let { Text(it, color = Color(0xFFC62828)) }
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = AccentGold, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Сохранение пароля…", color = MutedGray, fontSize = 14.sp)
                    }
                }
                Button(
                    onClick = {
                        error = null
                        when {
                            pin.length < 6 -> error = "Минимум 6 символов"
                            pin != pin2 -> error = "Пароли не совпадают"
                            busy -> { }
                            else -> {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.Default) { st.setPassword(pin) }
                                        setPin = false
                                        unlocked = true
                                        ws = st.loadWorkspace()
                                        pin = ""; pin2 = ""
                                        error = null
                                    } catch (e: Exception) {
                                        error = e.message ?: "Не удалось сохранить пароль"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(if (busy) "Сохранение…" else "Сохранить пароль") }
                TextButton(onClick = {
                    if (!busy) {
                        st.markPinPromptDone(); setPin = false; unlocked = true; ws = st.loadWorkspace(); pin = ""; pin2 = ""
                    }
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
                BackHandler(enabled = route !is CabRoute.Hub) { popCabinet() }
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
