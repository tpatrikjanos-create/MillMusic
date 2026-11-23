package hu.mill.music

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A felület a https://millmusic.local címről töltődik (valódi https eredet → működik a
 * localStorage és a YouTube/Radio API-k). A /local?u=<content uri> útvonal a telefon
 * zenefájljait szolgálja ki, tekeréshez Range-támogatással.
 */
object LocalServer {
    const val HOST = "millmusic.local"

    fun handle(ctx: Context, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url ?: return null
        if (u.host != HOST) return null
        return try {
            when (u.path) {
                "/", "/index.html" -> WebResourceResponse("text/html", "utf-8", ctx.assets.open("index.html"))
                "/local" -> content(ctx, req, u)
                else -> empty(404, "Not Found")
            }
        } catch (_: Exception) {
            empty(404, "Not Found")
        }
    }

    private fun empty(code: Int, reason: String) = WebResourceResponse(
        "text/plain", "utf-8", code, reason,
        mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0))
    )

    private fun content(ctx: Context, req: WebResourceRequest, u: Uri): WebResourceResponse {
        val target = Uri.parse(u.getQueryParameter("u") ?: return empty(404, "Not Found"))
        val afd = ctx.contentResolver.openAssetFileDescriptor(target, "r") ?: return empty(404, "Not Found")
        var total = afd.length
        if (total < 0) total = afd.parcelFileDescriptor.statSize
        val mime = ctx.contentResolver.getType(target) ?: "audio/mpeg"
        val input = afd.createInputStream()
        val range = req.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", true) }?.value
        val h = mutableMapOf(
            "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache"
        )
        if (range != null && total > 0) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
            val start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = (m?.groupValues?.get(2)?.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
            if (start >= total) {
                input.close()
                h["Content-Range"] = "bytes */$total"
                return WebResourceResponse(mime, null, 416, "Range Not Satisfiable", h, ByteArrayInputStream(ByteArray(0)))
            }
            skipFully(input, start)
            val len = end - start + 1
            h["Content-Range"] = "bytes $start-$end/$total"
            h["Content-Length"] = len.toString()
            return WebResourceResponse(mime, null, 206, "Partial Content", h, Limited(input, len))
        }
        if (total > 0) h["Content-Length"] = total.toString()
        return WebResourceResponse(mime, null, 200, "OK", h, input)
    }

    private fun skipFully(input: InputStream, n: Long) {
        var r = n
        while (r > 0) {
            val s = input.skip(r)
            if (s <= 0) {
                if (input.read() < 0) break
                r--
            } else r -= s
        }
    }

    private class Limited(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = inner.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() = inner.close()
    }
}
