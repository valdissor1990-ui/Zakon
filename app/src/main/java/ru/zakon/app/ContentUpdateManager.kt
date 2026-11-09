package ru.zakon.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPInputStream

private const val CONTENT_MANIFEST_URL =
    "https://raw.githubusercontent.com/valdissor1990-ui/Zakon/main/content/updates/manifest.json"

private const val CONTENT_PUBLIC_KEY_BASE64 =
    "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAhyJyhnYkSl1CNWShuTslaiBZW4tfGWTko+2E1hX9V3TqQ4E7jbhZ3y5RgJ6uHy/PyQlCaanCuWwfka6C3aj7HrFXPG5jqWSS2HLsfOKDclUqtyQB2yZqAW5UZA0uSJYf4QyDHI8TLif7nVwZz0ckgYmcdrv2Q1n1mDxDPTlM3LG3kfWSpLH9LkdJmaQIdx/C+ZyEs90gBpPwFghsTw3lZNbrV4j5/2aH5EUHdxZxu3XqirkMfhAvVzyEoqnDTIXiA4AxphrHL16FPH4kC3FqeyUyVvBvwqB0zG6DeKvQJQvQydRe9ZzPUbwj4X0edo6xLoqXJ4od66kWXpyZG4LD3ifvN/0IJL8AVxAvV7vVjMcAY7Id9WdcCN9K799eqswSdC/bYTBYOzfiiFZ0P2qqvq9Kg2jgRTVmTqm8BK/pP9i7/CTlsBumZ5WC3ebfSiF0h8SHMnndPJqN0k4gp0o8fCLDZ9Ma9lCjnC59JlzC2/BvbBSLAsD8PI7wDdFGQZxtAgMBAAE="

/** Checks and installs a separately published legal-content release. No user data is uploaded. */
internal suspend fun updateContentIfAvailable(context: Context, store: LegalStore): String? =
    withContext(Dispatchers.IO) {
        val manifestBytes = downloadHttps(CONTENT_MANIFEST_URL)
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val validation = manifest.optJSONObject("validation")
        if (validation?.optString("status") != "validated") return@withContext null

        val database = manifest.optJSONObject("database")
            ?: error("В манифесте отсутствует описание SQLite-базы")
        val signature = manifest.optJSONObject("signature")
            ?: error("В манифесте отсутствует цифровая подпись издателя")
        require(signature.optString("algorithm") == "SHA256withRSA") {
            "Неподдерживаемый алгоритм подписи правовой базы"
        }
        val signatureUrl = signature.optString("download_url", "")
        require(signatureUrl.startsWith("https://")) { "Адрес подписи должен использовать HTTPS" }
        verifySignature(manifestBytes, downloadHttps(signatureUrl))

        val version = manifest.optInt("version_code", 0)
        require(version > 0) { "В манифесте отсутствует корректная версия контента" }
        if (version <= store.installedPackVersion()) return@withContext null

        val label = manifest.optString("content_version", manifest.optString("effective_as_of", ""))
        val delta = manifest.optJSONObject("delta")
        if (delta != null && store.installedPackVersion() > 0 &&
            delta.optInt("from_version_code", -1) == store.installedPackVersion() &&
            delta.optInt("to_version_code", -1) == version
        ) {
            try {
                val deltaUrl = delta.optString("download_url", "")
                val expectedDeltaHash = delta.optString("sha256", "").lowercase()
                require(deltaUrl.startsWith("https://") && expectedDeltaHash.isNotBlank()) {
                    "Неверные параметры обновления-дельты"
                }
                val bytes = downloadHttps(deltaUrl)
                require(sha256(bytes) == expectedDeltaHash) { "SHA-256 обновления-дельты не совпал" }
                val patch = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                } else bytes
                store.applyDatabaseDelta(String(patch, Charsets.UTF_8), label, version)
                return@withContext "Правовая база обновлена: v$version"
            } catch (_: Exception) {
                // An invalid or incompatible delta falls back to the signed full database.
            }
        }

        val databaseUrl = database.optString("download_url", "")
        require(databaseUrl.startsWith("https://")) { "Адрес правовой базы должен использовать HTTPS" }
        val expectedHash = database.optString("sha256", "").lowercase()
        require(expectedHash.isNotBlank()) { "В манифесте отсутствует SHA-256 базы" }
        val bytes = downloadHttps(databaseUrl)
        require(sha256(bytes) == expectedHash) { "SHA-256 базы не совпал" }

        val rawFile = java.io.File(context.cacheDir, "zakon-content-background.tmp")
        val sqliteFile = java.io.File(context.cacheDir, "zakon-content-background.sqlite")
        try {
            rawFile.writeBytes(bytes)
            val compressed = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            if (compressed) {
                GZIPInputStream(rawFile.inputStream()).use { input ->
                    sqliteFile.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                bytes.inputStream().use { input ->
                    sqliteFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            store.installDatabaseFile(sqliteFile, version, label)
            "Правовая база установлена: v$version"
        } finally {
            rawFile.delete()
            sqliteFile.delete()
        }
    }

private fun verifySignature(manifest: ByteArray, signatureBytes: ByteArray) {
    val encoded = android.util.Base64.decode(CONTENT_PUBLIC_KEY_BASE64, android.util.Base64.DEFAULT)
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
    val verifier = Signature.getInstance("SHA256withRSA")
    verifier.initVerify(key)
    verifier.update(manifest)
    require(verifier.verify(signatureBytes)) { "Цифровая подпись правовой базы недействительна" }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun downloadHttps(address: String): ByteArray {
    var current = URL(address)
    require(current.protocol.equals("https", ignoreCase = true)) { "Разрешена загрузка только по HTTPS" }
    repeat(6) { redirects ->
        val connection = (current.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = false
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                require(redirects < 5) { "Слишком много перенаправлений" }
                val location = connection.getHeaderField("Location") ?: error("Пустой адрес перенаправления")
                val next = URL(current, location)
                require(next.protocol.equals("https", ignoreCase = true)) {
                    "Запрещено перенаправление на незащищённый HTTP"
                }
                current = next
            } else {
                if (code !in 200..299) error("HTTP $code")
                return connection.inputStream.use { it.readBytes() }
            }
        } finally {
            connection.disconnect()
        }
    }
    error("Не удалось завершить перенаправление")
}
