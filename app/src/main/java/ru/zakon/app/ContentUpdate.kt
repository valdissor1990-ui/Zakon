package ru.zakon.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private val TextOnDark = Color(0xFFF5F7FA)

/** Default manifest on GitHub (raw). Change when you host your own. */
internal const val DEFAULT_CONTENT_MANIFEST_URL =
    "https://raw.githubusercontent.com/valdissor1990-ui/Zakon/main/content/updates/manifest.json"

private fun sha256Hex(bytes: ByteArray): String {
    val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
    return dig.joinToString("") { "%02x".format(it) }
}

private fun httpGetBytes(url: String): ByteArray {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        requestMethod = "GET"
        instanceFollowRedirects = true
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) error("HTTP $code")
        return conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}

@Composable
internal fun ContentUpdateScreen(store: LegalStore, padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var remoteInfo by remember { mutableStateOf<String?>(null) }

    val localVer = store.installedPackVersion()
    val localLabel = store.installedPackLabel()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
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
                status = "Импорт: v${result.version} (${result.label}). Документов: ${result.documentsUpserted}, статей: ${result.articlesUpserted}"
            } catch (e: Exception) {
                status = "Ошибка импорта: ${e.message}"
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
                    val manifestBytes = httpGetBytes(DEFAULT_CONTENT_MANIFEST_URL)
                    val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                    val remoteVer = manifest.getInt("version")
                    val label = manifest.optString("label", "")
                    val packUrl = manifest.getString("pack_url")
                    val expectedSha = manifest.optString("sha256", "").lowercase()
                    remoteInfo = "Сервер: v$remoteVer · $label"

                    if (!force && remoteVer <= store.installedPackVersion()) {
                        return@withContext "Уже актуальная база (локально v${store.installedPackVersion()})."
                    }

                    val packBytes = httpGetBytes(packUrl)
                    if (expectedSha.isNotBlank()) {
                        val actual = sha256Hex(packBytes)
                        if (actual != expectedSha) {
                            error("SHA-256 не совпал.\nОжидался: $expectedSha\nПолучен: $actual")
                        }
                    }
                    val packJson = String(packBytes, Charsets.UTF_8)
                    val imported = store.importPackJson(packJson, requireNewer = false)
                    "Обновлено: v${imported.version} (${imported.label}). " +
                        "Документов: ${imported.documentsUpserted}, статей: ${imported.articlesUpserted}"
                }
                status = resultMsg
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Обновление базы", color = TextOnDark, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(
            "Законы можно обновлять без нового APK: скачать пакет или импортировать JSON-файл.",
            color = TextOnDark.copy(0.8f),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        Text("Локально: v$localVer · $localLabel", color = LegalGold, fontWeight = FontWeight.SemiBold)
        remoteInfo?.let { Text(it, color = TextOnDark.copy(0.7f), fontSize = 13.sp) }

        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = LegalGold,
                trackColor = Color.White.copy(0.2f)
            )
        }

        Button(
            onClick = { checkAndDownload(force = false) },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Проверить и обновить", fontWeight = FontWeight.Bold) }

        OutlinedButton(
            onClick = { checkAndDownload(force = true) },
            enabled = !busy,
            border = BorderStroke(1.dp, LegalGold),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = LegalGold),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Скачать пакет заново") }

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            enabled = !busy,
            border = BorderStroke(1.dp, Color.White.copy(0.4f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextOnDark),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Импорт JSON с устройства") }

        status?.let {
            Text(it, color = TextOnDark, fontSize = 14.sp, lineHeight = 20.sp)
        }

        Text(
            "Формат пакета: JSON с полями version, documents[], articles[]. " +
                "Манифест: $DEFAULT_CONTENT_MANIFEST_URL",
            color = TextOnDark.copy(0.55f),
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    }
}
