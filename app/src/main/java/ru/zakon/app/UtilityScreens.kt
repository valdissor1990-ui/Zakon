package ru.zakon.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private val OnWhite = Color(0xFF1A1A1A)
private val MutedGray = Color(0xFF6B6B6B)
private val AccentGold = Color(0xFF8A6A1A)

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
    fun hasVerifier(): Boolean = securePrefs.contains("pin_verifier") && securePrefs.contains("pin_salt")
    fun isPinEnabled(): Boolean = securePrefs.getBoolean("pin_enabled", hasVerifier())
    fun hasPassword(): Boolean = isPinEnabled() && hasVerifier()
    fun isPinPromptDone(): Boolean = securePrefs.getBoolean("pin_prompt_done", false)
    fun markPinPromptDone() { securePrefs.edit().putBoolean("pin_prompt_done", true).apply() }
    fun setPassword(pin: String) {
        require(pin.length >= 6)
        val salt = CabinetPinKdf.newSalt()
        val verifier = CabinetPinKdf.derive(pin, salt)
        securePrefs.edit()
            .putString("pin_salt", CabinetPinKdf.toBase64(salt))
            .putString("pin_verifier", CabinetPinKdf.toBase64(verifier))
            .putBoolean("pin_enabled", true).putBoolean("pin_prompt_done", true)
            .putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        salt.fill(0); verifier.fill(0)
        try { crypto.ensureKey(requireUserAuth = true) } catch (_: Exception) { }
    }
    fun checkPassword(pin: String): Boolean {
        if (isLockedOut() || !hasVerifier()) return false
        return try {
            val salt = CabinetPinKdf.fromBase64(securePrefs.getString("pin_salt", null) ?: return false)
            val expected = CabinetPinKdf.fromBase64(securePrefs.getString("pin_verifier", null) ?: return false)
            val ok = try { CabinetPinKdf.matches(pin, salt, expected) } finally { salt.fill(0); expected.fill(0) }
            if (ok) securePrefs.edit().putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
            ok
        } catch (_: Exception) { false }
    }
    fun deletePin(currentPin: String, wipeData: Boolean): Boolean {
        return try {
            if (hasVerifier() && (currentPin.isBlank() || !checkPassword(currentPin))) return false
            securePrefs.edit().remove("pin_salt").remove("pin_verifier").putBoolean("pin_enabled", false).commit()
            if (wipeData) wipeAllCabinetData()
            true
        } catch (_: Exception) { false }
    }
    fun changePassword(oldPin: String, newPin: String): Boolean {
        if (!checkPassword(oldPin) || newPin.length < 6) return false
        setPassword(newPin); return true
    }
    fun disablePin(currentPin: String): Boolean {
        if (!checkPassword(currentPin)) return false
        securePrefs.edit().putBoolean("pin_enabled", false).commit(); return true
    }
    fun enablePin() { if (hasVerifier()) securePrefs.edit().putBoolean("pin_enabled", true).apply() }
    fun isLockedOut(): Boolean = System.currentTimeMillis() < securePrefs.getLong("pin_lock_until", 0L)
    fun lockRemainingMs(): Long = (securePrefs.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    fun wipeAllCabinetData() {
        try { securePrefs.edit().remove("cases_enc").remove("workspace_v2_enc").commit() } catch (_: Exception) { }
        try { crypto.clearViewCache() } catch (_: Exception) { }
    }
    fun clearViewCache() = try { crypto.clearViewCache() } catch (_: Exception) { }
}

@Composable
internal fun CabinetScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val storage = remember { CabinetStorage(context) }
    var unlocked by remember { mutableStateOf(!storage.hasPassword()) }
    var showAsk by remember { mutableStateOf(!storage.hasVerifier() && !storage.isPinPromptDone()) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().padding(padding).background(Color.White)) {
        when {
            showAsk -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Личный кабинет", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                Text("Задать пароль?", color = MutedGray)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showAsk = false; unlocked = false }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold)) { Text("Да") }
                OutlinedButton(onClick = { storage.markPinPromptDone(); showAsk = false; unlocked = true }) { Text("Нет") }
            }
            !unlocked -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Вход", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                OutlinedTextField(pin, { pin = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = Color.Red) }
                Button(onClick = {
                    if (storage.hasPassword()) {
                        if (storage.checkPassword(pin)) unlocked = true else error = "Неверный пароль"
                    } else {
                        if (pin.length >= 6) { storage.setPassword(pin); unlocked = true } else error = "Минимум 6 символов"
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold), modifier = Modifier.fillMaxWidth()) { Text("Войти") }
            }
            else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Личный кабинет Professional", color = OnWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Обновление интерфейса: клиенты, дела, хронология — в следующей сборке.", color = MutedGray, fontSize = 14.sp)
                Text("Данные хранятся только на устройстве.", color = MutedGray, fontSize = 13.sp)
                if (storage.hasPassword()) {
                    TextButton(onClick = { unlocked = false; pin = "" }) { Text("Выйти", color = MutedGray) }
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
