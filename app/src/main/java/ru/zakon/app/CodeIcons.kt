package ru.zakon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

object CodeIcons {
    private var cache: MutableMap<String, Bitmap> = mutableMapOf()
    private var loaded = false

    private val idMap = mapOf(
        "uk" to "uk", "const" to "fz", "constitution" to "fz",
        "gk" to "gk", "apk" to "apk", "koap" to "koap",
        "tk" to "tk", "nk" to "nk", "zhk" to "zhk",
        "zk" to "zk", "fz" to "fz", "bk" to "bk"
    )

    private val assetNames = listOf("uk", "gk", "apk", "koap", "tk", "nk", "zhk", "zk", "fz", "bk")

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
                    val a = context.assets.open("icon_${name}_a.b64").bufferedReader().readText().trim()
                    val b = context.assets.open("icon_${name}_b.b64").bufferedReader().readText().trim()
                    val bytes = Base64.decode(a + b, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    cache[name] = bmp
                } catch (_: Exception) {
                }
            }
            loaded = true
        }
    }
}
