package ru.zakon.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private val OnSurface = Color(0xFF1A1A1A)
private val Muted = Color(0xFF5C5C5C)
private val Accent = Color(0xFF8A6A1A)

internal const val DEFAULT_CONTENT_MANIFEST_URL =
    "https://raw.githubusercontent.com/valdissor1990-ui/Zakon/main/content/updates/manifest.json"

private fun sha256Hex(bytes: ByteArray): String {
    val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
    return dig.joinToString("") { "%02x".format(it) }
}

private const val MAX_PACK_BYTES = 40 * 1024 * 1024 // 40 MB
private const val MAX_LOCAL_IMPORT_BYTES = 40 * 1024 * 1024 // 40 MB

internal fun readLimitedText(input: java.io.InputStream, maxBytes: Int): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Файл превышает допустимый размер 40 МБ" }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun httpGetBytes(url: String, maxBytes: Int = MAX_PACK_BYTES): ByteArray {
    require(url.startsWith("https://")) { "Только HTTPS" }
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        requestMethod = "GET"
        instanceFollowRedirects = false
    }
    try {
        val code = conn.responseCode
        if (code in 300..399) {
            val loc = conn.getHeaderField("Location") ?: error("HTTP $code без Location")
            require(loc.startsWith("https://")) { "Редирект только на HTTPS" }
            return httpGetBytes(loc, maxBytes)
        }
        if (code !in 200..299) error("HTTP $code")
        val len = conn.contentLengthLong
        if (len > maxBytes) error("Пакет слишком большой")
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        conn.inputStream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) error("Пакет слишком большой")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    } finally {
        conn.disconnect()
    }
}

@Composable
internal fun SettingsScreen(store: LegalStore, padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { CabinetStorage(context) }

    var tab by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("Настройки", color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = Accent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("База законов") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Безопасность") })
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> ContentUpdateSection(store)
            1 -> SecuritySettingsSection(storage)
        }
    }
}

@Composable
private fun ContentUpdateSection(store: LegalStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var remoteInfo by remember { mutableStateOf<String?>(null) }
    val localVer = store.installedPackVersion()
    val localLabel = store.installedPackLabel()

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pendingImportUri = uri
    }

    fun checkAndDownload(force: Boolean) {
        busy = true
        status = null
        remoteInfo = null
        scope.launch {
            try {
                val resultMsg = withContext(Dispatchers.IO) {
                    val manifestBytes = httpGetBytes(DEFAULT_CONTENT_MANIFEST_URL)
                    val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                    val remoteVer = manifest.getInt("version")
                    val label = manifest.optString("label", "")
                    val packUrl = manifest.getString("pack_url")
                    val expectedSha = manifest.optString("sha256", "").lowercase()
                    require(expectedSha.matches(Regex("[0-9a-f]{64}"))) { "Манифест без корректного SHA-256" }
                    require(packUrl.startsWith("https://")) { "pack_url должен быть HTTPS" }
                    remoteInfo = "Сервер: v$remoteVer · $label"
                    if (!force && remoteVer <= store.installedPackVersion()) {
                        return@withContext "Уже актуальная база (v${store.installedPackVersion()})."
                    }
                    val packBytes = httpGetBytes(packUrl)
                    val actual = sha256Hex(packBytes)
                    if (actual != expectedSha) error("SHA-256 не совпал")
                    val imported = store.importPackJson(String(packBytes, Charsets.UTF_8), requireNewer = false)
                    "Обновлено: v${imported.version}. Статей: ${imported.articlesUpserted}"
                }
                status = resultMsg
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Локально: v$localVer · $localLabel", color = Accent, fontWeight = FontWeight.SemiBold)
        remoteInfo?.let { Text(it, color = Muted, fontSize = 13.sp) }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
        Button(
            onClick = { checkAndDownload(false) },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Проверить и обновить") }
        OutlinedButton(
            onClick = { checkAndDownload(true) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Скачать пакет заново") }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Импорт JSON с устройства") }
        status?.let { Text(it, color = OnSurface, fontSize = 14.sp) }
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("Импорт непроверенного файла") },
            text = {
                Text(
                    "Файл JSON не имеет подтверждённой цифровой подписи. Его содержимое может быть устаревшим или изменённым. " +
                        "Импорт заменит или дополнит записи локальной правовой базы. Продолжайте только если доверяете источнику файла."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    busy = true
                    status = null
                    scope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use {
                                    readLimitedText(it, MAX_LOCAL_IMPORT_BYTES)
                                } ?: error("Не удалось прочитать файл")
                            }
                            val result = withContext(Dispatchers.IO) {
                                store.importPackJson(
                                    text,
                                    requireNewer = false,
                                    installedLabelOverride = "Локальный импорт (не проверен)"
                                )
                            }
                            status = "Импортирован непроверенный пакет: v${result.version}. " +
                                "Документов: ${result.documentsUpserted}, статей: ${result.articlesUpserted}"
                        } catch (e: Exception) {
                            status = "Ошибка: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun SecuritySettingsSection(storage: CabinetStorage) {
    val context = LocalContext.current
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }

    val bioStatus = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
    }
    val bioOk = bioStatus == BiometricManager.BIOMETRIC_SUCCESS

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when {
                storage.hasPassword() -> "Пароль кабинета: включён"
                storage.hasVerifier() && !storage.isPinEnabled() -> "Пароль: отключён (можно включить)"
                else -> "Пароль кабинета: не задан"
            },
            color = OnSurface,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (bioOk) "Сильная биометрия: доступна"
            else "Сильная биометрия: недоступна (код $bioStatus) — используйте пароль",
            color = Muted,
            fontSize = 13.sp
        )
        Text(
            "Пароль нигде не хранится — только криптографический verifier. Кабинет блокируется при сворачивании приложения.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        OutlinedTextField(
            value = currentPin,
            onValueChange = { if (it.length <= 32) currentPin = it },
            label = { Text(if (storage.hasVerifier()) "Текущий пароль" else "Новый пароль") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!storage.hasVerifier()) {
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 32) confirmPin = it },
                label = { Text("Повтор пароля") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    message = null
                    when {
                        currentPin.length < 8 -> message = "Минимум 8 символов"
                        currentPin != confirmPin -> message = "Пароли не совпадают"
                        else -> {
                            storage.setPassword(currentPin)
                            storage.markPinPromptDone()
                            message = "Пароль установлен"
                            currentPin = ""; confirmPin = ""
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Задать пароль") }
        } else {
            if (storage.isPinEnabled()) {
                OutlinedButton(
                    onClick = {
                        message = null
                        if (storage.disablePin(currentPin)) {
                            message = "Пароль отключён. Кабинет откроется без запроса."
                            currentPin = ""
                        } else message = "Неверный пароль"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Отключить пароль") }
            } else {
                OutlinedButton(
                    onClick = {
                        storage.enablePin()
                        message = "Пароль снова требуется при входе в кабинет"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Включить пароль") }
            }

            OutlinedTextField(
                value = newPin,
                onValueChange = { if (it.length <= 32) newPin = it },
                label = { Text("Новый пароль") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 32) confirmPin = it },
                label = { Text("Повтор нового") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    message = null
                    when {
                        newPin.length < 8 -> message = "Минимум 8 символов"
                        newPin != confirmPin -> message = "Пароли не совпадают"
                        storage.changePassword(currentPin, newPin) -> {
                            message = "Пароль изменён"
                            currentPin = ""; newPin = ""; confirmPin = ""
                        }
                        else -> message = "Неверный текущий пароль"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сменить пароль") }

            OutlinedButton(
                onClick = {
                    message = null
                    if (storage.deletePin(currentPin, wipeData = false)) {
                        message = "Пароль удалён"
                        currentPin = ""
                    } else message = "Неверный пароль"
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB71C1C)),
                border = BorderStroke(1.dp, Color(0xFFB71C1C)),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Удалить пароль") }

            if (!confirmWipe) {
                TextButton(onClick = { confirmWipe = true }) {
                    Text("Удалить пароль и все дела…", color = Color(0xFFB71C1C))
                }
            } else {
                Text("Удалить все дела кабинета?", color = Color(0xFFB71C1C), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmWipe = false }, modifier = Modifier.weight(1f)) { Text("Нет") }
                    Button(
                        onClick = {
                            if (storage.deletePin(currentPin, wipeData = true)) {
                                message = "Пароль и дела удалены"
                                currentPin = ""
                                confirmWipe = false
                            } else message = "Неверный пароль"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                        modifier = Modifier.weight(1f)
                    ) { Text("Да") }
                }
            }
        }

        message?.let { Text(it, color = Accent, fontSize = 14.sp) }
    }
}

@Composable
internal fun ContentUpdateScreen(store: LegalStore, padding: PaddingValues) {
    SettingsScreen(store, padding)
}
