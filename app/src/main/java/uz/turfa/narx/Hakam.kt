package uz.turfa.narx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 🤖 Gemini "hakam": rasm qidiruvi topgan eng o'xshash nomzodlardan aynan qaysi biri suratdagi tovar ekanini tanlaydi.
 * Sinov (scripts/gemini_sinov.py, 70 rasm): ilova yolg'iz 54% -> ilova + Gemini (ishonch >= 80%) 66%.
 * Gemini ishonchi past bo'lsa, limit tugasa yoki internet bo'lmasa — null, ilovaning o'z tartibi qoladi.
 */
object Hakam {
    const val TOP_K = 15
    const val MIN_CONF = 80
    // Sinovda Flash-Lite ishladi (bepul limiti eng katta); qolganlari zaxira
    private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

    private const val PROMPT = """Birinchi rasm — do'konda olingan SO'ROV rasmi (bitta tovar).
Keyingi rasmlar — do'kon katalogidagi NOMZOD tovarlar, har birining oldida raqami yozilgan (1, 2, 3 ...).
Vazifa: so'rov rasmidagi tovar AYNAN qaysi nomzod ekanini top.
- Shakl, rang, naqsh, yozuv, material, detallarni solishtir. Bir seriyadagi o'xshash tovarlarni farqla.
- Fon, yorug'lik, burchak, qadoq farqiga e'tibor berma.
- Hech biri aynan shu tovar bo'lmasa 0 qaytar.
Faqat JSON qaytar: {"tanlov": <raqam>, "ishonch": <0-100>}"""

    class Pick(val product: Product, val conf: Int)

    @Volatile private var limitDay = ""   // shu kun limit tugagan bo'lsa qayta urinmaymiz

    private fun b64(bmp: Bitmap, side: Int): String {
        val s = minOf(1f, side.toFloat() / maxOf(bmp.width, bmp.height))
        val b = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun fetch(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 6000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) null else {
                val bytes = c.inputStream.use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var smp = 1
                while (maxOf(o.outWidth, o.outHeight) / smp > 900) smp *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = smp })
            }
        } finally { c.disconnect() }
    } catch (_: Throwable) { null }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Ilova tartibidagi nomzodlardan Gemini tanlovini qaytaradi (ishonch >= MIN_CONF), aks holda null. Fon oqimida chaqiriladi. */
    fun pick(query: Bitmap, ranked: List<Product>, key: String): Pick? {
        if (key.isBlank() || limitDay == today()) return null
        val cands = ranked.filter { it.photo.isNotBlank() }.take(TOP_K)
        if (cands.size < 2) return null
        val pool = Executors.newFixedThreadPool(6)
        val imgs = try {
            pool.invokeAll(cands.map { p -> Callable { fetch(p.photo) } }, 8, TimeUnit.SECONDS)
                .map { f -> try { if (f.isCancelled) null else f.get() } catch (_: Throwable) { null } }
        } finally { pool.shutdownNow() }
        val use = cands.indices.filter { imgs[it] != null }
        if (use.size < 2) return null

        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("text", "SO'ROV rasmi:"))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(query, 640))))
        use.forEachIndexed { n, i ->
            parts.put(JSONObject().put("text", "Nomzod ${n + 1}:"))
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(imgs[i]!!, 448))))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0).put("response_mime_type", "application/json"))
            .toString().toByteArray()

        var limited = 0
        for (model in MODELS) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 6000; c.readTimeout = 12000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", key)
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code == 429) { limited++; continue }
                if (code !in 200..299) { if (code == 400 || code == 403) return null; continue }
                val txt = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val j = JSONObject(txt)
                val n = j.optInt("tanlov", 0); val conf = j.optInt("ishonch", 0)
                if (n < 1 || n > use.size || conf < MIN_CONF) return null
                return Pick(cands[use[n - 1]], conf)
            } catch (_: Throwable) {
                continue
            } finally { c.disconnect() }
        }
        if (limited == MODELS.size) limitDay = today()
        return null
    }
}
