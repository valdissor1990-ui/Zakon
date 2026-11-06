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

private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val TextOnDark = Color(0xFFF5F7FA)

private data class LocalCase(
    val id: String,
    val title: String,
    val notes: String,
    val attachments: List<String>,
    val updatedAt: Long
)

/**
 * Secure cabinet storage.
 *
 * PIN is NEVER stored. Only salt + PBKDF2 verifier live in EncryptedSharedPreferences
 * (AES-256 keys via Android Keystore MasterKey). Offline dump of the prefs XML
 * does not yield a usable PIN without breaking Keystore + PBKDF2.
 *
 * Case payloads and attachment files are AES-256-GCM with a separate Keystore key.
 */
private class CabinetStorage(private val context: Context) {
    private val crypto = CabinetCrypto(context)

    private val securePrefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "zakon_cabinet_secure_v1",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Plain prefs only for non-secret migration leftovers cleanup. */
    private val legacyPrefs = context.getSharedPreferences("zakon_cabinet", Context.MODE_PRIVATE)

    init {
        migrateFromLegacyIfNeeded()
    }

    private fun migrateFromLegacyIfNeeded() {
        // Move any leftover plaintext secrets into encrypted prefs once, then wipe legacy.
        val legacyVerifier = legacyPrefs.getString("pin_verifier", null)
        val legacySalt = legacyPrefs.getString("pin_salt", null)
        if (legacyVerifier != null && legacySalt != null && !securePrefs.contains("pin_verifier")) {
            securePrefs.edit()
                .putString("pin_salt", legacySalt)
                .putString("pin_verifier", legacyVerifier)
                .putBoolean("pin_enabled", true)
                .apply()
        }
        val legacyCasesEnc = legacyPrefs.getString("cases_enc", null)
        if (legacyCasesEnc != null && !securePrefs.contains("cases_enc")) {
            securePrefs.edit().putString("cases_enc", legacyCasesEnc).apply()
        }
        // Destroy weak legacy hashCode and plaintext cases permanently
        legacyPrefs.edit()
            .remove("pin_hash")
            .remove("pin_verifier")
            .remove("pin_salt")
            .remove("cases_json")
            .remove("cases_enc")
            .remove("pin_fail_count")
            .remove("pin_lock_until")
            .apply()
    }

    fun isPinEnabled(): Boolean =
        securePrefs.getBoolean("pin_enabled", hasVerifier())

    fun hasVerifier(): Boolean =
        securePrefs.contains("pin_verifier") && securePrefs.contains("pin_salt")

    fun hasPassword(): Boolean = isPinEnabled() && hasVerifier()

    fun setPassword(pin: String) {
        require(pin.length >= 6) { "PIN too short" }
        val salt = CabinetPinKdf.newSalt()
        val verifier = CabinetPinKdf.derive(pin, salt)
        securePrefs.edit()
            .putString("pin_salt", CabinetPinKdf.toBase64(salt))
            .putString("pin_verifier", CabinetPinKdf.toBase64(verifier))
            .putBoolean("pin_enabled", true)
            .putInt("pin_fail_count", 0)
            .putLong("pin_lock_until", 0L)
            .apply()
        // Wipe intermediate arrays
        salt.fill(0)
        verifier.fill(0)
        crypto.ensureKey(requireUserAuth = true)
        legacyPrefs.edit().remove("pin_hash").apply()
    }

    fun checkPassword(pin: String): Boolean {
        if (isLockedOut()) return false
        if (!hasVerifier()) return false
        val saltB64 = securePrefs.getString("pin_salt", null) ?: return false
        val verB64 = securePrefs.getString("pin_verifier", null) ?: return false
        val salt = CabinetPinKdf.fromBase64(saltB64)
        val expected = CabinetPinKdf.fromBase64(verB64)
        val ok = try {
            CabinetPinKdf.matches(pin, salt, expected)
        } finally {
            salt.fill(0)
            expected.fill(0)
        }
        if (ok) {
            securePrefs.edit().putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        } else {
            registerFailedAttempt()
        }
        return ok
    }

    /** Disable lock screen but keep encrypted data and verifier (can re-enable with same PIN). */
    fun disablePin(currentPin: String): Boolean {
        if (!checkPassword(currentPin)) return false
        securePrefs.edit().putBoolean("pin_enabled", false).putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        return true
    }

    /** Re-enable existing verifier without changing PIN. */
    fun enablePin() {
        if (hasVerifier()) {
            securePrefs.edit().putBoolean("pin_enabled", true).apply()
        }
    }

    /**
     * Permanently delete PIN material. Requires current PIN.
     * Does not delete cases unless [wipeData] is true.
     */
    fun deletePin(currentPin: String, wipeData: Boolean): Boolean {
        if (hasVerifier() && !checkPassword(currentPin)) return false
        securePrefs.edit()
            .remove("pin_salt")
            .remove("pin_verifier")
            .putBoolean("pin_enabled", false)
            .putInt("pin_fail_count", 0)
            .putLong("pin_lock_until", 0L)
            .apply()
        legacyPrefs.edit().remove("pin_hash").remove("pin_salt").remove("pin_verifier").apply()
        if (wipeData) wipeAllCabinetData()
        return true
    }

    fun changePassword(oldPin: String, newPin: String): Boolean {
        if (!checkPassword(oldPin)) return false
        if (newPin.length < 6) return false
        setPassword(newPin)
        return true
    }

    fun wipeAllCabinetData() {
        val cases = loadCases()
        cases.forEach { c -> c.attachments.forEach { deleteAttachment(it) } }
        securePrefs.edit().remove("cases_enc").apply()
        crypto.clearViewCache()
        crypto.encryptedDir().listFiles()?.forEach { it.delete() }
    }

    fun isLockedOut(): Boolean =
        System.currentTimeMillis() < securePrefs.getLong("pin_lock_until", 0L)

    fun lockRemainingMs(): Long =
        (securePrefs.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun registerFailedAttempt() {
        val fails = securePrefs.getInt("pin_fail_count", 0) + 1
        val delaySec = when {
            fails >= 8 -> 300
            fails >= 3 -> (1 shl (fails - 3).coerceAtMost(5)).coerceAtMost(60)
            else -> 0
        }
        securePrefs.edit()
            .putInt("pin_fail_count", fails)
            .putLong("pin_lock_until", if (delaySec > 0) System.currentTimeMillis() + delaySec * 1000L else 0L)
            .apply()
    }

    fun loadCases(): List<LocalCase> {
        val enc = securePrefs.getString("cases_enc", null) ?: return emptyList()
        return try {
            parseCases(crypto.decryptString(enc))
        } catch (_: Exception) {
            emptyList()
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
        try {
            val payload = crypto.encryptString(arr.toString())
            securePrefs.edit().putString("cases_enc", payload).apply()
        } catch (_: Exception) { }
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
            crypto.importAndEncrypt(bytes, displayName ?: uri.lastPathSegment ?: "file")
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

    fun deleteAttachment(encName: String) = crypto.deleteEncryptedFile(encName)
    fun clearViewCache() = crypto.clearViewCache()
    fun securityLevel(): String = try { crypto.securityLevelLabel() } catch (_: Exception) { "—" }
    fun biometricCipher(): Cipher? = try {
        crypto.cipherForEncrypt(crypto.getSecretKey())
    } catch (_: Exception) { null }
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
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
            ) onError(errString.toString())
        }
        override fun onAuthenticationFailed() { onError("Не удалось распознать биометрию") }
    })
    try {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в личный кабинет")
            .setSubtitle("Пароль не хранится. Данные — AES-GCM / Keystore")
            .setAllowedAuthenticators(BIO_AUTHENTICATORS)
            .setNegativeButtonText("Пароль")
            .build()
        if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        else prompt.authenticate(info)
    } catch (e: Exception) {
        onError(e.message ?: "Ошибка биометрии")
    }
}

@Composable
internal fun CabinetScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val storage = remember { CabinetStorage(context) }
    val scope = rememberCoroutineScope()

    var unlocked by remember {
        mutableStateOf(!storage.hasPassword())
    }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cases by remember { mutableStateOf(if (!storage.hasPassword()) storage.loadCases() else emptyList()) }
    var editing by remember { mutableStateOf<LocalCase?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var editAttachments by remember { mutableStateOf<List<String>>(emptyList()) }
    var lockTick by remember { mutableStateOf(0) }
    var showSecurity by remember { mutableStateOf(false) }
    var secPin by remember { mutableStateOf("") }
    var secNewPin by remember { mutableStateOf("") }
    var secConfirm by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }

    val bioStatus = remember(context) { biometricStatus(context) }
    val bioOk = bioStatus == BiometricManager.BIOMETRIC_SUCCESS
    val secLevel = remember { storage.securityLevel() }

    DisposableEffect(Unit) { onDispose { storage.clearViewCache() } }

    LaunchedEffect(lockTick) {
        while (storage.isLockedOut()) { delay(500); lockTick++ }
    }

    LaunchedEffect(Unit) {
        if (storage.hasPassword() && bioOk && activity != null && !unlocked && !storage.isLockedOut()) {
            showBiometric(activity, storage.biometricCipher(), onSuccess = {
                unlocked = true
                cases = storage.loadCases()
            }, onError = { })
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val enc = withContext(Dispatchers.IO) { storage.importAttachment(uri, uri.lastPathSegment) }
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
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Личный кабинет", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Пароль нигде не сохраняется — только криптографический verifier в EncryptedSharedPreferences. Дела и файлы: AES-256-GCM / Keystore.",
                    color = LegalGold.copy(0.95f), textAlign = TextAlign.Center, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text("Ключ: $secLevel", color = TextOnDark.copy(0.55f), fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (storage.hasPassword()) "Введите пароль или биометрию"
                    else if (storage.hasVerifier() && !storage.isPinEnabled()) "Пароль отключён. Можно войти или включить защиту снова."
                    else "Задайте пароль (минимум 6 символов)",
                    color = TextOnDark.copy(0.8f), textAlign = TextAlign.Center, fontSize = 14.sp
                )
                Spacer(Modifier.height(16.dp))

                if (!storage.hasPassword() && storage.hasVerifier() && !storage.isPinEnabled()) {
                    Button(
                        onClick = {
                            unlocked = true
                            cases = storage.loadCases()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Открыть без пароля", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { storage.enablePin(); error = "Защита включена — введите пароль" },
                        border = BorderStroke(1.dp, LegalGold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Снова требовать пароль") }
                    Spacer(Modifier.height(16.dp))
                }

                if (storage.hasPassword() || !storage.hasVerifier()) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 32) pin = it },
                        label = { Text(if (storage.hasVerifier()) "Пароль" else "Новый пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !storage.isLockedOut() && !busy,
                        colors = cabinetFieldColors(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!storage.hasVerifier()) {
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
                }

                if (storage.isLockedOut()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Блокировка: ${storage.lockRemainingMs() / 1000} с", color = Color(0xFFFF8A80), fontSize = 13.sp)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))

                if (storage.hasPassword() || !storage.hasVerifier()) {
                    Button(
                        onClick = {
                            error = null
                            if (storage.isLockedOut()) { error = "Подождите окончания блокировки"; return@Button }
                            if (pin.length < 6) { error = "Минимум 6 символов"; return@Button }
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.Default) {
                                    if (!storage.hasVerifier()) {
                                        if (pin != pinConfirm) "mismatch"
                                        else { storage.setPassword(pin); "ok" }
                                    } else if (storage.checkPassword(pin)) "ok" else "bad"
                                }
                                busy = false
                                when (result) {
                                    "ok" -> {
                                        unlocked = true
                                        cases = withContext(Dispatchers.IO) { storage.loadCases() }
                                        pin = ""; pinConfirm = ""
                                    }
                                    "mismatch" -> error = "Пароли не совпадают"
                                    else -> { error = "Неверный пароль"; lockTick++ }
                                }
                            }
                        },
                        enabled = !busy && !storage.isLockedOut(),
                        colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            if (storage.hasVerifier()) "Войти по паролю" else "Создать пароль и войти",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (storage.hasPassword()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            error = null
                            val act = activity
                            when {
                                act == null -> error = "Биометрия недоступна"
                                storage.isLockedOut() -> error = "Подождите блокировки"
                                !bioOk -> error = "Настройте биометрию в системе"
                                else -> showBiometric(act, storage.biometricCipher(), onSuccess = {
                                    unlocked = true
                                    cases = storage.loadCases()
                                }, onError = { error = it })
                            }
                        },
                        border = BorderStroke(1.dp, LegalGold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Войти по биометрии", fontWeight = FontWeight.SemiBold) }
                }
            }
        } else if (showSecurity) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Защита кабинета", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Пароль не записывается на диск. В EncryptedSharedPreferences лежит только соль и PBKDF2-verifier (600 000 итераций). Сами дела — отдельный AES-GCM ключ Keystore ($secLevel).",
                    color = TextOnDark.copy(0.8f), fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    if (storage.isPinEnabled()) "Статус: пароль ВКЛЮЧЁН" else "Статус: пароль ОТКЛЮЧЁН",
                    color = LegalGold, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = secPin,
                    onValueChange = { if (it.length <= 32) secPin = it },
                    label = { Text("Текущий пароль (для операций)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = cabinetFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                if (storage.isPinEnabled()) {
                    OutlinedButton(
                        onClick = {
                            error = null
                            if (storage.disablePin(secPin)) {
                                error = "Пароль отключён. Данные дел по-прежнему зашифрованы на диске."
                                secPin = ""
                            } else error = "Неверный пароль"
                        },
                        border = BorderStroke(1.dp, LegalGold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Отключить пароль") }
                } else if (storage.hasVerifier()) {
                    OutlinedButton(
                        onClick = {
                            storage.enablePin()
                            error = "Пароль снова требуется при входе"
                        },
                        border = BorderStroke(1.dp, LegalGold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Включить пароль") }
                }

                Spacer(Modifier.height(12.dp))
                Text("Сменить пароль", color = TextOnDark, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(secNewPin, { if (it.length <= 32) secNewPin = it }, label = { Text("Новый пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(secConfirm, { if (it.length <= 32) secConfirm = it }, label = { Text("Повтор нового") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        error = null
                        when {
                            secNewPin.length < 6 -> error = "Новый пароль: минимум 6 символов"
                            secNewPin != secConfirm -> error = "Новые пароли не совпадают"
                            storage.changePassword(secPin, secNewPin) -> {
                                error = "Пароль изменён"
                                secPin = ""; secNewPin = ""; secConfirm = ""
                            }
                            else -> error = "Неверный текущий пароль"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить новый пароль") }

                Spacer(Modifier.height(18.dp))
                Text("Удалить пароль", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                Text(
                    "Удаляет salt и verifier. Восстановить старый пароль будет невозможно (его и не было на диске).",
                    color = TextOnDark.copy(0.7f), fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        error = null
                        if (storage.deletePin(secPin, wipeData = false)) {
                            error = "Пароль удалён. Задайте новый при следующей необходимости."
                            secPin = ""
                        } else error = "Неверный пароль"
                    },
                    border = BorderStroke(1.dp, Color(0xFFFF8A80)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Удалить пароль (дела оставить)") }

                Spacer(Modifier.height(10.dp))
                if (!confirmWipe) {
                    OutlinedButton(
                        onClick = { confirmWipe = true },
                        border = BorderStroke(1.dp, Color(0xFFFF5252)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Удалить пароль И все дела") }
                } else {
                    Text("Точно удалить все дела и вложения?", color = Color(0xFFFF8A80), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmWipe = false }, modifier = Modifier.weight(1f)) { Text("Нет") }
                        Button(
                            onClick = {
                                if (storage.deletePin(secPin, wipeData = true)) {
                                    cases = emptyList()
                                    error = "Пароль и все данные кабинета удалены"
                                    secPin = ""
                                    confirmWipe = false
                                } else error = "Неверный пароль"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                            modifier = Modifier.weight(1f)
                        ) { Text("Да, удалить всё") }
                    }
                }

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = LegalGold, fontSize = 13.sp)
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { showSecurity = false; error = null; secPin = ""; secNewPin = ""; secConfirm = "" }) {
                    Text("‹ Назад к делам", color = TextOnDark)
                }
            }
        } else if (editing != null) {
            Column(Modifier.fillMaxSize()) {
                Text("Дело / материал", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text("Файлы шифруются в приватном каталоге приложения.", color = TextOnDark.copy(0.65f), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Название") }, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(editNotes, { editNotes = it }, label = { Text("Заметки") }, minLines = 5, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Вложения (${editAttachments.size}) · AES-GCM", color = LegalGold, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = !busy, border = BorderStroke(1.dp, GlassBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark)) { Text("Фото") }
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !busy, border = BorderStroke(1.dp, GlassBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark)) { Text("Файл") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(editAttachments) { encName ->
                        Row(
                            Modifier.fillMaxWidth().background(Color.White.copy(0.1f), RoundedCornerShape(10.dp)).clickable {
                                val uri = storage.openAttachment(encName, encName.removeSuffix(".enc"))
                                if (uri != null) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, null)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Открыть"))
                                    } catch (_: Exception) { error = "Не удалось открыть" }
                                } else error = "Ошибка расшифровки"
                            }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(encName.removeSuffix(".enc").take(40), color = TextOnDark, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("✕", color = Color(0xFFFF8A80), modifier = Modifier.clickable {
                                storage.deleteAttachment(encName)
                                editAttachments = editAttachments.filter { it != encName }
                            })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { editing = null }, border = BorderStroke(1.dp, Color.White.copy(0.4f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark), modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(
                        onClick = {
                            val id = editing!!.id
                            val updated = LocalCase(id, editTitle.ifBlank { "Без названия" }, editNotes, editAttachments, System.currentTimeMillis())
                            cases = if (cases.any { it.id == id }) cases.map { if (it.id == id) updated else it } else listOf(updated) + cases
                            storage.saveCases(cases)
                            editing = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                        modifier = Modifier.weight(1f)
                    ) { Text("Сохранить") }
                }
            }
        } else {
            Text("Данные зашифрованы ($secLevel). Пароль на диск не пишется.", color = LegalGold.copy(0.9f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Личный кабинет", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        if (storage.isPinEnabled()) "Защита паролем вкл." else "Защита паролем выкл.",
                        color = TextOnDark.copy(0.65f), fontSize = 12.sp
                    )
                }
                TextButton(onClick = { showSecurity = true }, colors = ButtonDefaults.textButtonColors(contentColor = LegalGold)) { Text("Код") }
                TextButton(onClick = {
                    editing = LocalCase(UUID.randomUUID().toString(), "", "", emptyList(), 0L)
                    editTitle = ""; editNotes = ""; editAttachments = emptyList()
                }, colors = ButtonDefaults.textButtonColors(contentColor = LegalGold)) { Text("+ Дело") }
                TextButton(onClick = {
                    if (storage.hasPassword()) {
                        unlocked = false
                        pin = ""
                    }
                    storage.clearViewCache()
                }, colors = ButtonDefaults.textButtonColors(contentColor = TextOnDark.copy(0.7f))) { Text("Выйти") }
            }
            Spacer(Modifier.height(12.dp))
            if (cases.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет дел.\nСоздайте дело — оно будет зашифровано.", color = TextOnDark.copy(0.7f), textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cases, key = { it.id }) { c ->
                        Box(
                            Modifier.fillMaxWidth().background(
                                Brush.verticalGradient(listOf(Color.White.copy(0.18f), Color.White.copy(0.08f))),
                                RoundedCornerShape(16.dp)
                            ).border(1.dp, GlassBorder, RoundedCornerShape(16.dp)).clickable {
                                editing = c; editTitle = c.title; editNotes = c.notes; editAttachments = c.attachments
                            }.padding(14.dp)
                        ) {
                            Column {
                                Text(c.title, color = TextOnDark, fontWeight = FontWeight.SemiBold)
                                if (c.notes.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(c.notes.take(120) + if (c.notes.length > 120) "…" else "", color = TextOnDark.copy(0.7f), fontSize = 13.sp)
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
    focusedTextColor = TextOnDark, unfocusedTextColor = TextOnDark,
    focusedBorderColor = LegalGold, unfocusedBorderColor = Color.White.copy(0.35f),
    focusedLabelColor = LegalGold, unfocusedLabelColor = TextOnDark.copy(0.6f),
    cursorColor = LegalGold, disabledTextColor = TextOnDark.copy(0.4f),
    disabledBorderColor = Color.White.copy(0.2f)
)

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("О приложении", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("ЗаконЪ · версия 0.1.0", color = LegalGold, fontWeight = FontWeight.SemiBold)
        Text("Безопасность кабинета", color = LegalGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Пароль не хранится на устройстве.\n" +
                "В EncryptedSharedPreferences (ключ MasterKey / Android Keystore) — только соль и PBKDF2-HMAC-SHA256 verifier (600 000 итераций).\n" +
                "Дела и вложения: AES-256-GCM, отдельный ключ Keystore.\n" +
                "Старый небезопасный pin_hash (hashCode) при миграции уничтожается.\n" +
                "Можно отключить, сменить или полностью удалить пароль в разделе «Код».",
            color = TextOnDark.copy(0.88f), fontSize = 14.sp, lineHeight = 20.sp
        )
        Text("Автор", color = LegalGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Исаев Владислав Андреевич", color = TextOnDark.copy(0.88f), fontSize = 14.sp)
        Text(
            "Материалы правовой базы — справочные. Сотрудничество: Valdissor@mail.ru",
            color = TextOnDark.copy(0.75f), fontSize = 13.sp, lineHeight = 18.sp
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
