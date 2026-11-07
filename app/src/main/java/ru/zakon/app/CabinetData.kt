package ru.zakon.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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

internal val CaseCategories = listOf("civil" to "Гражданское", "admin" to "Административное", "criminal" to "Уголовное", "arbitral" to "Арбитражное", "family" to "Семейное", "labor" to "Трудовое", "other" to "Иное")
internal val CaseStatuses = listOf("active" to "В работе", "waiting" to "Ожидание", "court" to "В суде", "done" to "Завершено", "archive" to "Архив")
internal fun lab(map: List<Pair<String, String>>, k: String) = map.find { it.first == k }?.second ?: k
internal fun fmt(ms: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(ms))

internal class CabinetStorage(private val context: Context) {
    private val crypto = CabinetCrypto(context)
    private val securePrefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "zakon_cabinet_secure_v1", masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val legacyPrefs = context.getSharedPreferences("zakon_cabinet", Context.MODE_PRIVATE)
    init {
        val lv = legacyPrefs.getString("pin_verifier", null); val ls = legacyPrefs.getString("pin_salt", null)
        if (lv != null && ls != null && !securePrefs.contains("pin_verifier")) securePrefs.edit().putString("pin_salt", ls).putString("pin_verifier", lv).putBoolean("pin_enabled", true).apply()
        legacyPrefs.getString("cases_enc", null)?.let { if (!securePrefs.contains("cases_enc")) securePrefs.edit().putString("cases_enc", it).apply() }
        legacyPrefs.edit().clear().apply()
    }
    fun hasVerifier() = securePrefs.contains("pin_verifier") && securePrefs.contains("pin_salt")
    fun isPinEnabled() = securePrefs.getBoolean("pin_enabled", hasVerifier())
    fun hasPassword() = isPinEnabled() && hasVerifier()
    fun isPinPromptDone() = securePrefs.getBoolean("pin_prompt_done", false)
    fun markPinPromptDone() { securePrefs.edit().putBoolean("pin_prompt_done", true).apply() }
    fun setPassword(pin: String) {
        require(pin.length >= 6)
        val salt = CabinetPinKdf.newSalt(); val verifier = CabinetPinKdf.derive(pin, salt)
        securePrefs.edit().putString("pin_salt", CabinetPinKdf.toBase64(salt)).putString("pin_verifier", CabinetPinKdf.toBase64(verifier)).putBoolean("pin_enabled", true).putBoolean("pin_prompt_done", true).putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply()
        salt.fill(0); verifier.fill(0)
        try { crypto.ensureKey(requireUserAuth = true) } catch (_: Exception) {}
    }
    fun checkPassword(pin: String): Boolean {
        if (isLockedOut() || !hasVerifier()) return false
        return try {
            val salt = CabinetPinKdf.fromBase64(securePrefs.getString("pin_salt", null) ?: return false)
            val expected = CabinetPinKdf.fromBase64(securePrefs.getString("pin_verifier", null) ?: return false)
            val ok = try { CabinetPinKdf.matches(pin, salt, expected) } finally { salt.fill(0); expected.fill(0) }
            if (ok) securePrefs.edit().putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).apply() else {
                val fails = securePrefs.getInt("pin_fail_count", 0) + 1
                val d = when { fails >= 8 -> 300; fails >= 3 -> (1 shl (fails - 3).coerceAtMost(5)).coerceAtMost(60); else -> 0 }
                securePrefs.edit().putInt("pin_fail_count", fails).putLong("pin_lock_until", if (d > 0) System.currentTimeMillis() + d * 1000L else 0L).apply()
            }
            ok
        } catch (_: Exception) { false }
    }
    fun deletePin(currentPin: String, wipeData: Boolean): Boolean = try {
        if (hasVerifier() && (currentPin.isBlank() || !checkPassword(currentPin))) return false
        securePrefs.edit().remove("pin_salt").remove("pin_verifier").putBoolean("pin_enabled", false).putInt("pin_fail_count", 0).putLong("pin_lock_until", 0L).commit()
        if (wipeData) wipeAllCabinetData(); true
    } catch (_: Exception) { false }
    fun changePassword(oldPin: String, newPin: String): Boolean { if (!checkPassword(oldPin) || newPin.length < 6) return false; setPassword(newPin); return true }
    fun disablePin(currentPin: String): Boolean { if (!checkPassword(currentPin)) return false; securePrefs.edit().putBoolean("pin_enabled", false).commit(); return true }
    fun enablePin() { if (hasVerifier()) securePrefs.edit().putBoolean("pin_enabled", true).apply() }
    fun isLockedOut() = System.currentTimeMillis() < securePrefs.getLong("pin_lock_until", 0L)
    fun lockRemainingMs() = (securePrefs.getLong("pin_lock_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    fun wipeAllCabinetData() {
        try { loadWorkspace().docs.forEach { deleteAttachment(it.encName) } } catch (_: Exception) {}
        try { securePrefs.edit().remove("cases_enc").remove("workspace_v2_enc").commit() } catch (_: Exception) {}
        try { crypto.clearViewCache(); crypto.encryptedDir().listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
    fun loadWorkspace(): CabinetWorkspace {
        securePrefs.getString("workspace_v2_enc", null)?.let {
            return try { parseWs(crypto.decryptString(it)) } catch (_: Exception) { CabinetWorkspace() }
        }
        val legacy = securePrefs.getString("cases_enc", null) ?: return CabinetWorkspace()
        return try {
            val arr = JSONArray(crypto.decryptString(legacy))
            val cases = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(CabCase(o.getString("id"), o.optString("title", "Без названия"), notes = o.optString("notes"), updatedAt = o.optLong("updatedAt")))
                }
            }
            val ws = CabinetWorkspace(cases = cases); saveWorkspace(ws); ws
        } catch (_: Exception) { CabinetWorkspace() }
    }
    fun saveWorkspace(ws: CabinetWorkspace) {
        val root = JSONObject()
        root.put("clients", JSONArray().also { a -> ws.clients.forEach { c -> a.put(JSONObject().put("id", c.id).put("type", c.type).put("name", c.name).put("phone", c.phone).put("email", c.email).put("notes", c.notes).put("updatedAt", c.updatedAt)) } })
        root.put("cases", JSONArray().also { a -> ws.cases.forEach { c -> a.put(JSONObject().put("id", c.id).put("title", c.title).put("category", c.category).put("status", c.status).put("clientId", c.clientId ?: JSONObject.NULL).put("court", c.court).put("caseNumber", c.caseNumber).put("subject", c.subject).put("notes", c.notes).put("updatedAt", c.updatedAt)) } })
        root.put("events", JSONArray().also { a -> ws.events.forEach { e -> a.put(JSONObject().put("id", e.id).put("caseId", e.caseId).put("type", e.type).put("title", e.title).put("body", e.body).put("at", e.at)) } })
        root.put("docs", JSONArray().also { a -> ws.docs.forEach { d -> a.put(JSONObject().put("id", d.id).put("caseId", d.caseId).put("title", d.title).put("encName", d.encName).put("createdAt", d.createdAt)) } })
        root.put("tasks", JSONArray().also { a -> ws.tasks.forEach { t -> a.put(JSONObject().put("id", t.id).put("caseId", t.caseId).put("title", t.title).put("status", t.status).put("updatedAt", t.updatedAt)) } })
        try { securePrefs.edit().putString("workspace_v2_enc", crypto.encryptString(root.toString())).apply() } catch (_: Exception) {}
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
    fun importAttachment(uri: Uri, displayName: String?): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        crypto.importAndEncrypt(bytes, displayName ?: uri.lastPathSegment ?: "file")
    } catch (_: Exception) { null }
    fun openAttachment(encName: String, displayName: String): Uri? = try {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", crypto.decryptFileToCache(encName, displayName))
    } catch (_: Exception) { null }
    fun deleteAttachment(encName: String) = try { crypto.deleteEncryptedFile(encName) } catch (_: Exception) {}
    fun clearViewCache() = try { crypto.clearViewCache() } catch (_: Exception) {}
}
