package ru.zakon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

object CodeIcons {
    private var cache: MutableMap<String, Bitmap> = mutableMapOf()
    private var loaded = false

    private val idMap = mapOf(
        "uk" to "uk", "upk" to "uk", "uik" to "uk",
        "gk" to "gk", "gpk" to "gk",
        "apk" to "apk",
        "koap" to "koap", "kas" to "koap",
        "tk" to "tk", "sk" to "tk",
        "nk" to "nk",
        "zhk" to "zhk",
        "zk" to "zk", "grk" to "zk",
        "bk" to "bk",
        "fz" to "fz", "const" to "fz", "constitution" to "fz",
        "vk" to "fz", "lk" to "fz", "air" to "fz", "ktm" to "fz"
    )

    private val assetNames = listOf(
        "uk", "gk", "apk", "koap", "tk", "nk", "zhk", "zk", "fz", "bk"
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
            for (name in assetNames) {
                try {
                    val b64 = context.assets.open("icon_$name.b64").bufferedReader().readText().trim()
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    cache[name] = bmp
                } catch (_: Exception) {
                }
            }
            loaded = true
        }
    }
}
