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

private const val CONTENT_SIGNING_PUBLIC_KEY_BASE64 =
    "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAhyJyhnYkSl1CNWShuTslaiBZW4tfGWTko+2E1hX9V3TqQ4E7jbhZ3y5RgJ6uHy/PyQlCaanCuWwfka6C3aj7HrFXPG5jqWSS2HLsfOKDclUqtyQB2yZqAW5UZA0uSJYf4QyDHI8TLif7nVwZz0ckgYmcdrv2Q1n1mDxDPTlM3LG3kfWSpLH9LkdJmaQIdx/C+ZyEs90gBpPwFghsTw3lZNbrV4j5/2aH5EUHdxZxu3XqirkMfhAvVzyEoqnDTIXiA4AxphrHL16FPH4kC3FqeyUyVvBvwqB0zG6DeKvQJQvQydRe9ZzPUbwj4X0edo6xLoqXJ4od66kWXpyZG4LD3ifvN/0IJL8AVxAvV7vVjMcAY7Id9WdcCN9K799eqswSdC/bYTBYOzfiiFZ0P2qqvq9Kg2jgRTVmTqm8BK/pP9i7/CTlsBumZ5WC3ebfSiF0h8SHMnndPJqN0k4gp0o8fCLDZ9Ma9lCjnC59JlzC2/BvbBSLAsD8PI7wDdFGQZxtAgMBAAE="

private fun verifyManifestSignature(manifestBytes: ByteArray, signatureBytes: ByteArray) {
    val encodedKey = android.util.Base64.decode(CONTENT_SIGNING_PUBLIC_KEY_BASE64, android.util.Base64.DEFAULT)
    val publicKey = java.security.KeyFactory.getInstance("RSA")
        .generatePublic(java.security.spec.X509EncodedKeySpec(encodedKey))
    val verifier = java.security.Signature.getInstance("SHA256withRSA")
    verifier.initVerify(publicKey)
    verifier.update(manifestBytes)
    require(verifier.verify(signatureBytes)) { "Цифровая подпись правовой базы недействительна" }
}

private fun sha256Hex(bytes: ByteArray): String {
    val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
    return dig.joinToString("") { "%02x".format(it) }
}

private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Загружаемый файл превышает допустимый размер" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun gunzipBounded(bytes: ByteArray, maxBytes: Int): ByteArray =
    java.util.zip.GZIPInputStream(bytes.inputStream()).use { readBounded(it, maxBytes) }

private fun httpDownloadToFile(
    url: String,
    target: java.io.File,
    maxBytes: Long,
    expectedSha256: String
) {
    var current = URL(url)
    require(current.protocol.equals("https", ignoreCase = true)) {
        "Загрузка правовой базы разрешена только по HTTPS"
    }
    require(expectedSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
        "В manifest указан некорректный SHA-256 базы"
    }
    val parent = requireNotNull(target.parentFile) { "Не удалось определить каталог загрузки" }
    check(parent.exists() || parent.mkdirs()) { "Не удалось создать каталог загрузки" }
    val temporary = java.io.File(parent, target.name + ".part")
    try {
        repeat(6) { redirectCount ->
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    require(redirectCount < 5) { "Слишком много перенаправлений при загрузке базы" }
                    val location = conn.getHeaderField("Location")
                        ?: error("Сервер вернул перенаправление без адреса")
                    val next = URL(current, location)
                    require(next.protocol.equals("https", ignoreCase = true) &&
                        next.userInfo == null
                    ) { "Запрещено перенаправление загрузки правовой базы на небезопасный адрес" }
                    current = next
                } else {
                    if (code !in 200..299) error("HTTP $code")
                    val contentLength = conn.contentLengthLong
                    require(contentLength < 0L || contentLength <= maxBytes) {
                        "Загружаемый файл превышает допустимый размер"
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    conn.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= maxBytes) { "Загружаемый файл превышает допустимый размер" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actualSha.equals(expectedSha256, ignoreCase = true)) {
                        "SHA-256 базы не совпал"
                    }
                    if (target.exists()) check(target.delete()) { "Не удалось заменить временный файл базы" }
                    check(temporary.renameTo(target)) { "Не удалось сохранить загруженный файл базы" }
                    return
                }
            } finally {
                conn.disconnect()
            }
        }
        error("Не удалось завершить перенаправление загрузки")
    } finally {
        temporary.delete()
    }
}

private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Распакованная база превышает допустимый размер" }
        output.write(buffer, 0, count)
    }
}
private fun httpGetBytes(url: String, maxBytes: Int): ByteArray {
    var current = URL(url)
    require(current.protocol.equals("https", ignoreCase = true)) {
        "Загрузка правовой базы разрешена только по HTTPS"
    }
    repeat(6) { redirectCount ->
        val conn = (current.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = false
        }
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                require(redirectCount < 5) { "Слишком много перенаправлений при загрузке базы" }
                val location = conn.getHeaderField("Location")
                    ?: error("Сервер вернул перенаправление без адреса")
                val next = URL(current, location)
                require(next.protocol.equals("https", ignoreCase = true)) {
                    "Запрещено перенаправление загрузки правовой базы на незащищённый HTTP"
                }
                current = next
            } else {
                if (code !in 200..299) error("HTTP $code")
                val contentLength = conn.contentLengthLong
                require(contentLength < 0L || contentLength <= maxBytes.toLong()) {
                    "Загружаемый файл превышает допустимый размер"
                }
                return conn.inputStream.use { readBounded(it, maxBytes) }
            }
        } finally {
            conn.disconnect()
        }
    }
    error("Не удалось завершить перенаправление загрузки")
}

@Composable
internal fun SettingsScreen(store: LegalStore, padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { CabinetStorage(context) }

    var tab by remember { mutableStateOf(0) } // 0 base, 1 security

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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = null
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                        ?: error("Не удалось прочитать файл")
                }
                val result = withContext(Dispatchers.IO) { store.importPackJson(text, requireNewer = false) }
                status = "Импорт: v${result.version}. Документов: ${result.documentsUpserted}, статей: ${result.articlesUpserted}"
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun checkAndDownload(force: Boolean) {
        busy = true
        status = null
        remoteInfo = null
        scope.launch {
            try {
                val resultMsg = withContext(Dispatchers.IO) {
                    val manifestBytes = httpGetBytes(DEFAULT_CONTENT_MANIFEST_URL, 1024 * 1024)
                    val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                    val validation = manifest.optJSONObject("validation")
                        ?: error("Серверный манифест не содержит подтверждения проверки правовой базы")
                    require(validation.optString("status") == "validated") {
                        "Новый выпуск правовой базы ещё не прошёл проверку и не опубликован. Локальные данные сохранены."
                    }
                    val database = manifest.optJSONObject("database")
                        ?: error("Удалённые JSON-пакеты больше не принимаются. Требуется подписанный SQLite-выпуск.")
                    val signature = manifest.optJSONObject("signature")
                        ?: error("В манифесте отсутствует цифровая подпись издателя")
                    require(signature.optString("algorithm") == "SHA256withRSA") {
                        "Неподдерживаемый алгоритм подписи правовой базы"
                    }
                    val signatureUrl = signature.optString("download_url", "")
                    require(signatureUrl.startsWith("https://")) { "Адрес подписи должен использовать HTTPS" }
                    verifyManifestSignature(manifestBytes, httpGetBytes(signatureUrl, 16 * 1024))
                    val version = manifest.optInt(
                        "version",
                        manifest.optInt(
                            "version_code",
                            manifest.optString("content_version").toIntOrNull() ?: 0
                        )
                    )
                    require(version > 0) { "В manifest отсутствует корректная версия контента" }
                    val label = manifest.optString(
                        "content_version",
                        manifest.optString("label", manifest.optString("effective_as_of", ""))
                    )
                    val databaseUrl = database?.optString("download_url")
                        ?.takeIf { it.isNotBlank() }
                        ?: database?.optString("url")?.takeIf { it.isNotBlank() }
                    if (databaseUrl != null) require(databaseUrl.startsWith("https://")) {
                        "Адрес правовой базы должен использовать HTTPS"
                    }
                    val expectedSha = (
                        database?.optString("sha256")?.takeIf { it.isNotBlank() }
                            ?: manifest.optString("sha256", "")
                        ).lowercase()
                    remoteInfo = "Сервер: v$version · $label"
                    val installedVersion = store.installedPackVersion()
                    require(version >= installedVersion) {
                        "Сервер предлагает более старую базу v$version; установлена v$installedVersion. Понижение версии запрещено."
                    }
                    if (!force && version == installedVersion) {
                        return@withContext "Уже актуальная база (v$installedVersion)."
                    }

                    val delta = manifest.optJSONObject("delta")
                    if (!force && delta != null && store.installedPackVersion() > 0 &&
                        delta.optInt("from_version_code", -1) == store.installedPackVersion()
                    ) {
                        val deltaUrl = delta.optString("download_url", "")
                        val deltaSha = delta.optString("sha256", "").lowercase()
                        if (deltaUrl.startsWith("https://") && deltaSha.isNotBlank() &&
                            delta.optInt("to_version_code", -1) == version
                        ) {
                            try {
                                val deltaBytes = httpGetBytes(deltaUrl, 64 * 1024 * 1024)
                                require(sha256Hex(deltaBytes) == deltaSha) { "SHA-256 обновления-дельты не совпал" }
                                val patchBytes = if (deltaBytes.size >= 2 &&
                                    deltaBytes[0] == 0x1f.toByte() && deltaBytes[1] == 0x8b.toByte()
                                ) {
                                    gunzipBounded(deltaBytes, 128 * 1024 * 1024)
                                } else deltaBytes
                                store.applyDatabaseDelta(String(patchBytes, Charsets.UTF_8), label, version)
                                return@withContext "Правовая база обновлена небольшим пакетом: v$version."
                            } catch (_: Exception) {
                                // A failed delta is rolled back; retry with the verified full DB below.
                                remoteInfo = "Дельта не подошла; загружается полная база."
                            }
                        }
                    }

                    if (!databaseUrl.isNullOrBlank()) {
                        require(expectedSha.isNotBlank()) { "В manifest отсутствует SHA-256 базы" }
                        val downloadedFile = java.io.File(context.cacheDir, "zakon-content-download.bin")
                        val sqliteFile = java.io.File(context.cacheDir, "zakon-content-install.sqlite")
                        try {
                            httpDownloadToFile(
                                databaseUrl,
                                downloadedFile,
                                256L * 1024 * 1024,
                                expectedSha
                            )
                            java.io.BufferedInputStream(downloadedFile.inputStream()).use { buffered ->
                                buffered.mark(2)
                                val isGzip = buffered.read() == 0x1f && buffered.read() == 0x8b
                                buffered.reset()
                                val input = if (isGzip) java.util.zip.GZIPInputStream(buffered) else buffered
                                input.use {
                                    sqliteFile.outputStream().buffered().use { output ->
                                        copyBounded(it, output, 512L * 1024 * 1024)
                                    }
                                }
                            }
                            store.installDatabaseFile(sqliteFile, version, label)
                            "Правовая база установлена: v$version."
                        } finally {
                            downloadedFile.delete()
                            sqliteFile.delete()
                        }
                    } else {
                        error("В подписанном manifest не указан адрес SQLite-базы")
                    }
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

/** Kept for callers that still reference the old name. */
@Composable
internal fun ContentUpdateScreen(store: LegalStore, padding: PaddingValues) {
    SettingsScreen(store, padding)
}
