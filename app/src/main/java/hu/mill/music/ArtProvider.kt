package hu.mill.music

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Borítóképek az Android Autónak, a lezárt képernyőnek és az értesítésnek.
 * Az Android Auto csak content:// képet tölt be, ezért minden kép innen jön:
 *   content://hu.mill.music.art/web/<https url>     – YouTube előnézet, rádió logó
 *   content://hu.mill.music.art/local/<content uri> – mp3-ba ágyazott borító
 *   content://hu.mill.music.art/emoji/<emoji>       – listák ikonja
 * A képek a cache mappába kerülnek, így mindegyiket csak egyszer kell előállítani.
 */
class ArtProvider : ContentProvider() {

    companion object {
        const val AUTH = "hu.mill.music.art"

        /** "web:https://…" / "local:content://…" / "emoji:🎄" → content:// URI */
        fun uriFor(key: String?): Uri? {
            if (key.isNullOrBlank()) return null
            val i = key.indexOf(':')
            if (i <= 0) return null
            return Uri.Builder().scheme("content").authority(AUTH)
                .appendPath(key.substring(0, i)).appendPath(key.substring(i + 1)).build()
        }

        fun emojiUri(e: String): Uri? = uriFor("emoji:$e")

        fun fileForKey(ctx: Context, key: String?): File? {
            if (key.isNullOrBlank()) return null
            val i = key.indexOf(':')
            if (i <= 0) return null
            return fileFor(ctx, key.substring(0, i), key.substring(i + 1))
        }

        @Synchronized
        fun fileFor(ctx: Context, type: String, v: String): File {
            val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
            val f = File(dir, type + "_" + sha1(type + v) + ".png")
            if (f.exists() && f.length() > 0) return f
            val bmp: Bitmap = when (type) {
                "web" -> download(v)
                "local" -> embedded(ctx, v)
                else -> null
            } ?: emoji(if (type == "emoji") v else "🎵")
            val tmp = File(dir, f.name + ".tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(f)
            return f
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

        private fun scale(b: Bitmap, max: Int = 512): Bitmap {
            val m = maxOf(b.width, b.height)
            if (m <= max) return b
            val r = max.toFloat() / m
            return Bitmap.createScaledBitmap(
                b, (b.width * r).toInt().coerceAtLeast(1), (b.height * r).toInt().coerceAtLeast(1), true
            )
        }

        private fun download(url: String): Bitmap? = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "MillMusic/0.2")
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { scale(it) }
        } catch (_: Exception) {
            null
        }

        private fun embedded(ctx: Context, uri: String): Bitmap? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(ctx, Uri.parse(uri))
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { scale(it) }
            } catch (_: Exception) {
                null
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }

        private fun emoji(e: String): Bitmap {
            val s = 256
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            bg.shader = LinearGradient(
                0f, 0f, s.toFloat(), s.toFloat(),
                0xFF2E1065.toInt(), 0xFF8B5CF6.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), bg)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 140f
                textAlign = Paint.Align.CENTER
            }
            c.drawText(e, s / 2f, s / 2f - (t.descent() + t.ascent()) / 2f, t)
            return b
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val seg = uri.pathSegments
        if (seg.size < 2) return null
        val ctx = context ?: return null
        val f = fileFor(ctx, seg[0], seg[1])
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
