package ru.zakon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

object CodeIcons {
    private var cache: MutableMap<String, Bitmap> = mutableMapOf()
    private var loaded = false

    private val idMap = mapOf(
        "uk" to "uk", "upk" to "upk", "uik" to "uik",
        "gk" to "gk", "gpk" to "gpk", "grk" to "grk",
        "apk" to "apk", "koap" to "koap", "kas" to "kas",
        "tk" to "tk", "sk" to "sk",
        "nk" to "nk", "bk" to "bk",
        "zhk" to "zhk", "zk" to "zk",
        "vk" to "vk", "lk" to "lk", "air" to "air", "ktm" to "ktm",
        "fz" to "fz", "const" to "fz", "constitution" to "fz"
    )

    private val assetNames = listOf(
        "uk", "gk", "apk", "koap", "tk", "nk", "zhk", "zk", "fz", "bk",
        "upk", "gpk", "sk", "grk", "vk", "lk", "air", "ktm", "kas", "uik"
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
