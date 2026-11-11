package uz.turfa.narx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Rasm orqali qidirish.
 * Tovar rasmlari GitHub'da (scripts/embed.py) vektorga aylantirilgan: baza/vectors.bin.
 * Telefon olingan rasmni AYNAN shu model bilan vektorga aylantiradi va eng yaqinlarini topadi.
 * Do'konchi to'g'ri tovarni tanlasa, rasm shu telefonda eslab qolinadi (learned.bin) — keyingi safar aniqroq.
 */
object Visual {
    const val VECTORS_URL = "https://raw.githubusercontent.com/maxmuda725-jpg/turfa-narx/main/baza/vectors.bin"
    private const val MODEL = "embedder.tflite"
    private const val SIDE = 640          // scripts/embed.py dagi bilan bir xil
    private const val MAX_LEARNED = 4000

    class Entry(val key: String, val url: String, val v: ByteArray)

    @Volatile private var server: List<Entry> = emptyList()
    @Volatile private var learned: List<Entry> = emptyList()
    private var embedder: ImageEmbedder? = null

    fun count() = server.size
    fun learnedCount() = learned.size
    fun ready() = server.isNotEmpty() || learned.isNotEmpty()

    // ------------------------------------------------------------ model
    @Synchronized
    private fun embedder(ctx: Context): ImageEmbedder = embedder ?: ImageEmbedder.createFromOptions(
        ctx.applicationContext,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .setQuantize(false)
            .build()
    ).also { embedder = it }

    /** Rasmni ochadi: burilishini to'g'rilaydi, uzun tomoni SIDE gacha kichraytiradi. */
    fun bitmap(ctx: Context, uri: Uri): Bitmap {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var smp = 1
        while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= SIDE) smp *= 2
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = smp; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IllegalStateException("Rasm ochilmadi")
        val rot = try {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        val scale = minOf(1f, SIDE.toFloat() / maxOf(b.width, b.height))
        if (rot != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        return if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Rasm -> vektor (uzunligi 1). Asosiy oqimda chaqirmang. */
    fun embed(ctx: Context, bmp: Bitmap): FloatArray {
        val e = embedder(ctx)
        val res = synchronized(e) { e.embed(BitmapImageBuilder(bmp).build()) }
        val v = res.embeddingResult().embeddings()[0].floatEmbedding()
        var n = 0f
        for (x in v) n += x * x
        n = kotlin.math.sqrt(n).takeIf { it > 0f } ?: 1f
        return FloatArray(v.size) { v[it] / n }
    }

    // ------------------------------------------------------------ qidirish
    /** Eng o'xshash tovarlar (har tovar bir marta), o'xshashlik bo'yicha. */
    fun search(q: FloatArray, all: List<Product>, n: Int = 24): List<Pair<Product, Float>> {
        val byKey = all.associateBy { it.key }
        val byCode = all.filter { it.code.isNotBlank() }.groupBy { it.code }
        val best = HashMap<String, Float>()
        val prod = HashMap<String, Product>()
        for ((list, bonus) in listOf(server to 0f, learned to 0.03f)) {   // shu telefonda o'rgatilganlari biroz ustun
            for (e in list) {
                if (e.v.size != q.size) continue
                val p = byKey[e.key] ?: byCode[e.key.substringBefore('|')]?.singleOrNull() ?: continue
                var s = 0f
                for (i in q.indices) s += q[i] * e.v[i]
                s = s / 127f + bonus
                if (s > (best[p.key] ?: -9f)) { best[p.key] = s; prod[p.key] = p }
            }
        }
        return best.entries.sortedByDescending { it.value }.take(n).map { prod[it.key]!! to it.value }
    }

    // ------------------------------------------------------------ fayllar
    private fun serverFile(ctx: Context) = File(ctx.filesDir, "vectors.bin")
    private fun learnedFile(ctx: Context) = File(ctx.filesDir, "learned.bin")

    fun load(ctx: Context) {
        server = try { read(serverFile(ctx).readBytes()) } catch (e: Exception) { emptyList() }
        learned = try { read(learnedFile(ctx).readBytes()) } catch (e: Exception) { emptyList() }
    }

    /** Yangi vectors.bin bo'lsa yuklaydi (ETag bilan: o'zgarmagan bo'lsa yuklamaydi). */
    fun download(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("baza", Context.MODE_PRIVATE)
        val f = serverFile(ctx)
        val c = URL(VECTORS_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        val tag = prefs.getString("vectors_etag", null)
        if (tag != null && f.exists()) c.setRequestProperty("If-None-Match", tag)
        try {
            if (c.responseCode == 304) return false
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { it.readBytes() }
            val list = read(bytes)
            if (list.isEmpty()) throw Exception("bo'sh")
            val tmp = File(ctx.filesDir, "vectors.bin.tmp")
            tmp.writeBytes(bytes); tmp.renameTo(f)
            prefs.edit().putString("vectors_etag", c.getHeaderField("ETag")).apply()
            server = list
            return true
        } finally { c.disconnect() }
    }

    /** Do'konchi tasdiqlagan rasmni shu telefonda eslab qoladi. */
    @Synchronized
    fun learn(ctx: Context, p: Product, q: FloatArray) {
        val e = Entry(p.key, "local", ByteArray(q.size) { (q[it] * 127f).roundToInt().coerceIn(-127, 127).toByte() })
        val list = (learned + e).takeLast(MAX_LEARNED)
        learned = list
        try { learnedFile(ctx).writeBytes(write(list)) } catch (_: Exception) {}
    }

    fun read(b: ByteArray): List<Entry> {
        if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "TFV1") return emptyList()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val n = bb.int; val d = bb.int
        val out = ArrayList<Entry>(n)
        repeat(n) {
            val key = ByteArray(bb.short.toInt() and 0xFFFF).also { bb.get(it) }
            val url = ByteArray(bb.short.toInt() and 0xFFFF).also { bb.get(it) }
            val v = ByteArray(d).also { bb.get(it) }
            out += Entry(String(key, Charsets.UTF_8), String(url, Charsets.UTF_8), v)
        }
        return out
    }

    fun write(list: List<Entry>): ByteArray {
        val d = list.lastOrNull()?.v?.size ?: 0
        val ok = list.filter { it.v.size == d }
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        head.put("TFV1".toByteArray(Charsets.US_ASCII)); head.putInt(ok.size); head.putInt(d)
        out.write(head.array())
        for (e in ok) {
            for (s in listOf(e.key, e.url)) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                out.write(bytes.size and 0xFF); out.write((bytes.size shr 8) and 0xFF)
                out.write(bytes)
            }
            out.write(e.v)
        }
        return out.toByteArray()
    }
}
