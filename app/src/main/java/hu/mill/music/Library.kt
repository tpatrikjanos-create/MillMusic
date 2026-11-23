package hu.mill.music

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A felület (JS) által küldött könyvtár-pillanatkép: listák, számok, kedvenc rádiók,
 * legutóbb hallgatott és ajánlott számok. Fájlba is mentjük, így az Android Auto
 * menüje akkor is megvan, ha a telefonos felület épp nem fut.
 */
object Library {
    @Volatile
    private var data: JSONObject? = null

    fun get(ctx: Context): JSONObject {
        data?.let { return it }
        val o = try {
            JSONObject(File(ctx.filesDir, "library.json").readText())
        } catch (_: Exception) {
            JSONObject()
        }
        data = o
        return o
    }

    fun save(ctx: Context, json: String) {
        try {
            val o = JSONObject(json)
            val tmp = File(ctx.filesDir, "library.tmp")
            tmp.writeText(json)
            tmp.renameTo(File(ctx.filesDir, "library.json"))
            data = o
        } catch (_: Exception) {
        }
    }
}
