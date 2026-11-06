package ru.zakon.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.crypto.Cipher

private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val TextOnDark = Color(0xFFF5F7FA)

private data class LocalCase(
    val id: String,
    val title: String,
    val notes: String,
    /** Encrypted file names inside app-private cabinet_enc/ */
    val attachments: List<String>,
    val updatedAt: Long
)

/**
 * Secure local cabinet storage:
 * - PIN: PBKDF2-HMAC-SHA256 (600k) + random salt + constant-time compare
 * - Cases JSON: AES-256-GCM via Android Keystore
 * - Attachments: copied into app-private storage and encrypted at rest
 * - Lockout after failed PIN attempts
 * - Legacy migration from plain pin_hash / plaintext cases_json
 */
private class CabinetStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("zakon_cabinet", Context.MODE_PRIVATE)
    private val crypto = CabinetCrypto(context)

    fun hasPassword(): Boolean =
        prefs.contains("pin_verifier") || prefs.contains("pin_hash")

    fun isLegacyPin(): Boolean =
        !prefs.contains("pin_verifier") && prefs.contains("pin_hash")

    fun setPassword(pin: String) {
        val salt = CabinetPinKdf.newSalt()
        val verifier = CabinetPinKdf.derive(pin, salt)
        prefs.edit()
            .putString("pin_salt", CabinetPinKdf.toBase64(salt))
            .putString("pin_verifier", CabinetPinKdf.toBase64(verifier))
            .remove("pin_hash")
            .putInt("pin_fail_count", 0)
            .putLong("pin_lock_until", 0L)
            .apply()
        crypto.ensureKey(requireUserAuth = true)
    }

    fun checkPassword(pin: String): Boolean {
        if (isLockedOut()) return false
        val ok = when {
            prefs.contains("pin_verifier") -> {
                val salt = CabinetPinKdf.fromBase64(prefs.getString("pin_salt", "") ?: return false)
                val expected = CabinetPinKdf.fromBase64(prefs.getString("pin_verifier", "") ?: return false)
                CabinetPinKdf.matches(pin, salt, expected)
            }
            prefs.contains("pin_hash") -> {
                // Legacy: migrate on success
                val legacyOk = prefs.getString("pin_hash", null) == pin.hashCode().toString()
                if (legacyOk) setPassword(pin)
                legacyOk
            }
            else -> false
        }
        if (ok) {
            prefs.edit().putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        } else {
            registerFailedAttempt()
        }
        return ok
    }

    fun isLockedOut(): Boolean =
        System.currentTimeMillis() < prefs.getLong("pin_lock_until", 0L)

    fun lockRemainingMs(): Long =
        (prefs.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun registerFailedAttempt() {
        val fails = prefs.getInt("pin_fail_count", 0) + 1
        // 1s, 2s, 4s ... max 60s after 3+ fails; after 8 fails force 5 min
        val delaySec = when {
            fails >= 8 -> 300
            fails >= 3 -> (1 shl (fails - 3).coerceAtMost(5)).coerceAtMost(60)
            else -> 0
        }
        prefs.edit()
            .putInt("pin_fail_count", fails)
            .putLong("pin_lock_until", if (delaySec > 0) System.currentTimeMillis() + delaySec * 1000L else 0L)
            .apply()
    }

    fun loadCases(): List<LocalCase> {
        val enc = prefs.getString("cases_enc", null)
        val raw = if (enc != null) {
            try {
                crypto.decryptString(enc)
            } catch (_: Exception) {
                return emptyList()
            }
        } else {
            // Legacy plaintext migration path
            prefs.getString("cases_json", "[]") ?: "[]"
        }
        return parseCases(raw).also {
            if (enc == null && it.isNotEmpty()) {
                // migrate plaintext -> encrypted
                saveCases(it)
                prefs.edit().remove("cases_json").apply()
            }
        }
    }

    fun saveCases(cases: List<LocalCase>) {
        val arr = JSONArray()
        cases.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("title", c.title)
                put("notes", c.notes)
                put("updatedAt", c.updatedAt)
                put("attachments", JSONArray(c.attachments))
            })
        }
        val plain = arr.toString()
        try {
            val payload = crypto.encryptString(plain)
            prefs.edit()
                .putString("cases_enc", payload)
                .remove("cases_json")
                .apply()
        } catch (_: Exception) {
            // last resort: do not write plaintext if crypto failed mid-session
        }
    }

    private fun parseCases(raw: String): List<LocalCase> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val att = o.optJSONArray("attachments")
                    val list = buildList {
                        if (att != null) for (j in 0 until att.length()) add(att.getString(j))
                    }
                    add(
                        LocalCase(
                            o.getString("id"),
                            o.getString("title"),
                            o.optString("notes", ""),
                            list,
                            o.optLong("updatedAt", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun importAttachment(uri: Uri, displayName: String?): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val name = displayName ?: uri.lastPathSegment ?: "file"
            crypto.importAndEncrypt(bytes, name)
        } catch (_: Exception) {
            null
        }
    }

    fun openAttachment(encName: String, displayName: String): Uri? {
        return try {
            val file = crypto.decryptFileToCache(encName, displayName)
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteAttachment(encName: String) {
        crypto.deleteEncryptedFile(encName)
    }

    fun clearViewCache() = crypto.clearViewCache()

    fun securityLevel(): String = try { crypto.securityLevelLabel() } catch (_: Exception) { "—" }

    fun biometricCipher(): Cipher? {
        return try {
            val key = crypto.getSecretKey()
            crypto.cipherForEncrypt(key)
        } catch (_: Exception) {
            null
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private const val BIO_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

private fun biometricStatus(context: Context): Int =
    BiometricManager.from(context).canAuthenticate(BIO_AUTHENTICATORS)

private fun showBiometric(
    activity: FragmentActivity,
    cipher: Cipher?,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
            ) {
                onError(errString.toString())
            }
        }
        override fun onAuthenticationFailed() {
            onError("Не удалось распознать биометрию")
        }
    })
    try {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в личный кабинет")
            .setSubtitle("Данные зашифрованы на устройстве (AES-GCM / Keystore)")
            .setAllowedAuthenticators(BIO_AUTHENTICATORS)
            .setNegativeButtonText("Пароль")
        val info = builder.build()
        if (cipher != null) {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } else {
            prompt.authenticate(info)
        }
    } catch (e: Exception) {
        onError(e.message ?: "Ошибка запуска биометрии")
    }
}

@Composable
internal fun CabinetScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val storage = remember { CabinetStorage(context) }
    val scope = rememberCoroutineScope()

    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cases by remember { mutableStateOf<List<LocalCase>>(emptyList()) }
    var editing by remember { mutableStateOf<LocalCase?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var editAttachments by remember { mutableStateOf<List<String>>(emptyList()) }
    var lockTick by remember { mutableStateOf(0) }

    val bioStatus = remember(context) { biometricStatus(context) }
    val bioOk = bioStatus == BiometricManager.BIOMETRIC_SUCCESS
    val secLevel = remember { storage.securityLevel() }

    DisposableEffect(Unit) {
        onDispose { storage.clearViewCache() }
    }

    LaunchedEffect(lockTick) {
        while (storage.isLockedOut()) {
            delay(500)
            lockTick++
        }
    }

    LaunchedEffect(Unit) {
        if (storage.hasPassword() && bioOk && activity != null && !unlocked && !storage.isLockedOut()) {
            val cipher = storage.biometricCipher()
            showBiometric(activity, cipher, onSuccess = {
                unlocked = true
                cases = storage.loadCases()
            }, onError = { })
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val name = uri.lastPathSegment
            val enc = withContext(Dispatchers.IO) { storage.importAttachment(uri, name) }
            if (enc != null) editAttachments = editAttachments + enc else error = "Не удалось зашифровать файл"
            busy = false
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val enc = withContext(Dispatchers.IO) { storage.importAttachment(uri, "photo.jpg") }
            if (enc != null) editAttachments = editAttachments + enc else error = "Не удалось зашифровать фото"
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        if (!unlocked) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Личный кабинет", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Дела и вложения шифруются на устройстве (AES-256-GCM, Android Keystore). Данные не отправляются на сервер.",
                    color = LegalGold.copy(alpha = 0.95f),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text("Уровень ключа: $secLevel", color = TextOnDark.copy(0.55f), fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (storage.hasPassword()) "Введите пароль или войдите по биометрии"
                    else "Задайте пароль для защиты данных",
                    color = TextOnDark.copy(0.8f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 32) pin = it },
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !storage.isLockedOut() && !busy,
                    colors = cabinetFieldColors(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!storage.hasPassword()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinConfirm,
                        onValueChange = { if (it.length <= 32) pinConfirm = it },
                        label = { Text("Повтор пароля") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = cabinetFieldColors(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (storage.isLockedOut()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Слишком много попыток. Подождите ${storage.lockRemainingMs() / 1000} с",
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null
                        if (storage.isLockedOut()) {
                            error = "Подождите окончания блокировки"
                            return@Button
                        }
                        if (pin.length < 4) {
                            error = "Минимум 4 символа"
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                if (!storage.hasPassword()) {
                                    if (pin != pinConfirm) "mismatch"
                                    else {
                                        storage.setPassword(pin)
                                        "ok"
                                    }
                                } else if (storage.checkPassword(pin)) "ok" else "bad"
                            }
                            busy = false
                            when (result) {
                                "ok" -> {
                                    unlocked = true
                                    cases = withContext(Dispatchers.IO) { storage.loadCases() }
                                }
                                "mismatch" -> error = "Пароли не совпадают"
                                else -> {
                                    error = "Неверный пароль"
                                    lockTick++
                                }
                            }
                        }
                    },
                    enabled = !busy && !storage.isLockedOut(),
                    colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        if (storage.hasPassword()) "Войти по паролю" else "Создать пароль и войти",
                        fontWeight = FontWeight.Bold
                    )
                }

                if (storage.hasPassword()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            error = null
                            val act = activity
                            when {
                                act == null -> error = "Биометрия недоступна (нет Activity)"
                                storage.isLockedOut() -> error = "Подождите окончания блокировки"
                                !bioOk -> error = when (bioStatus) {
                                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Нет биометрического датчика"
                                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Биометрия временно недоступна"
                                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Добавьте отпечаток или лицо в настройках"
                                    else -> "Биометрия недоступна (код $bioStatus)"
                                }
                                else -> {
                                    val cipher = storage.biometricCipher()
                                    showBiometric(act, cipher, onSuccess = {
                                        unlocked = true
                                        cases = storage.loadCases()
                                    }, onError = { error = it })
                                }
                            }
                        },
                        border = BorderStroke(1.dp, LegalGold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Войти по биометрии", fontWeight = FontWeight.SemiBold) }
                }
            }
        } else if (editing != null) {
            Column(Modifier.fillMaxSize()) {
                Text("Дело / материал", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Файлы копируются в защищённое хранилище приложения и шифруются. Открытие — через временную расшифрованную копию.",
                    color = TextOnDark.copy(0.65f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    label = { Text("Название") },
                    colors = cabinetFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = editNotes,
                    onValueChange = { editNotes = it },
                    label = { Text("Заметки") },
                    minLines = 5,
                    colors = cabinetFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("Вложения (${editAttachments.size}) · зашифрованы", color = LegalGold, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(
                        onClick = { pickImage.launch("image/*") },
                        enabled = !busy,
                        border = BorderStroke(1.dp, GlassBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark)
                    ) { Text("Фото") }
                    OutlinedButton(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        enabled = !busy,
                        border = BorderStroke(1.dp, GlassBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark)
                    ) { Text("Файл") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(editAttachments) { encName ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(0.1f), RoundedCornerShape(10.dp))
                                .clickable {
                                    val uri = storage.openAttachment(encName, encName.removeSuffix(".enc"))
                                    if (uri != null) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, null)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Открыть"))
                                        } catch (_: Exception) {
                                            error = "Не удалось открыть файл"
                                        }
                                    } else error = "Ошибка расшифровки вложения"
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                encName.removeSuffix(".enc").take(40),
                                color = TextOnDark,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "✕",
                                color = Color(0xFFFF8A80),
                                modifier = Modifier.clickable {
                                    storage.deleteAttachment(encName)
                                    editAttachments = editAttachments.filter { it != encName }
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { editing = null },
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark),
                        modifier = Modifier.weight(1f)
                    ) { Text("Отмена") }
                    Button(
                        onClick = {
                            val id = editing!!.id
                            val updated = LocalCase(
                                id,
                                editTitle.ifBlank { "Без названия" },
                                editNotes,
                                editAttachments,
                                System.currentTimeMillis()
                            )
                            cases = if (cases.any { it.id == id }) cases.map { if (it.id == id) updated else it }
                            else listOf(updated) + cases
                            storage.saveCases(cases)
                            editing = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                        modifier = Modifier.weight(1f)
                    ) { Text("Сохранить") }
                }
            }
        } else {
            Text(
                "Материалы зашифрованы на устройстве ($secLevel). Доступны только после входа.",
                color = LegalGold.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Личный кабинет", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Локально · AES-GCM", color = TextOnDark.copy(0.65f), fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        editing = LocalCase(UUID.randomUUID().toString(), "", "", emptyList(), 0L)
                        editTitle = ""; editNotes = ""; editAttachments = emptyList()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = LegalGold)
                ) { Text("+ Дело") }
                TextButton(
                    onClick = {
                        unlocked = false
                        pin = ""
                        storage.clearViewCache()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextOnDark.copy(0.7f))
                ) { Text("Выйти") }
            }
            Spacer(Modifier.height(12.dp))
            if (cases.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Пока нет дел.\nСоздайте дело, добавьте заметки и файлы.\nВсё будет зашифровано на устройстве.",
                        color = TextOnDark.copy(0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cases, key = { it.id }) { c ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(0.18f), Color.White.copy(0.08f))
                                    ),
                                    RoundedCornerShape(16.dp)
                                )
                                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                                .clickable {
                                    editing = c
                                    editTitle = c.title
                                    editNotes = c.notes
                                    editAttachments = c.attachments
                                }
                                .padding(14.dp)
                        ) {
                            Column {
                                Text(c.title, color = TextOnDark, fontWeight = FontWeight.SemiBold)
                                if (c.notes.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        c.notes.take(120) + if (c.notes.length > 120) "…" else "",
                                        color = TextOnDark.copy(0.7f),
                                        fontSize = 13.sp
                                    )
                                }
                                if (c.attachments.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Вложений: ${c.attachments.size} (шифр.)", color = LegalGold, fontSize = 12.sp)
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
private fun cabinetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextOnDark,
    unfocusedTextColor = TextOnDark,
    focusedBorderColor = LegalGold,
    unfocusedBorderColor = Color.White.copy(0.35f),
    focusedLabelColor = LegalGold,
    unfocusedLabelColor = TextOnDark.copy(0.6f),
    cursorColor = LegalGold,
    disabledTextColor = TextOnDark.copy(0.4f),
    disabledBorderColor = Color.White.copy(0.2f)
)

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("О приложении", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("ЗаконЪ · версия 0.1.0", color = LegalGold, fontWeight = FontWeight.SemiBold)
        Text("Безопасность кабинета", color = LegalGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Пароль: PBKDF2-HMAC-SHA256 (600 000 итераций), случайная соль, сравнение за постоянное время.\n" +
                "Данные дел: AES-256-GCM, ключ в Android Keystore (StrongBox при наличии).\n" +
                "Вложения копируются в приватное хранилище и шифруются; просмотр — через временную копию.\n" +
                "Биометрия может использоваться вместе с криптографическим объектом Keystore.\n" +
                "При неверном пароле включается прогрессивная блокировка попыток.",
            color = TextOnDark.copy(0.88f),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        Text("Автор", color = LegalGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Исаев Владислав Андреевич", color = TextOnDark.copy(0.88f), fontSize = 14.sp)
        Text(
            "Материалы правовой базы носят справочный характер. Создатель не несёт ответственности за применение информации. " +
                "Сотрудничество: Valdissor@mail.ru",
            color = TextOnDark.copy(0.75f),
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
internal fun MoreScreen(padding: PaddingValues, onConstructor: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
        Text("Инструменты", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
internal fun ConstructorScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
        Text("Конструктор документов", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}
