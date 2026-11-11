package uz.turfa.narx

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tovar qaysi polkada turadi: "polka-etaj", etaj TEPADAN sanaladi ("7-2" — 7-polka, tepadan 2-etaj).
 * Ilovada "7-polka, 2-etaj" deb ko'rsatiladi (label), saqlanishi qisqa: "7-2".
 * Bir tovar 3 tagacha joyda bo'lishi mumkin: "7-2,15-4".
 * Hamma telefonga server orqali (Do'kon Yordamchisi: /api/kassa/polka, /api/public/polkalar), kalit — tovar kodi.
 * Stellajlarda qavat soni har xil bo'lishi mumkin — hech qanday qat'iy sozlama yo'q.
 */
object Polka {
    private const val FILE = "polkalar.json"
    val RE = Regex("^\\d{1,3}-\\d{1,2}$")

    @Volatile private var map: Map<String, String> = emptyMap()

    fun count() = map.size

    /** Tovarning joylari: ["7-2", "15-4"] (bo'sh — yozilmagan). */
    fun of(p: Product): List<String> =
        if (p.code.isBlank()) emptyList() else map[p.code]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun stellaj(loc: String) = loc.substringBefore('-')

    /** "7-2" — aniq qavat; "7" — butun stellaj. */
    fun matches(p: Product, sel: String): Boolean =
        of(p).any { if (sel.contains('-')) it == sel else stellaj(it) == sel }

    /** Tartiblash kaliti: 7-2 < 7-10 < 12-1 (raqam bo'yicha). */
    fun sortKey(loc: String): Long {
        val s = stellaj(loc).toLongOrNull() ?: 999
        val q = loc.substringAfter('-', "").toLongOrNull() ?: 0
        return s * 1000 + q
    }

    /** Yozuvni tozalaydi: "7 - 2" / "7.2" / "7/2" -> "7-2"; noto'g'ri bo'lsa null. */
    fun normalize(s: String): String? {
        val t = s.trim().replace(Regex("\\s*[-./,: ]\\s*"), "-").trim('-')
        val parts = t.split('-')
        if (parts.size != 2) return null
        val a = parts[0].toIntOrNull() ?: return null
        val b = parts[1].toIntOrNull() ?: return null
        if (a !in 1..999 || b !in 1..99) return null
        return "$a-$b"
    }

    /** Ko'rsatish uchun: "7-2" -> "7-polka, 2-etaj"; "7" -> "7-polka". */
    fun label(loc: String): String =
        if (loc.contains('-')) "${stellaj(loc)}-polka, ${loc.substringAfter('-')}-etaj" else "$loc-polka"

    fun labels(locs: List<String>) = locs.joinToString("; ") { label(it) }

    fun next(loc: String): String = "${stellaj(loc)}-${(loc.substringAfter('-').toIntOrNull() ?: 0) + 1}"

    /** Hamma ishlatilgan joylar (tartib bilan). */
    fun all(): List<String> = map.values.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        .distinct().sortedBy { sortKey(it) }

    private fun parse(text: String): Map<String, String> {
        val j = org.json.JSONObject(text)
        val o = j.optJSONObject("items") ?: j
        val m = HashMap<String, String>()
        for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it }
        return m
    }

    fun load(ctx: Context) {
        map = try { parse(File(ctx.filesDir, FILE).readText()) } catch (e: Exception) { emptyMap() }
    }

    fun download(ctx: Context) {
        val c = URL("$KASSA_API/api/public/polkalar?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        try {
            if (c.responseCode != 200) return
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val m = parse(text)
            File(ctx.filesDir, FILE).writeText(org.json.JSONObject(m as Map<*, *>).toString())
            map = m
        } finally { c.disconnect() }
    }

    /** Server tasdiqlagandan keyin shu telefonda darrov qo'llash. value = null — joyni o'chirish. */
    @Synchronized
    fun setLocal(ctx: Context, codes: List<String>, value: String?) {
        map = map.toMutableMap().apply { codes.forEach { if (value == null) remove(it) else put(it, value) } }
        try { File(ctx.filesDir, FILE).writeText(org.json.JSONObject(map as Map<*, *>).toString()) } catch (_: Exception) {}
    }

    /** Serverga yozadi. Qaytaradi: HTTP kod (-1 — internet yo'q). Asosiy oqimda chaqirmang. */
    fun save(codes: List<String>, value: String?, key: String, xodim: String): Int = try {
        val c = URL("$KASSA_API/api/kassa/polka").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-app-key", key)
        c.setRequestProperty("x-xodim", xodim)
        val skus = org.json.JSONArray().apply { codes.forEach { put(it) } }
        c.outputStream.use { it.write(org.json.JSONObject().put("skus", skus).put("value", value ?: org.json.JSONObject.NULL).toString().toByteArray()) }
        val rc = c.responseCode; c.disconnect(); rc
    } catch (e: Exception) { -1 }
}
