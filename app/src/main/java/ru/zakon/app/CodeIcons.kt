package ru.zakon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject

object CodeIcons {
    private var cache: MutableMap<String, Bitmap> = mutableMapOf()
    private var loaded = false

    private val idMap = mapOf(
        "uk" to "uk", "const" to "fz", "constitution" to "fz",
        "gk" to "gk", "apk" to "apk", "koap" to "koap",
        "tk" to "tk", "nk" to "nk", "zhk" to "zhk",
        "zk" to "zk", "fz" to "fz", "bk" to "bk"
    )

    fun bitmapFor(context: Context, documentId: String): Bitmap? {
        ensureLoaded(context)
        val key = idMap[documentId.lowercase()] ?: documentId.lowercase()
        return cache[key]
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val json = context.assets.open("code_icons.json").bufferedReader().readText()
                val root = JSONObject(json)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val b64 = root.getString(k)
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    cache[k] = bmp
                }
            } catch (_: Exception) {
                // icons optional
            }
            loaded = true
        }
    }
}
