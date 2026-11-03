package com.jarvis.assistant.ui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.jarvis.assistant.platform.JarvisFileProvider
import com.jarvis.core.util.JarvisLog
import java.util.concurrent.Executors

/**
 * Small thumbnail loader: decodes off the main thread with a sample size so a 12-megapixel generated image costs a few hundred KB, caches the results in memory
 * (a quarter of an 8th of the app's heap at most), and ignores answers for views that have since been reused for another picture.
 */
class ImageLoader(context: Context, private val log: JarvisLog = JarvisLog.None) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "jarvis-thumbs").apply { isDaemon = true } }
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 8) / 1024).toInt().coerceIn(2_048, 48_000)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Shows [stored] (a URI or path JARVIS saved) in [view], at most [maxPx] on the longest side. [placeholder] is shown until it arrives or if it cannot be read. */
    fun load(stored: String, maxPx: Int, view: ImageView, placeholder: Int? = null, onFail: (() -> Unit)? = null) {
        val key = "$stored@$maxPx"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        if (placeholder != null) view.setImageResource(placeholder) else view.setImageDrawable(null)
        pool.execute {
            val bmp = try { decode(stored, maxPx) } catch (e: Throwable) { log.w("Images", "could not decode a thumbnail (${e.javaClass.simpleName})"); null }
            main.post {
                if (view.tag != key) return@post
                if (bmp != null) { cache.put(key, bmp); view.setImageBitmap(bmp) } else onFail?.invoke()
            }
        }
    }

    fun clear() = cache.evictAll()

    private fun decode(stored: String, maxPx: Int): Bitmap? {
        val uri = JarvisFileProvider.safeUriFor(ctx, stored) ?: run { log.w("Images", "a picture was not shown: its address is not one JARVIS saved"); return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // NOTE: with inJustDecodeBounds the decoder ALWAYS returns null (it only fills outWidth/outHeight). The previous code treated that null as "could not decode",
        // so no thumbnail was ever shown in the chat or the Library. Open the stream, ignore the return value, and read the bounds instead.
        val opened = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
        if (!opened) { log.w("Images", "a picture could not be opened"); return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { log.w("Images", "a saved file is not a readable picture"); return null }
        var sample = 1
        while ((bounds.outWidth / (sample * 2)) >= maxPx && (bounds.outHeight / (sample * 2)) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) log.w("Images", "a picture could not be decoded")
        return bmp
    }
}
