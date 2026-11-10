package uz.turfa.narx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ro'yxat rasmini Google Gemini AI orqali o'qish (tushunib o'qiydi, xatolarni tuzatadi).
 * Natija — "nom kod soni ta narx" ko'rinishidagi qatorlar; bazadan qidirish ListParser'da.
 */
object AiReader {
    private val MODELS = listOf("gemini-flash-latest", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

    private const val PROMPT = """Bu rasmda do'kon uchun optom buyurtma ro'yxati (qo'lda yoki bosma yozilgan).
Har bir tovar qatorini o'qib, faqat JSON massiv qaytar:
[{"kod":"43-25","nom":"gul","soni":10,"narx":34000}]
Qoidalar:
- "kod" — tovar kodi: raqamlar va chiziqchalar (masalan 43-25, 25-11-9-9, 8-8). Qo'lyozma xatolarini tuzat (O→0, l→1, З→3). Kod yo'q bo'lsa "".
- "nom" — tovar nomi so'zlari (gul, oyna, soat...), bo'lmasa "".
- "soni" — miqdor, butun son. Yozilmagan bo'lsa 1.
- "narx" — narx so'mda butun son (34 000 -> 34000). Yozilmagan bo'lsa 0. Miqdorni narx bilan adashtirma.
- Sarlavha, sana, telefon raqami, jami summa qatorlarini qo'shma.
- Har bir qatorni alohida yoz, birortasini ham tashlab ketma. Faqat JSON qaytar."""

    fun imageBase64(ctx: Context, uri: Uri, maxSide: Int = 1800): String {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var smp = 1
        while (maxOf(o.outWidth, o.outHeight) / smp > maxSide * 2) smp *= 2
        val bmp0 = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = smp })
        } ?: throw IllegalStateException("Rasm ochilmadi")
        val scale = maxSide.toFloat() / maxOf(bmp0.width, bmp0.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(bmp0, (bmp0.width * scale).toInt(), (bmp0.height * scale).toInt(), true) else bmp0
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Ishlamay qolsa xabar bilan Exception tashlaydi. */
    fun read(ctx: Context, uri: Uri, key: String): List<String> {
        val img = imageBase64(ctx, uri)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", img))))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("response_mime_type", "application/json"))
            .toString()
        var lastErr = "AI javob bermadi"
        for (model in MODELS) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 90000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", key)
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                if (code == 404) { lastErr = "Model topilmadi: $model"; continue }   // keyingi modelni sinaymiz
                if (code == 400 && text.contains("API key", true)) throw IllegalStateException("AI kaliti noto'g'ri")
                if (code == 403) throw IllegalStateException("AI kaliti ruxsat bermadi (403)")
                if (code == 429) throw IllegalStateException("Bugungi AI limiti tugadi")
                if (code !in 200..299) { lastErr = "AI xatosi ($code)"; continue }
                val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                return toRows(out)
            } finally { c.disconnect() }
        }
        throw IllegalStateException(lastErr)
    }

    fun toRows(json: String): List<String> {
        val clean = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONArray(clean)
        val rows = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kod = o.optString("kod").trim()
            val nom = o.optString("nom").trim()
            val soni = o.optLong("soni", 1).coerceAtLeast(1)
            val narx = o.optLong("narx", 0)
            if (kod.isEmpty() && nom.isEmpty()) continue
            rows += buildString {
                if (nom.isNotEmpty()) append(nom).append(' ')
                append(kod).append("  ").append(soni).append(" ta")
                if (narx > 0) append("  ").append(narx)
            }.trim()
        }
        return rows
    }
}
