package ru.zakon.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

internal data class LocalCase(
    val id: String,
    val title: String,
    val notes: String,
    val attachments: List<String>,
    val updatedAt: Long
)

/**
 * Cabinet content is encrypted at rest with AES-256-GCM.
 * The AES key is non-exportable and held by Android Keystore.
 * PIN verification uses PBKDF2 and is separate from the encryption key.
 */
internal class CabinetStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("zakon_cabinet", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val keyAlias = "ru.zakon.app.cabinet.aes.v1"
    private val attachmentDir = File(context.filesDir, "cabinet/attachments")
    private val temporaryDir = File(context.cacheDir, "cabinet-open")

    init { clearTemporaryFiles() }

    fun clearTemporaryFiles() {
        if (temporaryDir.exists()) temporaryDir.deleteRecursively()
    }

    fun hasPassword(): Boolean =
        prefs.contains("pin_salt") || prefs.contains("pin_hash")

    fun setPassword(pin: String) {
        val salt = ByteArray(16).also(random::nextBytes)
        val verifier = derivePin(pin, salt, 600_000)
        check(
            prefs.edit()
                .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin_verifier", Base64.encodeToString(verifier, Base64.NO_WRAP))
                .putInt("pin_iterations", 600_000)
                .remove("pin_hash")
                .commit()
        ) { "Не удалось сохранить пароль" }
    }

    fun passwordLockRemainingSeconds(): Long {
        val until = prefs.getLong("pin_lock_until", 0L)
        return ((until - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
    }

    private fun recordFailedAttempt() {
        val failures = (prefs.getInt("pin_failures", 0) + 1).coerceAtMost(20)
        val exponent = (failures - 5).coerceAtLeast(0).coerceAtMost(5)
        val delayMs = if (failures < 5) 0L else (30_000L shl exponent).coerceAtMost(15 * 60_000L)
        prefs.edit().putInt("pin_failures", failures)
            .putLong("pin_lock_until", if (delayMs == 0L) 0L else System.currentTimeMillis() + delayMs)
            .commit()
    }

    private fun clearFailedAttempts() {
        prefs.edit().remove("pin_failures").remove("pin_lock_until").commit()
    }

    fun checkPassword(pin: String): Boolean {
        if (passwordLockRemainingSeconds() > 0L) return false
        val saltText = prefs.getString("pin_salt", null)
        val verifierText = prefs.getString("pin_verifier", null)
        if (saltText != null && verifierText != null) {
            val iterations = prefs.getInt("pin_iterations", 310_000)
            val valid = try {
                val salt = Base64.decode(saltText, Base64.NO_WRAP)
                val expected = Base64.decode(verifierText, Base64.NO_WRAP)
                MessageDigest.isEqual(expected, derivePin(pin, salt, iterations))
            } catch (_: Exception) { false }
            if (!valid) { recordFailedAttempt(); return false }
            clearFailedAttempts()
            if (iterations < 600_000) setPassword(pin)
            return true
        }

        // Upgrade the old 32-bit hash only after the correct legacy PIN is entered.
        val oldHash = prefs.getString("pin_hash", null) ?: return false
        if (oldHash != pin.hashCode().toString()) { recordFailedAttempt(); return false }
        setPassword(pin)
        return true
    }

    fun loadCases(): List<LocalCase> {
        val encrypted = prefs.getString("cases_enc", null)
        val raw = if (encrypted != null) {
            decryptText(Base64.decode(encrypted, Base64.NO_WRAP))
        } else {
            // Preserve the old value until the encrypted copy has been committed successfully.
            val legacy = prefs.getString("cases_json", "[]") ?: "[]"
            encryptAndStoreCases(legacy)
            check(prefs.edit().remove("cases_json").commit()) {
                "Зашифрованная копия создана, но старые данные не удалось очистить"
            }
            legacy
        }

        val cases = parseCases(raw)
        var changed = false
        val migrated = cases.map { item ->
            val secured = item.attachments.map { reference ->
                if (reference.startsWith("secure:")) reference
                else {
                    try {
                        changed = true
                        importAttachment(Uri.parse(reference))
                    } catch (_: Exception) {
                        // Keep the original reference if Android no longer grants access.
                        changed = true
                        "unavailable:$reference"
                    }
                }
            }
            item.copy(attachments = secured)
        }
        if (changed) saveCases(migrated)
        return migrated.sortedByDescending { it.updatedAt }
    }

    fun saveCases(cases: List<LocalCase>) {
        val array = JSONArray()
        cases.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("notes", item.notes)
                put("updatedAt", item.updatedAt)
                put("attachments", JSONArray(item.attachments))
            })
        }
        encryptAndStoreCases(array.toString())
    }

    /** Imports a picked file into app-private storage as encrypted bytes. */
    fun importAttachment(source: Uri): String {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("Не удалось прочитать выбранный файл")
        val id = UUID.randomUUID().toString()
        if (!attachmentDir.exists() && !attachmentDir.mkdirs()) {
            input.close()
            throw IllegalStateException("Не удалось создать защищённую папку вложений")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        try {
            FileOutputStream(File(attachmentDir, "$id.bin")).use { fileOutput ->
                fileOutput.write(cipher.iv)
                CipherOutputStream(fileOutput, cipher).use { encryptedOutput ->
                    input.use { sourceInput -> sourceInput.copyTo(encryptedOutput) }
                }
            }
        } catch (e: Exception) {
            File(attachmentDir, "$id.bin").delete()
            throw e
        }
        val mime = context.contentResolver.getType(source) ?: "application/octet-stream"
        val name = queryDisplayName(source) ?: "вложение"
        val safeName = Base64.encodeToString(
            name.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "secure:$id:$mime:$safeName"
    }

    fun attachmentLabel(reference: String): String {
        if (reference.startsWith("unavailable:")) return "Файл недоступен — исходная ссылка сохранена"
        if (!reference.startsWith("secure:")) return Uri.parse(reference).lastPathSegment ?: "вложение"
        val parts = reference.split(":", limit = 4)
        if (parts.size != 4) return "Защищённое вложение"
        return try {
            String(
                Base64.decode(parts[3], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            "Защищённое вложение"
        }
    }

    /** Decrypts only to cache for opening; the permanent copy remains encrypted. */
    fun openAttachment(reference: String): Uri {
        if (!reference.startsWith("secure:")) {
            require(!reference.startsWith("unavailable:")) { "Старая ссылка на файл больше недоступна" }
            return Uri.parse(reference)
        }
        val parts = reference.split(":", limit = 4)
        require(parts.size == 4) { "Некорректная ссылка на вложение" }
        val id = parts[1]
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Некорректный идентификатор вложения" }
        val encryptedFile = File(attachmentDir, "$id.bin")
        require(encryptedFile.isFile) { "Зашифрованный файл не найден" }
        val originalName = attachmentLabel(reference)
        val extension = originalName.substringAfterLast('.', "")
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
            ?.let { ".$it" } ?: ".bin"
        val temporary = File(temporaryDir, "$id$extension")
        temporary.parentFile?.mkdirs()
        FileInputStream(encryptedFile).use { encryptedInput ->
            val iv = ByteArray(12)
            var offset = 0
            while (offset < iv.size) {
                val count = encryptedInput.read(iv, offset, iv.size - offset)
                if (count < 0) throw IllegalStateException("Повреждённый файл вложения")
                offset += count
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            FileOutputStream(temporary).use { clearOutput ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = encryptedInput.read(buffer)
                    if (count < 0) break
                    val clear = cipher.update(buffer, 0, count)
                    if (clear != null && clear.isNotEmpty()) clearOutput.write(clear)
                }
                val finalBytes = cipher.doFinal()
                if (finalBytes.isNotEmpty()) clearOutput.write(finalBytes)
                clearOutput.fd.sync()
            }
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            temporary
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCases(raw: String): List<LocalCase> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val attachmentArray = item.optJSONArray("attachments")
                val refs = buildList {
                    if (attachmentArray != null) {
                        for (j in 0 until attachmentArray.length()) add(attachmentArray.getString(j))
                    }
                }
                add(
                    LocalCase(
                        item.getString("id"),
                        item.getString("title"),
                        item.optString("notes", ""),
                        refs,
                        item.optLong("updatedAt", 0L)
                    )
                )
            }
        }
    }

    private fun encryptAndStoreCases(raw: String) {
        val encrypted = Base64.encodeToString(encryptBytes(raw.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("cases_enc", encrypted).commit()) {
            "Не удалось сохранить зашифрованные данные"
        }
    }

    private fun derivePin(pin: String, salt: ByteArray, iterations: Int = 600_000): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = javax.crypto.KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        fun spec(preferStrongBox: Boolean) = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(spec(true))
                return generator.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                // Not every device has StrongBox; use the Android Keystore/TEE fallback.
            }
        }
        generator.init(spec(false))
        return generator.generateKey()
    }

    private fun encryptBytes(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return cipher.iv + cipher.doFinal(clear)
    }

    private fun decryptBytes(payload: ByteArray): ByteArray {
        require(payload.size > 12) { "Повреждённые зашифрованные данные" }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun encryptText(value: String): ByteArray = encryptBytes(value.toByteArray(Charsets.UTF_8))
    private fun decryptText(value: ByteArray): String = String(decryptBytes(value), Charsets.UTF_8)

    fun keyProtectionStatus(): String {
        return try {
            val key = encryptionKey()
            val factory = javax.crypto.SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Аппаратный TEE"
                    else -> "Программный Android Keystore"
                }
            } else if (info.isInsideSecureHardware) "Аппаратный Android Keystore"
            else "Программный Android Keystore"
        } catch (_: Exception) {
            "Уровень защиты ключа не удалось определить"
        }
    }
}
