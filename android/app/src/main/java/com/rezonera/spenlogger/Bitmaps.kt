package com.rezonera.spenlogger

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/** Async bitmap cache for PDF pages and images. All callbacks run on the main thread. */
object Bitmaps {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(256L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // PdfRenderer is not thread-safe: a single worker thread does all decoding.
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()

    /** Returns the cached bitmap, or null while it is being loaded (onReady fires when done). */
    fun get(key: String, loader: () -> Bitmap?, onReady: () -> Unit): Bitmap? {
        cache.get(key)?.let { return it }
        if (failed.contains(key)) return null
        if (pending.add(key)) {
            exec.execute {
                val b = try {
                    loader()
                } catch (_: Throwable) {
                    null
                }
                main.post {
                    pending.remove(key)
                    if (b != null) cache.put(key, b) else failed.add(key)
                    onReady()
                }
            }
        }
        return null
    }

    fun pdfLoader(file: File, page: Int, targetW: Int): () -> Bitmap? = {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            val pg = renderer.openPage(page)
            try {
                val h = (targetW.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                pg.close()
            }
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    fun imageLoader(file: File, maxSide: Int): () -> Bitmap? = {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.path, opts)
    }
}
