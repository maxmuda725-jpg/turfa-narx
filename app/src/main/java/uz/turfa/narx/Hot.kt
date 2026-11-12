package uz.turfa.narx

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 🔥 Ko'p sotiladigan tovarlar: oxirgi 30 kunda eng ko'p sotilgan 100 ta (Do'kon Yordamchisi, /api/public/ommabop).
 * Server faqat tovar kodlarini tartib bilan beradi — sotuv soni yoki summasi yo'q.
 */
object Hot {
    private const val FILE = "ommabop.json"
    @Volatile private var order: List<String> = emptyList()
    @Volatile private var rank: Map<String, Int> = emptyMap()

    fun codes(): List<String> = order
    fun isHot(p: Product) = p.code.isNotBlank() && p.code in rank

    private fun set(list: List<String>) {
        order = list
        rank = list.withIndex().associate { it.value to it.index }
    }

    private fun parse(text: String): List<String> {
        val a = org.json.JSONObject(text).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    fun load(ctx: Context) {
        try { set(parse(File(ctx.filesDir, FILE).readText())) } catch (_: Exception) {}
    }

    fun download(ctx: Context) {
        val c = URL("$KASSA_API/api/public/ommabop").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        try {
            if (c.responseCode != 200) return
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val list = parse(text)
            if (list.isNotEmpty()) { File(ctx.filesDir, FILE).writeText(text); set(list) }
        } finally { c.disconnect() }
    }
}

/** Bosh ekran banneri: do'kon rasmi. GitHub'dagi baza/fon.jpg almashtirilsa, 🔄 dan keyin yangisi chiqadi. */
object Fon {
    private const val URL_FON = "https://raw.githubusercontent.com/maxmuda725-jpg/turfa-narx/main/baza/fon.jpg"
    private const val FILE = "fon.jpg"

    fun bitmap(ctx: Context): android.graphics.Bitmap? = try {
        val f = File(ctx.filesDir, FILE)
        if (f.length() > 1000) android.graphics.BitmapFactory.decodeFile(f.path)
        else ctx.assets.open(FILE).use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) { null }

    /** Yangi rasm yuklansa true. */
    fun download(ctx: Context): Boolean {
        val c = URL("$URL_FON?t=" + System.currentTimeMillis() / 3_600_000).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        return try {
            if (c.responseCode != 200) return false
            val bytes = c.inputStream.use { it.readBytes() }
            val f = File(ctx.filesDir, FILE)
            if (bytes.size < 1000 || (f.exists() && f.readBytes().contentEquals(bytes))) false
            else { f.writeBytes(bytes); true }
        } catch (_: Exception) { false } finally { c.disconnect() }
    }
}
