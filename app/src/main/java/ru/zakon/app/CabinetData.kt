package ru.zakon.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class CabClient(val id: String, val type: String = "person", val name: String, val phone: String = "", val email: String = "", val notes: String = "", val updatedAt: Long = System.currentTimeMillis())
internal data class CabCase(val id: String, val title: String, val category: String = "civil", val status: String = "active", val clientId: String? = null, val court: String = "", val caseNumber: String = "", val subject: String = "", val notes: String = "", val updatedAt: Long = System.currentTimeMillis())
internal data class CabEvent(val id: String, val caseId: String, val type: String = "note", val title: String, val body: String = "", val at: Long = System.currentTimeMillis())
internal data class CabDoc(val id: String, val caseId: String, val title: String, val encName: String, val createdAt: Long = System.currentTimeMillis())
internal data class CabTask(val id: String, val caseId: String, val title: String, val status: String = "new", val updatedAt: Long = System.currentTimeMillis())
internal data class CabinetWorkspace(val clients: List<CabClient> = emptyList(), val cases: List<CabCase> = emptyList(), val events: List<CabEvent> = emptyList(), val docs: List<CabDoc> = emptyList(), val tasks: List<CabTask> = emptyList())

/** Result of loading workspace — distinguishes empty vs decrypt failure (ZAK-04). */
internal sealed class WorkspaceLoad {
    data class Ok(val ws: CabinetWorkspace) : WorkspaceLoad()
    data class DecryptError(val message: String) : WorkspaceLoad()
    data object Empty : WorkspaceLoad()
}

internal val CaseCategories = listOf("civil" to "Гражданское", "admin" to "Административное", "criminal" to "Уголовное", "arbitral" to "Арбитражное", "family" to "Семейное", "labor" to "Трудовое", "other" to "Иное")
internal val CaseStatuses = listOf("active" to "В работе", "waiting" to "Ожидание", "court" to "В суде", "done" to "Завершено", "archive" to "Архив")
internal fun lab(map: List<Pair<String, String>>, k: String) = map.find { it.first == k }?.second ?: k
internal fun fmt(ms: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(ms))

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
    private val legacyPrefs = context.getSharedPreferences("zakon_cabinet", Context.MODE_PRIVATE)

    init {
        migrateLegacyIfNeeded()
    }

    private fun migrateLegacyIfNeeded() {
        val lv = legacyPrefs.getString("pin_verifier", null)
        val ls = legacyPrefs.getString("pin_salt", null)
        val hasLegacyPin = lv != null && ls != null
        val hasLegacyCases = legacyPrefs.getString("cases_enc", null) != null
        if (!hasLegacyPin && !hasLegacyCases) {
            if (legacyPrefs.all.isNotEmpty()) legacyPrefs.edit().clear().commit()
            return
        }
        val edit = securePrefs.edit()
        if (hasLegacyPin && !securePrefs.contains("pin_verifier")) {
            edit.putString("pin_salt", ls).putString("pin_verifier", lv).putBoolean("pin_enabled", true)
        }
        legacyPrefs.getString("cases_enc", null)?.let {
            if (!securePrefs.contains("cases_enc") && !securePrefs.contains("workspace_v2_enc")) {
                edit.putString("cases_enc", it)
            }
        }
        if (!edit.commit()) return
        legacyPrefs.edit().clear().commit()
    }

    fun hasVerifier() = securePrefs.contains("pin_verifier") && securePrefs.contains("pin_salt")
    fun isPinEnabled() = securePrefs.getBoolean("pin_enabled", hasVerifier())
    fun hasPassword() = isPinEnabled() && hasVerifier()
    fun isPinPromptDone() = securePrefs.getBoolean("pin_prompt_done", false)
    fun markPinPromptDone() { securePrefs.edit().putBoolean("pin_prompt_done", true).apply() }

    fun setPassword(pin: String) {
        require(pin.length >= 8) { "Пароль должен быть не короче 8 символов" }
        val salt = CabinetPinKdf.newSalt()
        val verifier = CabinetPinKdf.derive(pin, salt)
        securePrefs.edit()
            .putString("pin_salt", CabinetPinKdf.toBase64(salt))
            .putString("pin_verifier", CabinetPinKdf.toBase64(verifier))
            .putBoolean("pin_enabled", true)
            .putBoolean("pin_prompt_done", true)
            .putInt("pin_fail_count", 0)
            .putLong("pin_lock_until_elapsed", 0L)
            .commit()
        salt.fill(0); verifier.fill(0)
        try { crypto.ensureKey(requireUserAuth = true) } catch (_: Exception) {}
    }

    fun checkPassword(pin: String): Boolean {
        if (isLockedOut() || !hasVerifier()) return false
        return try {
            val salt = CabinetPinKdf.fromBase64(securePrefs.getString("pin_salt", null) ?: return false)
            val expected = CabinetPinKdf.fromBase64(securePrefs.getString("pin_verifier", null) ?: return false)
            val ok = try { CabinetPinKdf.matches(pin, salt, expected) } finally { salt.fill(0); expected.fill(0) }
            if (ok) {
                securePrefs.edit().putInt("pin_fail_count", 0).putLong("pin_lock_until_elapsed", 0L).apply()
            } else {
                val fails = securePrefs.getInt("pin_fail_count", 0) + 1
                val delaySec = when {
                    fails >= 8 -> 300
                    fails >= 3 -> (1 shl (fails - 3).coerceAtMost(5)).coerceAtMost(60)
                    else -> 0
                }
                val until = if (delaySec > 0) SystemClock.elapsedRealtime() + delaySec * 1000L else 0L
                securePrefs.edit().putInt("pin_fail_count", fails).putLong("pin_lock_until_elapsed", until).apply()
            }
            ok
        } catch (_: Exception) { false }
    }

    fun deletePin(currentPin: String, wipeData: Boolean): Boolean {
        return try {
            if (hasVerifier() && (currentPin.isBlank() || !checkPassword(currentPin))) return false
            securePrefs.edit()
                .remove("pin_salt").remove("pin_verifier")
                .putBoolean("pin_enabled", false)
                .putInt("pin_fail_count", 0)
                .putLong("pin_lock_until_elapsed", 0L)
                .commit()
            if (wipeData) wipeAllCabinetData()
            true
        } catch (_: Exception) { false }
    }

    fun changePassword(oldPin: String, newPin: String): Boolean {
        if (!checkPassword(oldPin) || newPin.length < 8) return false
        setPassword(newPin)
        return true
    }

    fun disablePin(currentPin: String): Boolean {
        if (!checkPassword(currentPin)) return false
        securePrefs.edit().putBoolean("pin_enabled", false).commit()
        return true
    }

    fun enablePin() { if (hasVerifier()) securePrefs.edit().putBoolean("pin_enabled", true).apply() }

    fun isLockedOut(): Boolean {
        val until = securePrefs.getLong("pin_lock_until_elapsed", 0L)
        if (until <= 0L) return false
        return SystemClock.elapsedRealtime() < until
    }

    fun lockRemainingMs(): Long {
        val until = securePrefs.getLong("pin_lock_until_elapsed", 0L)
        if (until <= 0L) return 0L
        return (until - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    fun wipeAllCabinetData() {
        try { loadWorkspaceSafe().docs.forEach { deleteAttachment(it.encName) } } catch (_: Exception) {}
        try { securePrefs.edit().remove("cases_enc").remove("workspace_v2_enc").commit() } catch (_: Exception) {}
        try { crypto.clearViewCache(); crypto.encryptedDir().listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }

    fun loadWorkspaceResult(): WorkspaceLoad {
        securePrefs.getString("workspace_v2_enc", null)?.let { enc ->
            return try {
                WorkspaceLoad.Ok(parseWs(crypto.decryptString(enc)))
            } catch (e: Exception) {
                WorkspaceLoad.DecryptError(e.message ?: "Ошибка расшифровки")
            }
        }
        val legacy = securePrefs.getString("cases_enc", null) ?: return WorkspaceLoad.Empty
        return try {
            val arr = JSONArray(crypto.decryptString(legacy))
            val cases = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(CabCase(o.getString("id"), o.optString("title", "Без названия"), notes = o.optString("notes"), updatedAt = o.optLong("updatedAt")))
                }
            }
            val ws = CabinetWorkspace(cases = cases)
            saveWorkspace(ws)
            WorkspaceLoad.Ok(ws)
        } catch (e: Exception) {
            WorkspaceLoad.DecryptError(e.message ?: "Ошибка расшифровки")
        }
    }

    fun loadWorkspace(): CabinetWorkspace = when (val r = loadWorkspaceResult()) {
        is WorkspaceLoad.Ok -> r.ws
        is WorkspaceLoad.Empty -> CabinetWorkspace()
        is WorkspaceLoad.DecryptError -> CabinetWorkspace()
    }

    private fun loadWorkspaceSafe(): CabinetWorkspace = when (val r = loadWorkspaceResult()) {
        is WorkspaceLoad.Ok -> r.ws
        else -> CabinetWorkspace()
    }

    fun saveWorkspace(ws: CabinetWorkspace): Boolean {
        val root = JSONObject()
        root.put("clients", JSONArray().also { a -> ws.clients.forEach { c -> a.put(JSONObject().put("id", c.id).put("type", c.type).put("name", c.name).put("phone", c.phone).put("email", c.email).put("notes", c.notes).put("updatedAt", c.updatedAt)) } })
        root.put("cases", JSONArray().also { a -> ws.cases.forEach { c -> a.put(JSONObject().put("id", c.id).put("title", c.title).put("category", c.category).put("status", c.status).put("clientId", c.clientId ?: JSONObject.NULL).put("court", c.court).put("caseNumber", c.caseNumber).put("subject", c.subject).put("notes", c.notes).put("updatedAt", c.updatedAt)) } })
        root.put("events", JSONArray().also { a -> ws.events.forEach { e -> a.put(JSONObject().put("id", e.id).put("caseId", e.caseId).put("type", e.type).put("title", e.title).put("body", e.body).put("at", e.at)) } })
        root.put("docs", JSONArray().also { a -> ws.docs.forEach { d -> a.put(JSONObject().put("id", d.id).put("caseId", d.caseId).put("title", d.title).put("encName", d.encName).put("createdAt", d.createdAt)) } })
        root.put("tasks", JSONArray().also { a -> ws.tasks.forEach { t -> a.put(JSONObject().put("id", t.id).put("caseId", t.caseId).put("title", t.title).put("status", t.status).put("updatedAt", t.updatedAt)) } })
        return try {
            val enc = crypto.encryptString(root.toString())
            securePrefs.edit().putString("workspace_v2_enc", enc).commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun parseWs(raw: String): CabinetWorkspace {
        val r = JSONObject(raw)
        fun ja(n: String) = r.optJSONArray(n) ?: JSONArray()
        val clients = buildList { val a = ja("clients"); for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(CabClient(o.getString("id"), o.optString("type", "person"), o.optString("name"), o.optString("phone"), o.optString("email"), o.optString("notes"), o.optLong("updatedAt"))) } }
        val cases = buildList { val a = ja("cases"); for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(CabCase(o.getString("id"), o.optString("title"), o.optString("category", "civil"), o.optString("status", "active"), if (o.isNull("clientId")) null else o.optString("clientId"), o.optString("court"), o.optString("caseNumber"), o.optString("subject"), o.optString("notes"), o.optLong("updatedAt"))) } }
        val events = buildList { val a = ja("events"); for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(CabEvent(o.getString("id"), o.getString("caseId"), o.optString("type", "note"), o.optString("title"), o.optString("body"), o.optLong("at"))) } }
        val docs = buildList { val a = ja("docs"); for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(CabDoc(o.getString("id"), o.getString("caseId"), o.optString("title"), o.getString("encName"), o.optLong("createdAt"))) } }
        val tasks = buildList { val a = ja("tasks"); for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(CabTask(o.getString("id"), o.getString("caseId"), o.optString("title"), o.optString("status", "new"), o.optLong("updatedAt"))) } }
        return CabinetWorkspace(clients, cases, events, docs, tasks)
    }

    fun importAttachment(uri: Uri, displayName: String?): String? {
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            stream.use { input ->
                val limited = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > CabinetCrypto.MAX_ATTACHMENT_BYTES) return null
                    limited.write(buf, 0, n)
                }
                crypto.importAndEncrypt(limited.toByteArray(), displayName ?: uri.lastPathSegment ?: "file")
            }
        } catch (_: Exception) { null }
    }

    fun openAttachment(encName: String, displayName: String): Uri? = try {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", crypto.decryptFileToCache(encName, displayName))
    } catch (_: Exception) { null }

    fun deleteAttachment(encName: String) = try { crypto.deleteEncryptedFile(encName) } catch (_: Exception) {}
    fun clearViewCache() = try { crypto.clearViewCache() } catch (_: Exception) {}
}
