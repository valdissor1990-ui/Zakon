package ru.zakon.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Cabinet cryptography:
 * - AES-256-GCM data key in Android Keystore (non-exportable)
 * - StrongBox when available
 * - user authentication required when hardware supports it (no silent downgrade)
 * - UUID file names for attachments
 */
class CabinetCrypto(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zakon_cabinet_aes_v1"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** Max attachment size: 25 MB */
        const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Returns existing key or creates a new one.
     * Never silently drops user-authentication requirement.
     * If StrongBox fails, retries without StrongBox but keeps auth flags.
     */
    fun ensureKey(requireUserAuth: Boolean = true): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }
        return createKey(requireUserAuth = requireUserAuth, tryStrongBox = true)
    }

    private fun createKey(requireUserAuth: Boolean, tryStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            if (tryStrongBox) {
                try {
                    builder.setIsStrongBoxBacked(true)
                } catch (_: Exception) {
                    // device may not support StrongBox
                }
            }
        }

        if (requireUserAuth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationRequired(true)
            builder.setUserAuthenticationParameters(
                300, // 5 minutes validity after auth
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else if (requireUserAuth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationRequired(true)
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(300)
        }

        return try {
            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        } catch (e: Exception) {
            // Retry without StrongBox only; keep auth requirements
            if (tryStrongBox) {
                createKey(requireUserAuth = requireUserAuth, tryStrongBox = false)
            } else {
                throw e
            }
        }
    }

    fun getSecretKey(): SecretKey = ensureKey(requireUserAuth = true)

    fun securityLevelLabel(): String {
        return try {
            val key = getSecretKey()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        when (info.securityLevel) {
                            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
                            else -> "Keystore"
                        }
                    }
                    info.isInsideSecureHardware -> "SecureHardware"
                    else -> "Software"
                }
            } else "Keystore"
        } catch (_: Exception) {
            "Keystore"
        }
    }

    fun cipherForEncrypt(key: SecretKey = getSecretKey()): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    fun cipherForDecrypt(iv: ByteArray, key: SecretKey = getSecretKey()): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    /** Returns Base64(iv || ciphertext+tag). */
    fun encryptToBase64(plain: ByteArray, key: SecretKey = getSecretKey()): String {
        val cipher = cipherForEncrypt(key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return CabinetPinKdf.toBase64(out)
    }

    fun encryptString(plain: String, key: SecretKey = getSecretKey()): String =
        encryptToBase64(plain.toByteArray(Charsets.UTF_8), key)

    fun decryptFromBase64(payload: String, key: SecretKey = getSecretKey()): ByteArray {
        val all = CabinetPinKdf.fromBase64(payload)
        require(all.size > IV_BYTES) { "ciphertext too short" }
        val iv = all.copyOfRange(0, IV_BYTES)
        val ct = all.copyOfRange(IV_BYTES, all.size)
        val cipher = cipherForDecrypt(iv, key)
        return cipher.doFinal(ct)
    }

    fun decryptString(payload: String, key: SecretKey = getSecretKey()): String =
        String(decryptFromBase64(payload, key), Charsets.UTF_8)

    fun encryptedDir(): File =
        File(context.filesDir, "cabinet_enc").also { if (!it.exists()) it.mkdirs() }

    fun cacheDir(): File =
        File(context.cacheDir, "cabinet_view").also { if (!it.exists()) it.mkdirs() }

    /**
     * Encrypt bytes into app-private file; returns relative name.
     * Always uses UUID — never derived from user-provided filename (ZAK-05).
     */
    fun encryptToFile(plain: ByteArray, preferredName: String? = null): String {
        require(plain.size <= MAX_ATTACHMENT_BYTES) {
            "Файл слишком большой (макс. ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} МБ)"
        }
        val name = "att_" + UUID.randomUUID().toString().replace("-", "")
        val file = File(encryptedDir(), "$name.enc")
        val cipher = cipherForEncrypt()
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        FileOutputStream(file).use { out ->
            out.write(ByteBuffer.allocate(4).putInt(iv.size).array())
            out.write(iv)
            out.write(ct)
        }
        return file.name
    }

    fun decryptFileToCache(encFileName: String, displayName: String): File {
        clearViewCache()
        val src = File(encryptedDir(), encFileName)
        require(src.exists()) { "missing encrypted file" }
        require(src.length() <= MAX_ATTACHMENT_BYTES + 64) { "encrypted file too large" }
        val raw = FileInputStream(src).use { fis ->
            val size = src.length().toInt()
            val buf = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = fis.read(buf, off, size - off)
                if (n < 0) break
                off += n
            }
            buf
        }
        val buf = ByteBuffer.wrap(raw)
        val ivLen = buf.int
        require(ivLen in 8..32) { "bad iv" }
        val iv = ByteArray(ivLen)
        buf.get(iv)
        val ct = ByteArray(buf.remaining())
        buf.get(ct)
        val plain = cipherForDecrypt(iv).doFinal(ct)
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        val out = File(cacheDir(), "${System.currentTimeMillis()}_$safe")
        FileOutputStream(out).use { it.write(plain) }
        out.deleteOnExit()
        return out
    }

    fun deleteEncryptedFile(encFileName: String) {
        File(encryptedDir(), encFileName).delete()
    }

    fun clearViewCache() {
        val dir = cacheDir()
        dir.listFiles()?.forEach { f ->
            try {
                if (f.isFile) {
                    try {
                        java.io.RandomAccessFile(f, "rw").use { raf ->
                            val zeros = ByteArray(minOf(f.length(), 4096L).toInt())
                            raf.write(zeros)
                        }
                    } catch (_: Exception) {}
                    f.delete()
                } else {
                    f.deleteRecursively()
                }
            } catch (_: Exception) {}
        }
    }

    fun importAndEncrypt(uriStream: ByteArray, displayName: String): String =
        encryptToFile(uriStream, displayName)
}
