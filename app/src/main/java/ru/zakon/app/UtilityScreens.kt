package ru.zakon.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.crypto.Cipher

private val WhiteBg = Color(0xFFFFFFFF)
private val OnWhite = Color(0xFF1A1A1A)
private val MutedGray = Color(0xFF6B6B6B)
private val LineGray = Color(0xFFE0E0E0)
private val AccentGold = Color(0xFF8A6A1A)

internal data class LocalCase(
    val id: String,
    val title: String,
    val notes: String,
    val attachments: List<String>,
    val updatedAt: Long
)

internal class CabinetStorage(private val context: Context) {
    private val crypto = CabinetCrypto(context)
    private val securePrefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "zakon_cabinet_secure_v1", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val legacyPrefs = context.getSharedPreferences("zakon_cabinet", Context.MODE_PRIVATE)
    init { migrateFromLegacyIfNeeded() }

    private fun migrateFromLegacyIfNeeded() {
        val legacyVerifier = legacyPrefs.getString("pin_verifier", null)
        val legacySalt = legacyPrefs.getString("pin_salt", null)
        if (legacyVerifier != null && legacySalt != null && !securePrefs.contains("pin_verifier")) {
            securePrefs.edit().putString("pin_salt", legacySalt).putString("pin_verifier", legacyVerifier).putBoolean("pin_enabled", true).apply()
        }
        val legacyCasesEnc = legacyPrefs.getString("cases_enc", null)
        if (legacyCasesEnc != null && !securePrefs.contains("cases_enc")) {
            securePrefs.edit().putString("cases_enc", legacyCasesEnc).apply()
        }
        legacyPrefs.edit().remove("pin_hash").remove("pin_verifier").remove("pin_salt").remove("cases_json").remove("cases_enc").remove("pin_fail_count").remove("pin_lock_until").apply()
    }

    fun isPinEnabled(): Boolean = securePrefs.getBoolean("pin_enabled", hasVerifier())
    fun hasVerifier(): Boolean = securePrefs.contains("pin_verifier") && securePrefs.contains("pin_salt")
    fun hasPassword(): Boolean = isPinEnabled() && hasVerifier()
    fun isPinPromptDone(): Boolean = securePrefs.getBoolean("pin_prompt_done", false)
    fun markPinPromptDone() { securePrefs.edit().putBoolean("pin_prompt_done", true).apply() }

    fun setPassword(pin: String) {
        require(pin.length >= 6) { "PIN too short" }
        val salt = CabinetPinKdf.newSalt()
        val verifier = CabinetPinKdf.derive(pin, salt)
        securePrefs.edit()
            .putString("pin_salt", CabinetPinKdf.toBase64(salt))
            .putString("pin_verifier", CabinetPinKdf.toBase64(verifier))
            .putBoolean("pin_enabled", true).putBoolean("pin_prompt_done", true)
            .putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        salt.fill(0); verifier.fill(0)
        crypto.ensureKey(requireUserAuth = true)
        legacyPrefs.edit().remove("pin_hash").apply()
    }

    fun checkPassword(pin: String): Boolean {
        if (isLockedOut() || !hasVerifier()) return false
        val salt = CabinetPinKdf.fromBase64(securePrefs.getString("pin_salt", null) ?: return false)
        val expected = CabinetPinKdf.fromBase64(securePrefs.getString("pin_verifier", null) ?: return false)
        val ok = try { CabinetPinKdf.matches(pin, salt, expected) } finally { salt.fill(0); expected.fill(0) }
        if (ok) securePrefs.edit().putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        else registerFailedAttempt()
        return ok
    }

    fun disablePin(currentPin: String): Boolean {
        if (!checkPassword(currentPin)) return false
        securePrefs.edit().putBoolean("pin_enabled", false).putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        return true
    }
    fun enablePin() { if (hasVerifier()) securePrefs.edit().putBoolean("pin_enabled", true).apply() }

    fun deletePin(currentPin: String, wipeData: Boolean): Boolean {
        if (hasVerifier() && !checkPassword(currentPin)) return false
        securePrefs.edit().remove("pin_salt").remove("pin_verifier").putBoolean("pin_enabled", false).putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        if (wipeData) wipeAllCabinetData()
        return true
    }

    fun changePassword(oldPin: String, newPin: String): Boolean {
        if (!checkPassword(oldPin) || newPin.length < 6) return false
        setPassword(newPin); return true
    }

    fun wipeAllCabinetData() {
        loadCases().forEach { c -> c.attachments.forEach { deleteAttachment(it) } }
        securePrefs.edit().remove("cases_enc").apply()
        crypto.clearViewCache()
        crypto.encryptedDir().listFiles()?.forEach { it.delete() }
    }

    fun isLockedOut(): Boolean = System.currentTimeMillis() < securePrefs.getLong("pin_lock_until", 0L)
    fun lockRemainingMs(): Long = (securePrefs.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun registerFailedAttempt() {
        val fails = securePrefs.getInt("pin_fail_count", 0) + 1
        val delaySec = when { fails >= 8 -> 300; fails >= 3 -> (1 shl (fails - 3).coerceAtMost(5)).coerceAtMost(60); else -> 0 }
        securePrefs.edit().putInt("pin_fail_count", fails).putLong("pin_lock_until", if (delaySec > 0) System.currentTimeMillis() + delaySec * 1000L else 0L).apply()
    }

    fun loadCases(): List<LocalCase> {
        val enc = securePrefs.getString("cases_enc", null) ?: return emptyList()
        return try { parseCases(crypto.decryptString(enc)) } catch (_: Exception) { emptyList() }
    }

    fun saveCases(cases: List<LocalCase>) {
        val arr = JSONArray()
        cases.forEach { c -> arr.put(JSONObject().apply {
            put("id", c.id); put("title", c.title); put("notes", c.notes)
            put("updatedAt", c.updatedAt); put("attachments", JSONArray(c.attachments))
        }) }
        try { securePrefs.edit().putString("cases_enc", crypto.encryptString(arr.toString())).apply() } catch (_: Exception) { }
    }

    private fun parseCases(raw: String): List<LocalCase> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val att = o.optJSONArray("attachments")
                    val list = buildList { if (att != null) for (j in 0 until att.length()) add(att.getString(j)) }
                    add(LocalCase(o.getString("id"), o.getString("title"), o.optString("notes", ""), list, o.optLong("updatedAt", 0L)))
                }
            }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun importAttachment(uri: Uri, displayName: String?): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            crypto.importAndEncrypt(bytes, displayName ?: uri.lastPathSegment ?: "file")
        } catch (_: Exception) { null }
    }

    fun openAttachment(encName: String, displayName: String): Uri? {
        return try {
            val file = crypto.decryptFileToCache(encName, displayName)
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } catch (_: Exception) { null }
    }

    fun deleteAttachment(encName: String) = crypto.deleteEncryptedFile(encName)
    fun clearViewCache() = crypto.clearViewCache()
    fun biometricCipher(): Cipher? = try { crypto.cipherForEncrypt(crypto.getSecretKey()) } catch (_: Exception) { null }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) { if (ctx is FragmentActivity) return ctx; ctx = ctx.baseContext }
    return null
}

private const val BIO_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
private fun biometricStatus(context: Context): Int = BiometricManager.from(context).canAuthenticate(BIO_AUTHENTICATORS)

private fun showBiometric(activity: FragmentActivity, cipher: Cipher?, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) onError(errString.toString())
        }
        override fun onAuthenticationFailed() { onError("Не распознано") }
    })
    try {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в личный кабинет").setSubtitle("Подтвердите личность")
            .setAllowedAuthenticators(BIO_AUTHENTICATORS).setNegativeButtonText("Пароль").build()
        if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else prompt.authenticate(info)
    } catch (e: Exception) { onError(e.message ?: "Ошибка биометрии") }
}

@Composable
internal fun CabinetScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val storage = remember { CabinetStorage(context) }
    val scope = rememberCoroutineScope()

    var unlocked by remember { mutableStateOf(!storage.hasPassword()) }
    var showAskPin by remember { mutableStateOf(!storage.hasVerifier() && !storage.isPinPromptDone()) }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var settingPin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cases by remember { mutableStateOf(if (!storage.hasPassword()) storage.loadCases() else emptyList()) }
    var editing by remember { mutableStateOf<LocalCase?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var editAttachments by remember { mutableStateOf<List<String>>(emptyList()) }
    var lockTick by remember { mutableStateOf(0) }

    val bioStatus = remember(context) { biometricStatus(context) }
    val bioOk = bioStatus == BiometricManager.BIOMETRIC_SUCCESS

    DisposableEffect(Unit) { onDispose { storage.clearViewCache() } }
    LaunchedEffect(lockTick) { while (storage.isLockedOut()) { delay(500); lockTick++ } }
    LaunchedEffect(unlocked, showAskPin) {
        if (!unlocked && !showAskPin && storage.hasPassword() && bioOk && activity != null && !storage.isLockedOut()) {
            showBiometric(activity, storage.biometricCipher(), onSuccess = { unlocked = true; cases = storage.loadCases() }, onError = { })
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val enc = withContext(Dispatchers.IO) { storage.importAttachment(uri, uri.lastPathSegment) }
            if (enc != null) editAttachments = editAttachments + enc else error = "Не удалось добавить файл"
            busy = false
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val enc = withContext(Dispatchers.IO) { storage.importAttachment(uri, "photo.jpg") }
            if (enc != null) editAttachments = editAttachments + enc else error = "Не удалось добавить фото"
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().padding(padding).background(WhiteBg)) {
        when {
            showAskPin -> {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Личный кабинет", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Задать пароль для защиты дел и заметок?\nПозже — в Настройках → Безопасность.", color = MutedGray, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { settingPin = true; showAskPin = false }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Да, задать пароль") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { storage.markPinPromptDone(); showAskPin = false; unlocked = true; cases = storage.loadCases() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Нет, открыть без пароля") }
                }
            }
            settingPin && !unlocked -> {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Новый пароль", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Минимум 6 символов", color = MutedGray, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(pin, { if (it.length <= 32) pin = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(pinConfirm, { if (it.length <= 32) pinConfirm = it }, label = { Text("Повтор") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFB71C1C), fontSize = 13.sp) }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        error = null
                        when {
                            pin.length < 6 -> error = "Минимум 6 символов"
                            pin != pinConfirm -> error = "Пароли не совпадают"
                            else -> { storage.setPassword(pin); settingPin = false; unlocked = true; cases = storage.loadCases(); pin = ""; pinConfirm = "" }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Сохранить и войти") }
                    TextButton(onClick = { storage.markPinPromptDone(); settingPin = false; unlocked = true; cases = storage.loadCases() }) { Text("Отмена — без пароля", color = MutedGray) }
                }
            }
            !unlocked -> {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Личный кабинет", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Введите пароль", color = MutedGray, fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(pin, { if (it.length <= 32) pin = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !storage.isLockedOut() && !busy, modifier = Modifier.fillMaxWidth())
                    if (storage.isLockedOut()) { Spacer(Modifier.height(8.dp)); Text("Подождите ${storage.lockRemainingMs() / 1000} с", color = Color(0xFFB71C1C)) }
                    error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFB71C1C), fontSize = 13.sp, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        error = null
                        if (storage.isLockedOut()) { error = "Подождите окончания блокировки"; return@Button }
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.Default) { storage.checkPassword(pin) }
                            busy = false
                            if (ok) { unlocked = true; cases = storage.loadCases(); pin = "" }
                            else { error = "Неверный пароль"; lockTick++ }
                        }
                    }, enabled = !busy && !storage.isLockedOut(), colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Войти") }
                    if (storage.hasPassword()) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            error = null
                            val act = activity
                            when {
                                act == null -> error = "Биометрия недоступна"
                                storage.isLockedOut() -> error = "Подождите блокировки"
                                !bioOk -> error = when (bioStatus) {
                                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Нет биометрического датчика"
                                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Добавьте отпечаток или лицо в настройках телефона"
                                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Биометрия временно недоступна"
                                    else -> "Биометрия недоступна (код $bioStatus)"
                                }
                                else -> showBiometric(act, storage.biometricCipher(), onSuccess = { unlocked = true; cases = storage.loadCases() }, onError = { error = it })
                            }
                        }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (bioOk) "Войти по биометрии" else "Биометрия (проверить)") }
                        if (!bioOk) {
                            Spacer(Modifier.height(6.dp))
                            Text("Настройте отпечаток или Face Unlock в системе телефона", color = MutedGray, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Управление паролем — Настройки → Безопасность", color = MutedGray, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
            editing != null -> {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Дело", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(editNotes, { editNotes = it }, label = { Text("Заметки") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text("Вложения (${editAttachments.size})", color = AccentGold, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = !busy) { Text("Фото") }
                        OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Файл") }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(editAttachments) { encName ->
                            Row(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(8.dp)).clickable {
                                val uri = storage.openAttachment(encName, encName.removeSuffix(".enc"))
                                if (uri != null) try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, null); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                    context.startActivity(Intent.createChooser(intent, "Открыть"))
                                } catch (_: Exception) { error = "Не удалось открыть" }
                            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(encName.removeSuffix(".enc").take(40), color = OnWhite, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("✕", color = Color(0xFFB71C1C), modifier = Modifier.clickable {
                                    storage.deleteAttachment(encName)
                                    editAttachments = editAttachments.filter { it != encName }
                                })
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { editing = null }, modifier = Modifier.weight(1f)) { Text("Отмена") }
                        Button(onClick = {
                            val id = editing!!.id
                            val updated = LocalCase(id, editTitle.ifBlank { "Без названия" }, editNotes, editAttachments, System.currentTimeMillis())
                            cases = if (cases.any { it.id == id }) cases.map { if (it.id == id) updated else it } else listOf(updated) + cases
                            storage.saveCases(cases); editing = null
                        }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.White), modifier = Modifier.weight(1f)) { Text("Сохранить") }
                    }
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Личный кабинет", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(if (storage.hasPassword()) "Защищён паролем" else "Без пароля · настройка в «Настройки»", color = MutedGray, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            editing = LocalCase(UUID.randomUUID().toString(), "", "", emptyList(), 0L)
                            editTitle = ""; editNotes = ""; editAttachments = emptyList()
                        }) { Text("+ Дело", color = AccentGold) }
                        if (storage.hasPassword()) {
                            TextButton(onClick = { unlocked = false; pin = ""; storage.clearViewCache() }) { Text("Выйти", color = MutedGray) }
                        }
                    }
                    HorizontalDivider(color = LineGray, modifier = Modifier.padding(vertical = 10.dp))
                    if (cases.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Нет дел.\nНажмите «+ Дело».", color = MutedGray, textAlign = TextAlign.Center)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(cases, key = { it.id }) { c ->
                                Column(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable {
                                    editing = c; editTitle = c.title; editNotes = c.notes; editAttachments = c.attachments
                                }.padding(14.dp)) {
                                    Text(c.title, color = OnWhite, fontWeight = FontWeight.SemiBold)
                                    if (c.notes.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(c.notes.take(120) + if (c.notes.length > 120) "…" else "", color = MutedGray, fontSize = 13.sp)
                                    }
                                    if (c.attachments.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Вложений: ${c.attachments.size}", color = AccentGold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("О приложении", color = Color(0xFFF5F7FA), fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("ЗаконЪ · версия 0.1.0", color = LegalGold, fontWeight = FontWeight.SemiBold)
        Text("Личный кабинет: белый экран, пароль по желанию, настройка в «Настройки → Безопасность».", color = Color(0xFFF5F7FA).copy(0.88f), fontSize = 14.sp, lineHeight = 20.sp)
        Text("Автор", color = LegalGold, fontWeight = FontWeight.Bold)
        Text("Исаев Владислав Андреевич", color = Color(0xFFF5F7FA).copy(0.88f))
        Text("Сотрудничество: Valdissor@mail.ru", color = Color(0xFFF5F7FA).copy(0.75f), fontSize = 13.sp)
    }
}

@Composable
internal fun MoreScreen(padding: PaddingValues, onConstructor: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) { Text("Инструменты", color = Color(0xFFF5F7FA), fontWeight = FontWeight.Bold, fontSize = 20.sp) }
}

@Composable
internal fun ConstructorScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) { Text("Конструктор документов", color = Color(0xFFF5F7FA), fontWeight = FontWeight.Bold, fontSize = 20.sp) }
}
