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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.UUID

private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val TextOnDark = Color(0xFFF5F7FA)

private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun biometricAuthenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else BiometricManager.Authenticators.BIOMETRIC_STRONG

private fun biometricStatus(context: Context): Int =
    BiometricManager.from(context).canAuthenticate(biometricAuthenticators())

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
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Подтвердите личность")
            .setSubtitle("Разрешение на доступ к ключу шифрования личного кабинета")
            .setAllowedAuthenticators(biometricAuthenticators())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) builder.setNegativeButtonText("Отмена")
        val info = builder.build()
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, storage) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storage.clearTemporaryFiles()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var storageFailure by remember { mutableStateOf<String?>(null) }
    var cases by remember { mutableStateOf<List<LocalCase>>(emptyList()) }
    var casesLoaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LocalCase?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var editAttachments by remember { mutableStateOf<List<String>>(emptyList()) }

    val bioStatus = remember(context) { biometricStatus(context) }
    val bioOk = bioStatus == BiometricManager.BIOMETRIC_SUCCESS
    var keyProtection by remember { mutableStateOf("") }

    val runProtected: (() -> Unit) -> Unit = { action ->
        if (storage.requiresSystemAuthentication()) {
            val act = activity
            if (act == null) error = "Не удалось открыть системную проверку устройства"
            else showBiometric(act, onSuccess = action, onError = { error = it })
        } else action()
    }
    val completeUnlock: () -> Unit = { runProtected { unlocked = true } }

    LaunchedEffect(casesLoaded) {
        if (casesLoaded) keyProtection = storage.keyProtectionStatus()
    }

    LaunchedEffect(Unit) {
        if (storage.hasPassword() && bioOk && activity != null && !unlocked) {
            showBiometric(activity, onSuccess = { unlocked = true }, onError = { })
        }
    }
    LaunchedEffect(unlocked) {
        if (unlocked && !casesLoaded) {
            try {
                cases = storage.loadCases()
                casesLoaded = true
                storageFailure = null
            } catch (e: Exception) {
                storageFailure = e.message ?: "Не удалось расшифровать данные кабинета"
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runProtected {
            try { editAttachments = editAttachments + storage.importAttachment(uri); error = null }
            catch (e: Exception) { error = e.message ?: "Не удалось защитить файл" }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runProtected {
            try { editAttachments = editAttachments + storage.importAttachment(uri); error = null }
            catch (e: Exception) { error = e.message ?: "Не удалось защитить фотографию" }
        }
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
                OutlinedTextField(pin, { if (it.length <= 64) pin = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, colors = cabinetFieldColors(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                if (!storage.hasPassword()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(pinConfirm, { if (it.length <= 64) pinConfirm = it }, label = { Text("Повтор пароля") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, colors = cabinetFieldColors(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null
                        if (pin.length < 6) { error = "Минимум 6 символов"; return@Button }
                        if (!storage.hasPassword()) {
                            if (pin != pinConfirm) { error = "Пароли не совпадают"; return@Button }
                            storage.setPassword(pin); completeUnlock()
                        } else if (storage.checkPassword(pin)) completeUnlock()
                        else {
                            val wait = storage.passwordLockRemainingSeconds()
                            error = if (wait > 0) "Слишком много попыток. Повторите через $wait сек." else "Неверный пароль"
                        }
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
        } else if (!casesLoaded && storageFailure == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LegalGold) }
        } else if (storageFailure != null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Не удалось открыть защищённые данные", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(storageFailure ?: "Ошибка расшифровки", color = TextOnDark, textAlign = TextAlign.Center, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text("Данные не удалены. Не очищайте данные приложения и не переустанавливайте его. Закройте и снова откройте приложение.", color = LegalGold, textAlign = TextAlign.Center, fontSize = 13.sp)
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
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { pickImage.launch(arrayOf("image/*")) }, border = BorderStroke(1.dp, GlassBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark)) { Text("Фото") }
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, border = BorderStroke(1.dp, GlassBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark)) { Text("Файл") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(editAttachments) { uriStr ->
                        Row(Modifier.fillMaxWidth().background(Color.White.copy(0.1f), RoundedCornerShape(10.dp)).clickable {
                            try {
                                runProtected {
                                    try {
                                        val openedUri = storage.openAttachment(uriStr)
                                        val mime = if (uriStr.startsWith("secure:") || uriStr.startsWith("secure2:")) uriStr.split(":", limit = 4).getOrNull(2) else context.contentResolver.getType(openedUri)
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(openedUri, mime ?: "*/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Открыть"))
                                    } catch (e: Exception) { error = e.message ?: "Не удалось открыть вложение" }
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "Не удалось открыть вложение"
                            }
                        }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(storage.attachmentLabel(uriStr), color = TextOnDark, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
                        val nextCases = if (cases.any { it.id == id }) cases.map { if (it.id == id) updated else it } else listOf(updated) + cases
                        runProtected {
                            try {
                                storage.saveCases(nextCases)
                                cases = nextCases
                                editing = null
                                error = null
                            } catch (e: Exception) { error = e.message ?: "Не удалось сохранить защищённые данные" }
                        }
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
