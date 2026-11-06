package ru.zakon.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val TextOnDark = Color(0xFFF5F7FA)

private data class LocalCase(
    val id: String, val title: String, val notes: String,
    val attachments: List<String>, val updatedAt: Long
)

private class CabinetStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("zakon_cabinet", Context.MODE_PRIVATE)
    fun hasPassword(): Boolean = prefs.contains("pin_hash")
    fun setPassword(pin: String) { prefs.edit().putString("pin_hash", pin.hashCode().toString()).apply() }
    fun checkPassword(pin: String): Boolean = prefs.getString("pin_hash", null) == pin.hashCode().toString()
    fun loadCases(): List<LocalCase> {
        val raw = prefs.getString("cases_json", "[]") ?: "[]"
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
    fun saveCases(cases: List<LocalCase>) {
        val arr = JSONArray()
        cases.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("title", c.title); put("notes", c.notes)
                put("updatedAt", c.updatedAt); put("attachments", JSONArray(c.attachments))
            })
        }
        prefs.edit().putString("cases_json", arr.toString()).apply()
    }
    fun persistUri(uri: Uri) {
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
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

private fun showBiometric(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                onError(errString.toString())
            }
        }
        override fun onAuthenticationFailed() { onError("Не удалось распознать биометрию") }
    })
    try {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в личный кабинет")
            .setSubtitle("Данные хранятся только на этом устройстве")
            .setAllowedAuthenticators(BIO_AUTHENTICATORS)
            .setNegativeButtonText("Пароль")
            .build()
        prompt.authenticate(info)
    } catch (e: Exception) {
        onError(e.message ?: "Ошибка запуска биометрии")
    }
}

@Composable
internal fun CabinetScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val storage = remember { CabinetStorage(context) }
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cases by remember { mutableStateOf(storage.loadCases()) }
    var editing by remember { mutableStateOf<LocalCase?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var editAttachments by remember { mutableStateOf<List<String>>(emptyList()) }

    val bioStatus = remember(context) { biometricStatus(context) }
    val bioOk = bioStatus == BiometricManager.BIOMETRIC_SUCCESS

    LaunchedEffect(Unit) {
        if (storage.hasPassword() && bioOk && activity != null && !unlocked) {
            showBiometric(activity, onSuccess = { unlocked = true }, onError = { })
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { storage.persistUri(uri); editAttachments = editAttachments + uri.toString() }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { storage.persistUri(uri); editAttachments = editAttachments + uri.toString() }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        if (!unlocked) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Личный кабинет", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text("Все материалы кабинета хранятся только на вашем устройстве и доступны только вам.", color = LegalGold.copy(alpha = 0.95f), textAlign = TextAlign.Center, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(16.dp))
                Text(if (storage.hasPassword()) "Введите пароль или войдите по биометрии" else "Задайте пароль для защиты данных", color = TextOnDark.copy(0.8f), textAlign = TextAlign.Center, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(pin, { if (it.length <= 12) pin = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, colors = cabinetFieldColors(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                if (!storage.hasPassword()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(pinConfirm, { if (it.length <= 12) pinConfirm = it }, label = { Text("Повтор пароля") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, colors = cabinetFieldColors(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null
                        if (pin.length < 4) { error = "Минимум 4 символа"; return@Button }
                        if (!storage.hasPassword()) {
                            if (pin != pinConfirm) { error = "Пароли не совпадают"; return@Button }
                            storage.setPassword(pin); unlocked = true
                        } else if (storage.checkPassword(pin)) unlocked = true
                        else error = "Неверный пароль"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(if (storage.hasPassword()) "Войти по паролю" else "Создать пароль и войти", fontWeight = FontWeight.Bold) }

                if (storage.hasPassword()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            error = null
                            val act = activity
                            when {
                                act == null -> error = "Биометрия недоступна (нет Activity)"
                                !bioOk -> error = when (bioStatus) {
                                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "На устройстве нет биометрического датчика"
                                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Биометрия временно недоступна"
                                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Добавьте отпечаток или лицо в настройках телефона"
                                    else -> "Биометрия недоступна (код $bioStatus)"
                                }
                                else -> showBiometric(act, onSuccess = { unlocked = true }, onError = { error = it })
                            }
                        },
                        border = BorderStroke(1.dp, LegalGold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Войти по биометрии", fontWeight = FontWeight.SemiBold) }
                    if (!bioOk) {
                        Spacer(Modifier.height(6.dp))
                        Text("Код биометрии: $bioStatus — настройте отпечаток/лицо в системе", color = TextOnDark.copy(0.55f), fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        } else if (editing != null) {
            Column(Modifier.fillMaxSize()) {
                Text("Дело / материал", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Название") }, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(editNotes, { editNotes = it }, label = { Text("Заметки") }, minLines = 5, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Вложения (${editAttachments.size})", color = LegalGold, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { pickImage.launch("image/*") }, border = BorderStroke(1.dp, GlassBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark)) { Text("Фото") }
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, border = BorderStroke(1.dp, GlassBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark)) { Text("Файл") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(editAttachments) { uriStr ->
                        Row(Modifier.fillMaxWidth().background(Color.White.copy(0.1f), RoundedCornerShape(10.dp)).clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(uriStr), null); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                context.startActivity(Intent.createChooser(intent, "Открыть"))
                            } catch (_: Exception) {}
                        }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(uriStr.substringAfterLast('/').take(40).ifBlank { "вложение" }, color = TextOnDark, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("✕", color = Color(0xFFFF8A80), modifier = Modifier.clickable { editAttachments = editAttachments.filter { it != uriStr } })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { editing = null }, border = BorderStroke(1.dp, Color.White.copy(0.4f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark), modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(onClick = {
                        val id = editing!!.id
                        val updated = LocalCase(id, editTitle.ifBlank { "Без названия" }, editNotes, editAttachments, System.currentTimeMillis())
                        cases = if (cases.any { it.id == id }) cases.map { if (it.id == id) updated else it } else listOf(updated) + cases
                        storage.saveCases(cases); editing = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy), modifier = Modifier.weight(1f)) { Text("Сохранить") }
                }
            }
        } else {
            Text("Материалы кабинета хранятся на устройстве и доступны только вам.", color = LegalGold.copy(0.9f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Личный кабинет", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Локально на устройстве", color = TextOnDark.copy(0.65f), fontSize = 12.sp)
                }
                TextButton(onClick = {
                    editing = LocalCase(UUID.randomUUID().toString(), "", "", emptyList(), 0L)
                    editTitle = ""; editNotes = ""; editAttachments = emptyList()
                }, colors = ButtonDefaults.textButtonColors(contentColor = LegalGold)) { Text("+ Дело") }
            }
            Spacer(Modifier.height(12.dp))
            if (cases.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Пока нет дел.\nСоздайте дело, добавьте заметки, фото и файлы.", color = TextOnDark.copy(0.7f), textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cases, key = { it.id }) { c ->
                        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.White.copy(0.18f), Color.White.copy(0.08f))), RoundedCornerShape(16.dp)).border(1.dp, GlassBorder, RoundedCornerShape(16.dp)).clickable {
                            editing = c; editTitle = c.title; editNotes = c.notes; editAttachments = c.attachments
                        }.padding(14.dp)) {
                            Column {
                                Text(c.title, color = TextOnDark, fontWeight = FontWeight.SemiBold)
                                if (c.notes.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(c.notes.take(120) + if (c.notes.length > 120) "…" else "", color = TextOnDark.copy(0.7f), fontSize = 13.sp)
                                }
                                if (c.attachments.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Вложений: ${c.attachments.size}", color = LegalGold, fontSize = 12.sp)
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
    focusedLabelColor = LegalGold, unfocusedLabelColor = TextOnDark.copy(0.6f), cursorColor = LegalGold
)

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("О приложении", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("ЗаконЪ · версия 0.1.0", color = LegalGold, fontWeight = FontWeight.SemiBold)
        Text("Локальное хранение", color = LegalGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Данные кабинета хранятся только на устройстве. Биометрия обрабатывается системой Android.", color = TextOnDark.copy(0.88f), fontSize = 14.sp, lineHeight = 20.sp)
        Text("Автор", color = LegalGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Исаев Владислав Андреевич", color = TextOnDark.copy(0.88f), fontSize = 14.sp)
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
