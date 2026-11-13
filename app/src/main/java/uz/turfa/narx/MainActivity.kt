package uz.turfa.narx

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// ======================= SOZLAMALAR =======================
// Do'konchilar kiradigan PIN
const val SELLER_PIN = "1234"
// Baza shu manzildan yuklanadi (GitHub). Tannarx bu faylda YO'Q.
const val BASE_URL = "https://raw.githubusercontent.com/maxmuda725-jpg/turfa-narx/main/baza/products.csv"
// Billz'ga qoralama yuboradigan server (Do'kon Yordamchisi, Lovable). Kalit telefonga bir marta kiritiladi.
const val KASSA_API = "https://do-kon-savdo-yordamchisi.lovable.app"
// ==========================================================

/** "17600" -> [17600];  "11200-17600" -> [11200, 17600] */
fun nums(s: String): List<Long> =
    s.replace(" ", "").replace(" ", "").replace(",", ".")
        .split('-', '–', '/')
        .mapNotNull { it.filter { ch -> ch.isDigit() || ch == '.' }.toDoubleOrNull()?.toLong() }
        .filter { it > 0 }
        .distinct().sorted()

fun group(n: Long): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

fun fmt(n: Long): String = if (n <= 0) "—" else group(n) + " so'm"

fun priceText(v: List<Long>): String = when (v.size) {
    0 -> "—"
    1 -> fmt(v[0])
    else -> "${group(v.first())} / ${fmt(v.last())}"
}

data class Product(
    val name: String, val code: String, val barcode: String,
    val dona: String, val optom: String, val qty: String, val photo: String = "",
    val cat: String = "",   // GitHub'da hisoblangan "aqlli" kategoriya: "Kategoriya|Bo'lim"
    val photos: List<String> = emptyList(),   // hamma rasmlari: asosiysi + "rasmlar" ustunidagilar
    val added: String = "",   // bazaga qo'shilgan sana YYYY-MM-DD (🆕 Yangi kelganlar)
) {
    val nameLower = name.lowercase()
    val tokens: Set<String> = Search.tokens(name) + Search.tokens(code) + Search.tokens(barcode)
    val donaList = nums(dona)
    val optomList = nums(optom)
    val donaText = priceText(donaList)
    val optomText = priceText(optomList)
    val key = "$code|$name"

    /** Kassa uchun bitta narx. Ikki xil bo'lsa — kattasi. Optom yo'q bo'lsa — dona. */
    fun unitPrice(optomMode: Boolean): Long {
        val o = optomList.lastOrNull() ?: 0
        val d = donaList.lastOrNull() ?: 0
        return if (optomMode && o > 0) o else d
    }
    fun noOptom() = optomList.isEmpty()
}

class CartItem(val p: Product, var count: Long)

object Search {
    private val SEP = Regex("[\\s,;/|()\\[\\]{}\"'«»]+")

    fun tokens(s: String): Set<String> =
        s.lowercase().split(SEP)
            .map { it.trim('.', ':', '!', '?', '*', '#') }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Raqam qatnashgan so'z (masalan 43-25) — faqat AYNAN o'sha kod topiladi
     * (43-250, 43-2 chiqmaydi). Faqat harfli so'z (masalan "gul") — nom ichidan qidiriladi.
     * Bir nechta so'z yozilsa, hammasi mos kelishi kerak.
     */
    fun matches(p: Product, words: List<String>): Boolean =
        words.all { w ->
            if (w.any { it.isDigit() }) codeHit(p, w) > 0
            else p.nameLower.contains(w)
        }

    /**
     * 2 = aniq kod (43-25), 1 = kodning oxiri chiziqcha chegarasida (8-8 -> 25-11-8-8), 0 = yo'q.
     * 43-25 hech qachon 43-250 ni topmaydi.
     */
    fun codeHit(p: Product, w: String): Int = when {
        w in p.tokens -> 2
        w.contains('-') && p.tokens.any { it.endsWith("-$w") } -> 1
        else -> 0
    }

    /** "43-25" -> "43";  "25-7-44-7" -> "25-7-44";  defissiz kod -> null */
    fun series(w: String): String? {
        val i = w.lastIndexOf('-')
        return if (i > 0 && w.substring(0, i).any { it.isDigit() }) w.substring(0, i) else null
    }

    /** Aniq natijadan tashqari, shu seriyadagi tovarlar (avval qoldig'i borlari). */
    fun similar(all: List<Product>, query: String, exact: List<Product>): Pair<String, List<Product>> {
        val words = tokens(query).toList()
        val codeWord = words.lastOrNull { w -> w.any { it.isDigit() } && series(w) != null } ?: return "" to emptyList()
        val pre = series(codeWord)!! + "-"
        val others = words.filter { it != codeWord }
        val skip = exact.map { it.key }.toHashSet()
        // Faqat shu turdagi tovarlar: aniq topilgan tovar nomining birinchi so'zi (masalan "gul")
        val kinds = exact.map { firstWord(it.name) }.filter { it.isNotEmpty() }.toHashSet()
        val target = codeWord.substringAfterLast('-').toLongOrNull()
        fun seriesToken(p: Product) = p.tokens.firstOrNull { it.startsWith(pre) && it != codeWord }
        fun distance(p: Product): Long {
            val n = seriesToken(p)?.substringAfterLast('-')?.toLongOrNull() ?: return Long.MAX_VALUE
            return if (target == null) n else kotlin.math.abs(n - target)
        }
        val res = all.filter { p ->
            p.key !in skip && seriesToken(p) != null && matches(p, others) &&
                (kinds.isEmpty() || firstWord(p.name) in kinds)
        }.sortedWith(compareBy<Product>(
            { (it.qty.toDoubleOrNull() ?: 0.0) <= 0 },   // avval qoldig'i borlari
            { distance(it) },                            // keyin raqami eng yaqinlari (43-24, 43-26)
            { natKey(it.name) },
        ))
        return (pre + "…") to res.take(60)
    }

    private fun firstWord(s: String) = s.lowercase().trim().split(' ').firstOrNull().orEmpty()

    private fun natKey(s: String): String =
        Regex("\\d+").replace(s.lowercase()) { it.value.padStart(8, '0') }

    // ---------------------------------------------------- har xil yozilgan kodlar
    private val ODD_DASH = Regex("[–—−‐‑_]")
    // "43 39", "43.39", "43,39" (oldi-ortida chiziqcha yoki raqam bo'lmasa)
    private val SPACED = Regex("(?<![\\d-])\\d+(?:[ .,]+\\d+)+(?![\\d-])")
    private val SPACED_SEP = Regex("[ .,]+")
    private var codeSet: Set<String> = emptySet()
    private var codeSetOf: List<Product>? = null

    /** Bazadagi barcha raqamli kodlar (43-39, qai-68441, shtrix...). */
    @Synchronized
    private fun codes(all: List<Product>): Set<String> {
        if (codeSetOf !== all) {
            val set = HashSet<String>()
            for (p in all) for (t in p.tokens) if (t.any { it.isDigit() }) {
                set += t
                // chiziqcha chegarasidagi qo'shimchalar: "25-4-37-w05-15" -> "w05-15", "37-w05-15"...
                var i = t.indexOf('-')
                while (i >= 0) { val suf = t.substring(i + 1); if (suf.contains('-')) set += suf; i = t.indexOf('-', i + 1) }
            }
            codeSet = set
            codeSetOf = all
        }
        return codeSet
    }

    /** "43 39" -> "43-39", "43–39" -> "43-39" (faqat shunday kod bazada bo'lsa). */
    private fun normalize(query: String, codes: Set<String>): String {
        val q = ODD_DASH.replace(query, "-")
        return SPACED.replace(q) { m ->
            val j = m.value.split(SPACED_SEP).joinToString("-")
            if (j in codes) j else m.value
        }
    }

    /**
     * Bitta so'zning bazadagi variantlari: "4339" -> ["43-39"], "43.39" -> ["43-39"].
     * Aniq o'zi bazada bo'lsa — faqat o'zi. Hech narsa topilmasa — o'zi.
     */
    fun variants(w: String, codes: Set<String>): List<String> {
        if (w.none { it.isDigit() } || w in codes) return listOf(w)
        val d = w.replace('.', '-').replace(',', '-')
        if (d in codes) return listOf(d)
        if (d.length in 3..9 && d.all { it.isLetterOrDigit() } && d.any { it.isDigit() }) {
            val n = d.length - 1
            val res = ArrayList<String>()
            for (mask in 1 until (1 shl n)) {
                val sb = StringBuilder()
                for (i in d.indices) { sb.append(d[i]); if (i < n && (mask shr i) and 1 == 1) sb.append('-') }
                val c = sb.toString()
                if (c in codes) res += c
            }
            if (res.isNotEmpty()) return res.sortedBy { c -> c.count { it == '-' } }
        }
        return listOf(d)
    }

    /** Qidiruv so'zini bazadagi ko'rinishga keltiradi ("4339" -> "43-39"). */
    fun canonical(all: List<Product>, query: String): String {
        val codes = codes(all)
        return tokens(normalize(query, codes)).joinToString(" ") { variants(it, codes).first() }
    }

    /** 0 = omborda bor, 1 = noma'lum, 2 = tugagan */
    fun stockRank(p: Product): Int {
        val q = p.qty.toDoubleOrNull() ?: return 1
        return if (q > 0) 0 else 2
    }

    fun run(all: List<Product>, query: String): List<Product> {
        val codes = codes(all)
        val alts = tokens(normalize(query, codes)).map { variants(it, codes) }
        if (alts.isEmpty()) return emptyList()
        fun hit(p: Product, w: String) = if (w.any { it.isDigit() }) codeHit(p, w) else if (p.nameLower.contains(w)) 1 else 0
        return all.filter { p -> alts.all { a -> a.any { hit(p, it) > 0 } } }
            .sortedWith(compareBy<Product>(
                { p -> -alts.sumOf { a -> a.maxOf { w -> if (w.any { it.isDigit() }) hit(p, w) else 0 } } },  // aniq kodlar tepada
            ))   // tugaganlar pastga tushirilmaydi: Billz'da "tugagan" bo'lsa ham do'konda turgan bo'lishi mumkin
            .take(300)
    }
}

object Db {
    private const val FILE = "products.csv"

    fun load(ctx: Context): List<Product> {
        val f = File(ctx.filesDir, FILE)
        return try {
            if (f.exists()) f.inputStream().use { parse(it) }
            else ctx.assets.open(FILE).use { parse(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Internetdan yangi bazani yuklaydi. To'g'ri bo'lsa saqlaydi va sonini qaytaradi. */
    fun download(ctx: Context): Int {
        val c = URL(BASE_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { it.readBytes() }
            val list = parse(bytes.inputStream())
            if (list.isEmpty()) throw Exception("bo'sh fayl")
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(ctx.filesDir, FILE))
            ctx.getSharedPreferences("baza", Context.MODE_PRIVATE).edit()
                .putLong("updated", System.currentTimeMillis()).apply()
            return list.size
        } finally { c.disconnect() }
    }

    fun updatedAt(ctx: Context): Long =
        ctx.getSharedPreferences("baza", Context.MODE_PRIVATE).getLong("updated", 0)

    fun parse(input: InputStream): List<Product> {
        val text = input.bufferedReader(Charsets.UTF_8).readText().removePrefix("﻿")
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val first = lines[0]
        val delim = listOf(';', '\t', ',').maxByOrNull { d -> first.count { it == d } } ?: ';'
        val head = split(first, delim).map { it.lowercase().trim() }

        fun idx(vararg keys: String) = head.indexOfFirst { h -> keys.any { h.contains(it) } }
        var iName = idx("nom", "name", "mahsulot", "товар", "назв")
        var iCode = idx("kod", "sku", "artikul", "артикул", "код")
        var iDona = idx("dona", "chakana", "retail", "розн")
        var iOptom = idx("optom", "ulgurji", "wholesale", "опт")
        val iBar = idx("shtrix", "barkod", "barcode", "баркод")
        val iQty = idx("qoldiq", "soni", "количество", "qty")
        val iPhoto = head.indexOf("rasm").takeIf { it >= 0 } ?: idx("rasm", "photo", "image", "фото")
        val iPhotos = head.indexOf("rasmlar")
        val iCat = head.indexOf("kategoriya")
        val iAdded = head.indexOf("qoshilgan")

        val hasHeader = iName >= 0 && iDona >= 0
        if (!hasHeader) { iName = 0; iCode = 1; iDona = 2; iOptom = 3 }

        val out = ArrayList<Product>(lines.size)
        for (line in if (hasHeader) lines.drop(1) else lines) {
            val c = split(line, delim)
            val name = c.getOrNull(iName)?.trim().orEmpty()
            if (name.isEmpty()) continue
            out += Product(
                name = name,
                code = if (iCode >= 0) c.getOrNull(iCode)?.trim().orEmpty() else "",
                barcode = if (iBar >= 0) c.getOrNull(iBar)?.trim().orEmpty() else "",
                dona = c.getOrNull(iDona).orEmpty(),
                optom = if (iOptom >= 0) c.getOrNull(iOptom).orEmpty() else "",
                qty = if (iQty >= 0) c.getOrNull(iQty)?.trim().orEmpty() else "",
                photo = if (iPhoto >= 0) c.getOrNull(iPhoto)?.trim().orEmpty() else "",
                cat = if (iCat >= 0) c.getOrNull(iCat)?.trim().orEmpty() else "",
                added = if (iAdded >= 0) c.getOrNull(iAdded)?.trim().orEmpty() else "",
                photos = ((if (iPhoto >= 0) listOf(c.getOrNull(iPhoto)?.trim().orEmpty()) else emptyList()) +
                    (if (iPhotos >= 0) c.getOrNull(iPhotos).orEmpty().split(' ', '\n', '\t', ',') else emptyList()))
                    .map { it.trim() }.filter { it.startsWith("http") }.distinct(),
            )
        }
        return out
    }

    private fun split(line: String, d: Char): List<String> {
        val res = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == d && !q -> { res += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        res += sb.toString()
        return res
    }
}

/** Rasmni yuklaydi va telefonda saqlaydi: keyingi safar internetsiz chiqadi. */
object Img {
    private val pool = Executors.newFixedThreadPool(4)
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(k: String, b: Bitmap) = b.byteCount
    }
    private val ui = Handler(Looper.getMainLooper())

    private fun file(ctx: Context, url: String, big: Boolean) =
        File(File(ctx.cacheDir, "img").apply { mkdirs() }, (if (big) "b" else "s") + url.hashCode().toUInt())

    fun forget(ctx: Context, url: String) {
        mem.remove("s:$url"); mem.remove("b:$url")
        file(ctx, url, false).delete(); file(ctx, url, true).delete()
    }

    fun load(ctx: Context, url: String, view: ImageView, big: Boolean = false) {
        val key = (if (big) "b:" else "s:") + url
        view.tag = key
        mem.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        pool.execute {
            val bmp = try {
                val f = file(ctx, url, big)
                if (!f.exists()) {
                    val bytes = if (url.startsWith("file:")) File(url.removePrefix("file:")).readBytes() else {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 10000; c.readTimeout = 20000
                        c.instanceFollowRedirects = true
                        c.inputStream.use { it.readBytes() }.also { c.disconnect() }
                    }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    val target = if (big) 1200 else 240
                    var smp = 1
                    while (o.outWidth / (smp * 2) >= target && o.outHeight / (smp * 2) >= target) smp *= 2
                    val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = smp }) ?: return@execute
                    f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, if (big) 85 else 75, it) }
                    b
                } else BitmapFactory.decodeFile(f.path)
            } catch (e: Exception) { null } ?: return@execute
            mem.put(key, bmp)
            ui.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }
}

class MainActivity : Activity() {
    private val GREEN = Color.parseColor("#1E5F49")   // TURFA emblemasi yashili
    private val BLUE = Color.parseColor("#1F4E9E")
    private val ORANGE = Color.parseColor("#B26A00")
    private val RED = Color.parseColor("#C62828")
    private val INK = Color.parseColor("#1A1A1A")
    private val BG = Color.parseColor("#F4F5F7")

    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    private var all: List<Product> = emptyList()
    private var shown: List<Any> = emptyList()   // Product yoki String (bo'lim sarlavhasi)
    private var similarOpen = false
    private var lastQuery = ""

    // Kassa (faqat hisob-kitob, Billz'ga hech narsa yuborilmaydi)
    private val cart = LinkedHashMap<String, CartItem>()
    private var optomMode = false
    private var codeIndex: Map<String, List<Product>>? = null
    private var camUri: Uri? = null
    private val REQ_GALLERY = 71
    private val REQ_CAMERA = 72
    // Rasm orqali qidirish
    private val REQ_PHOTO_CAM = 73
    private val REQ_PHOTO_GAL = 74
    private val REQ_SHELF_CAM = 75
    private val REQ_SHELF_GAL = 76
    private val REQ_KOD_CAM = 77
    private var kodCamId: String? = null; private var kodCamBack = true
    private class ShelfObj(val bmp: Bitmap, val n: Int)
    private var shelfRows: List<Any>? = null   // polka rasmi natijasi (har buyum + mos tovarlar)
    private val REQ_TEACH = 75
    private var photoHits: List<Pair<Product, Float>>? = null
    private var photoVec: FloatArray? = null
    private val photoLearned = HashSet<String>()
    private var photoUri: Uri? = null          // qidirilgan rasm: tasdiqlansa hamma telefonga yuboriladi
    private val photoShared = HashSet<String>()
    private var teachFor: Product? = null
    // Tovarga rasm yuklash (hamma telefonga)
    private val REQ_UP_CAM = 76
    private val REQ_UP_GAL = 77
    private var upProduct: Product? = null
    private var upPending: File? = null
    private var upDone: ((Int) -> Unit)? = null   // kod ro'yxatidan kelinganda: yuklangach shu chaqiriladi
    private var upKodId: String? = null           // shu 🏷 qatoriga rasm nusxasi (kichik) saqlanadi
    private val upFiles = ArrayList<File>()
    // Sevimlilar (shu telefonda saqlanadi)
    private val favs = LinkedHashSet<String>()
    private var favMode = false
    // Kategoriya: (kategoriya, bo'lim yoki null = hammasi)
    private var catSel: Pair<String, String?>? = null
    // Rasmi yo'q tovarlar ko'rinishi (faqat ko'rsatish, kassa va kategoriyaga ta'sir qilmaydi)
    private var noPhotoMode = false
    // 📍 Polkalar: polkaView — ko'rish ("7-2" qavat, "7" stellaj, "?" joyi yo'qlar); polkaAdd — joylash rejimi (qaysi qavatga)
    private var polkaView: String? = null
    private var polkaAdd: String? = null
    private var photoScope: String? = null      // rasm qidiruv faqat shu stellaj ichida (keyingi qidiruv uchun)
    // Maxsus ro'yxatlar: "hot" — 🔥 ko'p sotiladi, "new" — 🆕 yangi kelgan; priceRange — 💰 narx oralig'i; altFor — 🔁 tugagan o'rniga
    private var special: String? = null
    private var priceRange: Pair<Long, Long>? = null
    private var altFor: Product? = null
    private lateinit var banner: View
    private lateinit var bannerImg: ImageView
    private lateinit var titleRow: View
    private lateinit var headView: View
    private lateinit var chipScroll: View
    private var searchOpen = false
    private var homeAnimated = false
    private val emblem: Bitmap? by lazy { try { assets.open("emblema.png").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null } }
    private lateinit var listView: ListView
    private var lastModeKey = ""
    private var photoScopeShown: String? = null // ko'rsatilayotgan natija qaysi stellajdan
    private var photoCodeDoubt = false   // o'qilgan kodli tovarlar rasmga umuman o'xshamaydi — kod pastga tushadi
    private var photoCodes: List<Pair<String, List<Product>>> = emptyList()   // rasmdan o'qilgan kod(lar) va shu kodli tovarlar

    private lateinit var searchScreen: View
    private lateinit var kassaScreen: View
    private lateinit var adapter: BaseAdapter
    private lateinit var cartAdapter: BaseAdapter
    private lateinit var info: TextView
    private lateinit var search: EditText
    private lateinit var refreshBtn: Button
    private lateinit var kassaBar: TextView
    private lateinit var totalText: TextView
    private lateinit var totalSub: TextView
    private lateinit var donaBtn: Button
    private lateinit var optomBtn: Button
    private lateinit var emptyCart: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        all = Db.load(this)
        favs += getSharedPreferences("sevimli", MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty()
        Thread { Visual.load(applicationContext) }.start()
        Cats.load(this)
        Cats.loadOverrides(this)
        Polka.load(this)
        Hot.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); fitsSystemWindows = true }
        val content = FrameLayout(this)
        searchScreen = buildSearch()
        kassaScreen = buildKassa().apply { visibility = View.GONE }
        content.addView(searchScreen, -1, -1)
        content.addView(kassaScreen, -1, -1)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildNav())
        setContentView(root)
        navMark(false)
        refresh(); updateCart()
        login { update(silent = true); checkAppUpdate(silent = true) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            kassaScreen.visibility == View.VISIBLE -> showKassa(false)
            photoHits != null -> closePhoto()
            shelfRows != null -> { shelfRows = null; refresh() }
            polkaAdd != null -> polkaAddMenu()
            polkaView != null -> { polkaView = null; refresh() }
            special != null || priceRange != null || altFor != null -> { clearSpecial(); refresh() }
            searchOpen && search.text.isBlank() -> { searchOpen = false; hideKb(search); refresh() }
            favMode -> { favMode = false; refresh() }
            catSel != null -> { catSel = null; refresh() }
            noPhotoMode -> { noPhotoMode = false; refresh() }
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- yordamchilar
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun box(color: Int, radius: Int = 12) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun tint(c: Int, a: Int = 28) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun hideKb(v: View) =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)

    private fun pill(text: String, fg: Int, bg: Int, size: Float = 15f, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text; textSize = size; isAllCaps = false
            setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
            background = box(bg, 22); minWidth = 0; minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onClick() }
        }

    private fun topBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(10), dp(10))
        setBackgroundColor(GREEN)
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
    }

    // ---------------------------------------------------------------- qidiruv ekrani (yangi dizayn)
    private val LINE = Color.parseColor("#E3E6EA")
    private val MUTED = Color.parseColor("#5A6270")
    private val CHIP = Color.parseColor("#EEF0F3")
    private lateinit var subTitle: TextView
    private lateinit var clearBtn: TextView
    private lateinit var chipRow: LinearLayout
    private var chipSig = ""
    private val navItems = ArrayList<TextView>()

    /** Ro'yxatdagi maxsus qatorlar: bosh ekran plitkalari va rasm natijasining 1-o'rni. */
    private object HomeTiles
    private class TopHit(val p: Product)

    private fun buildSearch(): View {
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Bosh ekranda: do'kon rasmi (banner, tiniq — ustiga hech narsa yozilmaydi)
        banner = FrameLayout(this).apply { setBackgroundColor(GREEN) }
        bannerImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "TUR-FA do'koni"
            Fon.bitmap(this@MainActivity)?.let { setImageBitmap(it) }
            setOnClickListener { if (isOwner()) ownerMenu() }
        }
        (banner as FrameLayout).addView(bannerImg, FrameLayout.LayoutParams(-1, -1))
        screen.addView(banner, LinearLayout.LayoutParams(-1, dp(170)))
        banner.visibility = View.GONE

        // Yashil sarlavha: nom + baza holati + 🔄, ostida qidiruv va 📷
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(GREEN)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "TUR-FA narxlar"; textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { if (isOwner()) ownerMenu() }
        })
        subTitle = TextView(this).apply { textSize = 12f; setTextColor(Color.argb(220, 255, 255, 255)) }
        titles.addView(subTitle)
        row1.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        refreshBtn = pill("🔄", Color.WHITE, Color.argb(46, 255, 255, 255), 18f) { update(silent = false); checkAppUpdate(silent = true); verifyAccess() }
            .apply { setOnLongClickListener { checkAppUpdate(silent = false); true }; contentDescription = "Bazani yangilash" }
        row1.addView(refreshBtn, LinearLayout.LayoutParams(dp(48), dp(44)))
        head.addView(row1)
        titleRow = row1

        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = box(Color.WHITE, 14); setPadding(dp(12), 0, dp(6), 0)
        }
        field.addView(TextView(this).apply { text = "🔍"; textSize = 17f }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        search = EditText(this).apply {
            hint = "Kod yoki nom: 43-25, vaza…"; textSize = 18f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = null; setPadding(0, dp(12), 0, dp(12)); setTextColor(INK)
        }
        field.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        clearBtn = TextView(this).apply {
            text = "✕"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(MUTED)
            background = box(LINE, 14); visibility = View.GONE; contentDescription = "Tozalash"
            setOnClickListener { search.setText("") }
        }
        field.addView(clearBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        searchRow.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchRow.addView(pill("📷", INK, Color.WHITE, 22f) { photoMenu() }.apply { contentDescription = "Rasm bilan qidirish" },
            LinearLayout.LayoutParams(dp(54), dp(52)).apply { leftMargin = dp(8) })
        head.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        screen.addView(head)
        headView = head

        // Kategoriya chiplari (yonga suriladi)
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), dp(12), dp(4)) }
        chipScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(chipRow)
        }
        screen.addView(chipScroll)

        info = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setPadding(dp(16), dp(4), dp(16), dp(2)) }
        screen.addView(info)

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View =
                when (val r = shown[p]) {
                    is Product -> productRow(r)
                    is TopHit -> productRow(r.p, top = true)
                    is ShelfObj -> shelfObjView(r)
                    HomeTiles -> homeView()
                    else -> sectionHeader(r as String)
                }
        }
        val list = ListView(this).apply {
            listView = this
            this.adapter = this@MainActivity.adapter
            divider = null; clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(12))
            setOnTouchListener { v, _ -> hideKb(v); false }
        }
        screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        kassaBar = TextView(this).apply {
            textSize = 14f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            background = box(CHIP, 14)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { showKassa(true) }
        }
        screen.addView(kassaBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                refresh()
            }
        })
        return screen
    }

    /** Pastki menyu: 🔍 Qidiruv · 🗂 Kategoriya · 📍 Polka · 🏷 Kod · ☰ Yana (kassa — Yana ichida va kartadagi 🧮) */
    private fun buildNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        fun item(icon: String, label: String, onClick: () -> Unit) {
            val t = TextView(this).apply {
                text = "$icon\n$label"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(MUTED)
                typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(6), 0, dp(6))
                setLineSpacing(dp(2).toFloat(), 1f)
                setOnClickListener { onClick() }
            }
            navItems += t
            nav.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        item("🔍", "Qidiruv") { goHome() }
        item("🗂", "Kategoriya") { showKassa(false); catMenu() }
        item("📍", "Polka") { showKassa(false); polkaMenu() }
        item("🏷", "Kod") { showKassa(false); kodList() }
        item("☰", "Yana") { moreMenu() }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
        wrap.addView(nav)
        return wrap
    }

    private fun navMark(kassa: Boolean) {
        navItems.forEachIndexed { i, t -> t.setTextColor(if (i == 0 && !kassa) GREEN else if (i == 3) RED else MUTED) }
        navKod()
    }

    private fun navKod() {
        val n = try { kodLoad().size } catch (_: Exception) { 0 }
        if (navItems.size > 3) navItems[3].text = if (n > 0) "🏷\nKod · $n" else "🏷\nKod"
    }

    /** Hamma rejimlarni yopib, toza qidiruvga qaytaradi (polkaga joylash rejimi saqlanadi). */
    private fun goHome() {
        photoHits = null; photoVec = null; photoUri = null
        favMode = false; catSel = null; noPhotoMode = false; polkaView = null; clearSpecial(); searchOpen = false
        showKassa(false)
        if (search.text.isNotEmpty()) search.setText("") else refresh()
    }

    private fun moreMenu() {
        val items = arrayListOf<Pair<String, () -> Unit>>(
            "🧮 Kassa (hisoblash)" to { showKassa(true) },
            "⭐ Sevimlilar" to { showKassa(false); if (!favMode) toggleFavMode() },
            "🏷 Kod chiqarish ro'yxati" to { kodList() },
            "📸 Tovarga rasm yuklash" to { uploadStart() },
            "🚫 Rasmi yo'q tovarlar" to { showKassa(false); openNoPhoto() },
            "🖼 Yuklangan rasmlar" to { rasmlarList() },
            "📁 Rasmlar papkasi (ZIP)" to { zipMenu() },
            "📋 Ro'yxatni rasmdan o'qish" to { scanMenu() },
            "🔄 Bazani yangilash" to { update(silent = false); verifyAccess() },
            "⬆️ Ilovani yangilash" to { checkAppUpdate(silent = false) },
        )
        if (isOwner()) items += "👤 Egasi menyusi" to { ownerMenu() }
        AlertDialog.Builder(this).setTitle("☰ Yana")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    /** Rasm bilan qidirish: kamera yoki galereya (to'liq menyu — 📷 tugmasida). */
    private fun photoPick() {
        AlertDialog.Builder(this).setTitle("📷 Rasm bilan topish")
            .setItems(arrayOf("📷 Kamera bilan", "🖼 Galereyadan")) { _, w ->
                if (w == 0) openCamera(REQ_PHOTO_CAM) else openGallery(REQ_PHOTO_GAL)
            }.show()
    }

    /** Kategoriya chiplari: faqat tovari bor kategoriyalar; tanlangani yashil. */
    private fun fillChips() {
        val sig = "${all.size}|${catSel?.first}|${Cats.cats.size}"
        if (sig == chipSig) return
        chipSig = sig
        chipRow.removeAllViews()
        val counts = all.groupingBy { Cats.of(it).first }.eachCount()
        for ((c, _) in Cats.order()) {
            val n = counts[c] ?: continue
            val on = catSel?.first == c
            chipRow.addView(TextView(this).apply {
                text = c; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(if (on) Color.WHITE else INK)
                background = GradientDrawable().apply {
                    setColor(if (on) GREEN else Color.WHITE); cornerRadius = dp(18).toFloat()
                    if (!on) setStroke(dp(1), Color.parseColor("#D5D9DE"))
                }
                setPadding(dp(14), 0, dp(14), 0)
                contentDescription = "$c, $n ta"
                setOnClickListener { if (on) { catSel = null; refresh() } else openCat(c, null) }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(8) })
        }
    }

    /** Bosh ekran — "profil": do'kon rasmi muqova, emblema avatar, raqamlar, katta tugmalar ro'yxati. */
    private fun homeView(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        val anim = !homeAnimated; homeAnimated = true
        var delay = 0L
        fun pop(v: View): View {
            if (anim) { v.alpha = 0f; v.translationY = dp(18).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(380)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start(); delay += 55 }
            return v
        }
        fun press(v: View) = v.setOnTouchListener { x, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }

        // Muqova + emblema
        val top = FrameLayout(this)
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "TUR-FA do'koni"
            Fon.bitmap(this@MainActivity)?.let { setImageBitmap(it) } ?: setBackgroundColor(GREEN)
            background = box(GREEN, 22); clipToOutline = true
            setOnClickListener { if (isOwner()) ownerMenu() }
        }
        top.addView(cover, FrameLayout.LayoutParams(-1, dp(200)).apply { setMargins(0, dp(4), 0, 0) })
        val av = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "TUR-FA emblemasi"
            emblem?.let { setImageBitmap(it) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN); setStroke(dp(5), BG) }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            elevation = dp(4).toFloat()
        }
        top.addView(av, FrameLayout.LayoutParams(dp(108), dp(108), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(150) })
        if (anim) { av.scaleX = 0.6f; av.scaleY = 0.6f; av.alpha = 0f
            av.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start() }
        wrap.addView(top, LinearLayout.LayoutParams(-1, dp(262)))

        wrap.addView(pop(TextView(this).apply {
            text = "TUR-FA"; textSize = 26f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }))
        wrap.addView(pop(TextView(this).apply {
            text = "sovg'alar · gullar · o'yinchoqlar"; textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER
        }))

        // Raqamlar
        val (noPhoto, _) = homeStats()
        val newN = all.count { isNew(it) }
        val kodN = try { kodLoad().size } catch (_: Exception) { 0 }
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = box(Color.WHITE, 18); setPadding(0, dp(12), 0, dp(12))
        }
        fun stat(n: String, label: String, color: Int, act: () -> Unit) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { act() } }
            c.addView(TextView(this).apply { text = n; textSize = 20f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
            c.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER })
            if (stats.childCount > 0) stats.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(dp(1), -1))
            stats.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        stat(group(all.size.toLong()), "tovar", INK) { catMenu() }
        stat(group(newN.toLong()), "yangi keldi", RED) { openSpecial("new") }
        stat(group(kodN.toLong()), "kod ro'yxatda", GREEN) { kodList() }
        wrap.addView(pop(stats), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(16), dp(8), 0) })

        // Qidiruv qatori (bosilsa — klaviatura bilan qidiruv)
        val sbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = box(Color.WHITE, 18); setPadding(dp(16), 0, dp(6), 0)
            setOnClickListener { openSearch() }; contentDescription = "Kod yoki nom yozing"
        }
        sbar.addView(TextView(this).apply { text = "🔍  Kod yoki nom yozing…"; textSize = 16f; setTextColor(MUTED) }, LinearLayout.LayoutParams(0, -2, 1f))
        sbar.addView(pill("📷", Color.WHITE, GREEN, 20f) { photoPick() }.apply { contentDescription = "Rasm bilan qidirish" }, LinearLayout.LayoutParams(dp(50), dp(44)))
        wrap.addView(pop(sbar), LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(8), dp(12), dp(8), 0) })

        // Asosiy ro'yxat (Telegram sozlamalari kabi)
        data class Row(val icon: String, val color: Int, val title: String, val sub: String, val badge: String, val act: () -> Unit)
        val groups = listOf(
            listOf(
                Row("📷", GREEN, "Rasm bilan topish", "Tovar yoki yozilgan kod", "") { photoPick() },
                Row("🏷", RED, "Kod chiqarish", "Yorliq ro'yxati", if (kodN > 0) "$kodN" else "") { kodList() },
                Row("💰", Color.parseColor("#F59E0B"), "Puliga mos tovar", "Mijoz: «100 mingga nima bor?»", "") { priceMenu() },
            ),
            listOf(
                Row("🔥", Color.parseColor("#EA580C"), "Ko'p sotiladi", "Oxirgi 30 kun", "") { openSpecial("hot") },
                Row("🎁", Color.parseColor("#DB2777"), "Yangi kelgan", "Oxirgi 20 kun", if (newN > 0) "$newN" else "") { openSpecial("new") },
                Row("📍", Color.parseColor("#7C3AED"), "Polkalar", "Qaysi tovar qayerda", "") { polkaMenu() },
                Row("⭐", Color.parseColor("#CA8A04"), "Sevimlilar", if (favs.isEmpty()) "Tez topiladiganlar" else "${favs.size} ta", "") { toggleFavMode() },
                Row("🚫", Color.parseColor("#64748B"), "Rasmi yo'qlar", "${group(noPhoto.toLong())} ta — rasmga oling", "") { openNoPhoto() },
            ),
        )
        for (g in groups) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = box(Color.WHITE, 18); clipToOutline = true }
            g.forEachIndexed { i, r ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12)); isClickable = true; isFocusable = true
                    setOnClickListener { r.act() }; contentDescription = r.title
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    foreground = getDrawable(tv.resourceId)
                }
                press(row)
                row.addView(TextView(this).apply {
                    text = r.icon; textSize = 19f; gravity = Gravity.CENTER; background = box(r.color, 12)
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply { text = r.title; textSize = 16f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD })
                col.addView(TextView(this).apply { text = r.sub; textSize = 12f; setTextColor(if (r.icon == "🚫") RED else MUTED) })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                if (r.badge.isNotEmpty()) row.addView(TextView(this).apply {
                    text = r.badge; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                    background = box(r.color, 11); setPadding(dp(9), dp(2), dp(9), dp(2))
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
                row.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(Color.parseColor("#B0B6BE")) })
                card.addView(pop(row))
                if (i < g.lastIndex) card.addView(View(this).apply { setBackgroundColor(Color.parseColor("#F1F2F4")) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(68) })
            }
            wrap.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(12), dp(8), 0) })
        }
        return wrap
    }

    // ---------------------------------------------------------------- 💰 🔥 🆕 🔁 maxsus ro'yxatlar
    /** Bosh ekran (profil) ochiq bo'lsa — yashil sarlavha, chiplar yashiriladi; profil ro'yxat ichida. */
    private fun setBanner(on: Boolean) {
        val v = if (on) View.GONE else View.VISIBLE
        if (headView.visibility != v) headView.visibility = v
        if (chipScroll.visibility != v) chipScroll.visibility = v
        if (!on) homeAnimated = false
    }

    /** Profil ekranidagi qidiruv qatori bosilganda: sarlavha chiqadi, klaviatura ochiladi. */
    private fun openSearch() {
        searchOpen = true; refresh()
        search.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private val today: String
        get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Tashkent") }.format(java.util.Date())

    private var newSince = ""
    /** Oxirgi 20 kunda bazaga qo'shilgan. */
    private fun isNew(p: Product): Boolean {
        if (newSince.isEmpty()) newSince = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Tashkent") }
            .format(java.util.Date(System.currentTimeMillis() - 20L * 86_400_000))
        return p.added.length == 10 && p.added >= newSince && p.added > "2000-01-01"
    }

    private fun price(p: Product): Long = p.donaList.maxOrNull() ?: 0

    /** Qidiruv narxmi? "30000", "30 000", "30.000", "30k", "30 ming", "1,5 mln" -> so'm. Kod (4339, 43-39) narx emas. */
    private fun priceQuery(q: String): Long? {
        val t = q.trim().lowercase().replace('\u00A0', ' ')
        Regex("^(\\d+(?:[.,]\\d+)?)\\s*(k|ming|минг|тыс|mln|million|млн)\\.?$").find(t)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val mul = if (m.groupValues[2] in listOf("mln", "million", "млн")) 1_000_000 else 1000
            return (n * mul).toLong().takeIf { it >= 1000 }
        }
        if (!Regex("^\\d{1,3}([ .,]?\\d{3})+$").matches(t)) return null
        val v = t.filter { it.isDigit() }.toLongOrNull() ?: return null
        return v.takeIf { it >= 1000 && it % 500 == 0L }   // 4339 kabi kodlar narx emas
    }

    private fun openSpecial(kind: String?, range: Pair<Long, Long>? = null, alt: Product? = null) {
        special = kind; priceRange = range; altFor = alt
        favMode = false; noPhotoMode = false; polkaView = null; photoHits = null; photoVec = null
        if (range == null) catSel = null   // narx oralig'ida tanlangan kategoriya saqlanadi
        showKassa(false)
        hideKb(search)
        if (search.text.isNotEmpty()) search.setText("") else refresh()
    }

    /** -> (sarlavha, tovarlar) */
    private fun specialList(): Pair<String, List<Product>> {
        altFor?.let { p ->
            return "🔁 «${p.name}» o'rniga (do'konda bor)" to alternatives(p)
        }
        priceRange?.let { (lo, hi) ->
            val cat = catSel
            val list = all.filter { Search.stockRank(it) == 0 && price(it) in lo..hi &&
                (cat == null || Cats.of(it).let { c -> c.first == cat.first && (cat.second == null || c.second == cat.second) }) }
                .sortedWith(compareBy<Product>({ if (Hot.isHot(it)) 0 else 1 }, { price(it) }))
            return "💰 ${priceLabel(lo, hi)}lik tovarlar" + (cat?.let { " · ${it.second ?: it.first}" } ?: "") to list
        }
        return when (special) {
            "hot" -> {
                val byCode = all.groupBy { it.code }
                "🔥 Ko'p sotiladi (30 kun)" to Hot.codes().flatMap { byCode[it].orEmpty() }.distinctBy { it.key }
            }
            else -> "🆕 Yangi kelgan (20 kun)" to all.filter { isNew(it) }
                .sortedWith(compareBy<Product> { Search.stockRank(it) }.thenByDescending { it.added })
        }
    }

    private fun priceLabel(lo: Long, hi: Long): String = when {
        lo <= 0 -> "${group(hi / 1000)} ming so'mgacha"
        hi >= Long.MAX_VALUE / 2 -> "${group(lo / 1000)} ming so'mdan qimmat"
        else -> "${group(lo / 1000)} – ${group(hi / 1000)} ming so'm"
    }

    /** Mijoz byudjeti: narx oralig'i (faqat do'konda borlari; kategoriya tanlangan bo'lsa — o'sha ichida). */
    private fun priceMenu() {
        val opts = listOf(0L to 50_000L, 50_000L to 100_000L, 100_000L to 200_000L, 200_000L to 300_000L,
            300_000L to 500_000L, 500_000L to Long.MAX_VALUE)
        val labels = opts.map { "💰 " + priceLabel(it.first, it.second) } + "✏️ Boshqa summa yozaman"
        val cat = catSel
        AlertDialog.Builder(this).setTitle("Mijoz qancha pul aytdi?" + (cat?.let { "\n(faqat ${it.second ?: it.first})" } ?: ""))
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < opts.size) openSpecial(null, opts[i]) else priceCustom()
            }.show()
    }

    private fun priceCustom() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(20), dp(8), dp(20), 0); gravity = Gravity.CENTER_VERTICAL }
        fun field(h: String) = EditText(this).apply { hint = h; inputType = InputType.TYPE_CLASS_NUMBER; textSize = 20f; gravity = Gravity.CENTER }
        val a = field("50"); val b = field("150")
        row.addView(a, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = " – "; textSize = 20f })
        row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = " ming"; textSize = 16f })
        AlertDialog.Builder(this).setTitle("💰 Necha puldan necha pulgacha?\n(ming so'mda yozing: 50 – 150)").setView(row)
            .setPositiveButton("Ko'rsat") { _, _ ->
                val lo = (a.text.toString().toLongOrNull() ?: 0) * 1000
                val hi = b.text.toString().toLongOrNull()?.times(1000) ?: Long.MAX_VALUE
                if (hi < lo) toast("Birinchi son kichikroq bo'lishi kerak") else openSpecial(null, lo to hi)
            }.setNegativeButton("Bekor", null).show()
    }

    /** Tugagan tovar o'rniga: o'sha kategoriya, narxi yaqin, do'konda bor; bir bo'limdagilari va 🔥 ko'p sotiladiganlar oldinda. */
    private fun alternatives(p: Product): List<Product> {
        val (c, sub) = Cats.of(p)
        val pr = price(p).coerceAtLeast(1)
        return all.asSequence()
            .filter { it.key != p.key && Search.stockRank(it) == 0 && Cats.of(it).first == c && price(it) > 0 }
            .filter { kotlin.math.abs(price(it) - pr).toDouble() / pr <= 0.5 }
            .sortedWith(compareBy<Product>(
                { if (sub != null && Cats.of(it).second == sub) 0 else 1 },
                { kotlin.math.abs(price(it) - pr).toDouble() / pr > 0.2 },
                { if (Hot.isHot(it)) 0 else 1 },
                { kotlin.math.abs(price(it) - pr) },
            ))
            .take(30).toList()
    }

    // Bosh ekran raqamlari: baza o'zgarganda bir marta hisoblanadi (har chizishda emas — sekin telefonda qotmasin)
    private var homeStatsKey: Any? = null
    private var homeStatsVal = 0 to 0
    private fun homeStats(): Pair<Int, Int> {
        if (homeStatsKey !== all) {
            homeStatsVal = all.count { it.photo.isBlank() } to all.mapTo(HashSet()) { Cats.of(it).first }.size
            homeStatsKey = all
        }
        return homeStatsVal
    }

    private fun badge(t: String, fg: Int, bg: Int) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
        background = box(bg, 8); setPadding(dp(8), dp(3), dp(8), dp(3))
        isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun productRow(p: Product, top: Boolean = false): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (Search.stockRank(p) == 2) Color.parseColor("#E9EAEC") else Color.WHITE)   // tugagan — kulrang (kassaga baribir qo'shiladi)
                cornerRadius = dp(18).toFloat()
                if (top) setStroke(dp(2), GREEN)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        if (top) card.addView(badge("1-o'rin · eng o'xshash", Color.WHITE, GREEN),
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pics = allPhotos(p)
        val photoUrl = pics.firstOrNull().orEmpty()
        if (photoUrl.isNotBlank()) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = box(Color.parseColor("#E9EEF2"), 14)
                clipToOutline = true
                setOnClickListener { showPhoto(p, 0) }
                contentDescription = if (pics.size > 1) "Tovar rasmi, ${pics.size} ta" else "Tovar rasmi"
            }
            Img.load(this, photoUrl, iv)
            head.addView(iv, LinearLayout.LayoutParams(dp(92), dp(92)).apply { rightMargin = dp(12) })
        } else {
            head.addView(TextView(this).apply {
                text = Cats.of(p).first.substringBefore(' ').ifBlank { "📦" }; textSize = 34f; gravity = Gravity.CENTER
                background = box(Color.parseColor("#E9EEF2"), 14); alpha = 0.75f
                contentDescription = "Rasmi yo'q"
            }, LinearLayout.LayoutParams(dp(92), dp(92)).apply { rightMargin = dp(12) })
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val fav = p.key in favs
        head.addView(TextView(this).apply {
            text = if (fav) "★" else "☆"; textSize = 26f; gravity = Gravity.CENTER
            setTextColor(if (fav) Color.parseColor("#F2A900") else Color.parseColor("#B8BEC6"))
            setOnClickListener { toggleFav(p) }
            contentDescription = if (fav) "Sevimlilardan olib tashlash" else "Sevimlilarga qo'shish"
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(head)
        if (pics.size > 1) {
            // Hamma rasmlari: kichik ko'rinishda, bosilsa kattalashadi
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pics.forEachIndexed { i, u ->
                strip.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = box(Color.parseColor("#E9EEF2"), 10); clipToOutline = true
                    contentDescription = "Rasm ${i + 1}"
                    setOnClickListener { showPhoto(p, i) }
                    Img.load(this@MainActivity, u, this)
                }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(6) })
            }
            strip.addView(TextView(this).apply {
                text = "📷 ${pics.size} ta"; textSize = 13f; setTextColor(MUTED); typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(-2, dp(56)))
            card.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; addView(strip)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        titleBox.addView(TextView(this).apply {
            text = p.name; textSize = 17f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
        })
        titleBox.addView(TextView(this).apply { text = p.code; textSize = 12f; setTextColor(MUTED) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        val marks = listOfNotNull(if (Hot.isHot(p)) "🔥 Ko'p sotiladi" else null, if (isNew(p)) "🆕 Yangi" else null)
        if (marks.isNotEmpty()) titleBox.addView(TextView(this).apply {
            text = marks.joinToString("  ·  "); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#B54708"))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        val badges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (p.qty.isNotBlank()) {
            val n = p.qty.toDoubleOrNull()?.toLong() ?: 0
            // Qoldiq SONI faqat egasiga; sotuvchilar faqat "Bor" / "Tugagan" ni ko'radi
            badges.addView(if (n > 0) badge(if (isOwner()) "✅ Bor · ${group(n)} ta" else "✅ Bor", Color.parseColor("#0B6B52"), Color.parseColor("#E3F3EE"))
                else badge("❌ Tugagan", Color.parseColor("#B42318"), Color.parseColor("#FDECEA")),
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        val locs = Polka.of(p)
        if (locs.isNotEmpty()) badges.addView(badge("📍 " + Polka.labels(locs), BLUE, Color.parseColor("#E6EDF8")).apply {
            setOnClickListener { openPolka(locs[0]) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (badges.childCount > 0) titleBox.addView(badges, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val prices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prices.addView(priceBox("DONA", p.donaText, p.donaList.size > 1, Color.parseColor("#0B6B52")),
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        prices.addView(priceBox("OPTOM", p.optomText, p.optomList.size > 1, BLUE),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        card.addView(prices, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (p.donaList.size > 1 || p.optomList.size > 1) card.addView(TextView(this).apply {
            text = "Do'konga qarab farq qiladi, kassadan aniqlang"
            textSize = 12f; setTextColor(ORANGE); setPadding(0, dp(6), 0, 0)
        })

        // Rasm natijasining 1-o'rni: "✅ Shu tovar" darrov ko'rinadi
        if (top && photoVec != null && p.key !in photoShared) card.addView(
            pill(if (p.key in photoLearned) "📤 Shu rasmni hamma telefonga o'rgatish" else "✅ Shu tovar (hamma telefonga o'rgat)", Color.WHITE, GREEN, 15f) {
                rememberPhoto(p); sharePhoto(p)
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        // Tugagan: o'rniga do'konda bor o'xshashini taklif qilish
        if (Search.stockRank(p) == 2 && altFor?.key != p.key) card.addView(
            pill("🔁 O'rniga boshqasini ko'rsat (do'konda bor)", GREEN, tint(GREEN, 26), 15f) { openSpecial(null, null, p) },
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })

        // Amallar: ⋯ · 🧮 (kassa — faqat hisoblash) · 🏷 Kodga qo'shish (asosiy)
        val inCart = cart[p.key]?.count ?: 0
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addRow.addView(pill("⋯", INK, CHIP, 22f) { productMenu(p) }.apply { contentDescription = "Boshqa amallar" },
            LinearLayout.LayoutParams(dp(52), dp(48)).apply { rightMargin = dp(8) })
        if (p.donaList.isNotEmpty()) addRow.addView(
            pill(if (inCart > 0) "🧮${group(inCart)}" else "🧮", INK, CHIP, 17f) {
                val ci = cart.getOrPut(p.key) { CartItem(p, 0) }
                ci.count += 1
                rememberPhoto(p)
                toast("🧮 Hisobga qo'shildi: ${group(ci.count)} ta")
                updateCart(); adapter.notifyDataSetChanged()
            }.apply { contentDescription = "Hisobga (kassaga) qo'shish" },
            LinearLayout.LayoutParams(dp(if (inCart > 0) 72 else 52), dp(48)).apply { rightMargin = dp(8) })
        addRow.addView(pill("🏷  Kodga qo'shish", Color.WHITE, RED, 16f) { kodAddProduct(p) }.apply {
            contentDescription = "Kod chiqarish ro'yxatiga qo'shish"
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(addRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        polkaAdd?.let { target ->
            val here = target in locs
            card.addView(pill(if (here) "✓ ${Polka.label(target)} da — olib tashlash" else "📍 ${Polka.label(target)} ga qo'yish",
                if (here) BLUE else Color.WHITE, if (here) tint(BLUE, 30) else BLUE, 16f) { polkaPlace(p, target, remove = here) },
                LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        }

        card.setOnLongClickListener { productMenu(p); true }

        val wrap = LinearLayout(this).apply { setPadding(dp(4), dp(5), dp(4), dp(5)) }
        wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun sectionHeader(t: String): View {
        val tv = TextView(this).apply {
            text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (t.startsWith("🔍")) BLUE else INK)
            setPadding(dp(12), dp(14), dp(12), dp(8))
            if (t.startsWith("🚫")) {
                background = box(tint(RED, 22), 12)
                setOnClickListener { noPhotoMode = false; refresh() }
            }
            if (t.startsWith("🗂")) {
                background = box(tint(BLUE, 22), 12)
                setOnClickListener { catSel = null; refresh() }
            }
            if (t.startsWith("⭐")) {
                background = box(tint(Color.parseColor("#F2A900"), 34), 12)
                setOnClickListener { favMode = false; refresh() }
            }
            if (t.startsWith("💰") || t.startsWith("🔥") || t.startsWith("🆕") || t.startsWith("🔁")) {
                background = box(tint(GREEN, 30), 12); setTextColor(GREEN)
                setOnClickListener { clearSpecial(); refresh() }
            }
            if (t.startsWith("🗄")) {
                background = box(tint(BLUE, 26), 12); setTextColor(BLUE)
                setOnClickListener { shelfRows = null; refresh() }
            }
            if (t.startsWith("📍") || t.startsWith("❓")) {
                background = box(tint(BLUE, 30), 12)
                setOnClickListener { if (polkaAdd != null) polkaAddMenu() else { polkaView = null; refresh() } }
            }
            if (t.startsWith("↔️")) {
                background = box(Color.parseColor("#FFF3DC"), 12); setTextColor(Color.parseColor("#8A5200"))
            }
            if (t == "Boshqa o'xshashlari" || t == "Rasmga o'xshashlari") { setTextColor(MUTED); textSize = 14f }
            if (t.startsWith("🔤")) { background = box(tint(GREEN, 30), 12); setTextColor(GREEN) }
            if (t.startsWith("📷")) {
                background = box(tint(GREEN, 22), 12)
                setOnClickListener { closePhoto() }
            }
            if (t.startsWith("🔍")) {
                background = box(tint(BLUE, 22), 12)
                setOnClickListener { similarOpen = !similarOpen; refresh() }
            }
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(4), dp(6), dp(4), dp(2)) }
        wrap.addView(tv, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun priceBox(label0: String, v: String, two: Boolean, color: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(tint(color, 22), 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(context).apply {
                text = if (two) "$label0 · ⚠️ 2 xil narx" else label0
                textSize = 11f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.04f
            })
            addView(TextView(context).apply {
                text = v; textSize = if (two) 16f else 21f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            })
        }

    // ---------------------------------------------------------------- kassa ekrani
    private fun buildKassa(): View {
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        val top = topBar()
        top.addView(pill("← Orqaga", GREEN, Color.WHITE) { showKassa(false) }, LinearLayout.LayoutParams(-2, dp(44)))
        top.addView(title("  Kassa").apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(pill("Tozalash", RED, Color.WHITE) { clearCart() }, LinearLayout.LayoutParams(-2, dp(44)))
        screen.addView(top)

        // DONA / OPTOM almashtirgich
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(Color.WHITE, 26)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        donaBtn = pill("DONA narx", Color.WHITE, GREEN, 17f) { setMode(false) }
        optomBtn = pill("OPTOM narx", BLUE, Color.WHITE, 17f) { setMode(true) }
        modeRow.addView(donaBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        modeRow.addView(optomBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        screen.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(12), dp(12), dp(6)) })
        screen.addView(pill("📷 Ro'yxatni rasmdan qo'shish", INK, Color.WHITE, 16f) { scanMenu() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(12), dp(2), dp(12), dp(6)) })

        emptyCart = TextView(this).apply {
            text = "Kassa bo'sh.\nMahsulotni qidirib, \"＋ Kassaga\" tugmasini bosing."
            textSize = 16f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(40))
        }
        screen.addView(emptyCart)

        cartAdapter = object : BaseAdapter() {
            val items get() = cart.values.toList()
            override fun getCount() = cart.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View = cartRow(items[p])
        }
        screen.addView(ListView(this).apply {
            adapter = cartAdapter; divider = null; clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(8))
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        // JAMI
        val totalBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(INK, 18)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        totalSub = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#BBBBBB")) }
        totalText = TextView(this).apply { textSize = 30f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        totalBox.addView(totalSub); totalBox.addView(totalText)
        totalBox.addView(TextView(this).apply {
            text = "Hisob-kitob. Billz'ga faqat \"📤 Billz'ga qoralama\" bosilganda yuboriladi."
            textSize = 11f; setTextColor(Color.parseColor("#999999")); setPadding(0, dp(4), 0, 0)
        })
        screen.addView(totalBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(6)) })
        screen.addView(pill("🖨 Chop etish / PDF", INK, Color.WHITE, 17f) { printCart() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
        screen.addView(pill("📤 Billz'ga qoralama (Turfa)", Color.WHITE, Color.parseColor("#6A1B9A"), 17f) { sendToBillz() }
            .apply { setOnLongClickListener { askKey {}; true } },
            LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        return screen
    }

    private fun cartRow(ci: CartItem): View {
        val p = ci.p
        val unit = p.unitPrice(optomMode)
        val color = if (optomMode) BLUE else GREEN
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(Color.WHITE, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(TextView(this).apply {
            text = p.name; textSize = 16f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
        })
        val note = when {
            optomMode && p.noOptom() -> "  · optom narx yo'q, dona olindi"
            (if (optomMode) p.optomList else p.donaList).size > 1 -> "  · 2 xil narx, kattasi olindi"
            else -> ""
        }
        card.addView(TextView(this).apply {
            text = "${fmt(unit)} × ${group(ci.count)}$note"
            textSize = 13f; setTextColor(if (note.isEmpty()) Color.GRAY else ORANGE)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val minus = pill("−", INK, Color.parseColor("#EEEEEE"), 22f) {
            ci.count -= 1
            if (ci.count <= 0) cart.remove(p.key)
            updateCart()
        }
        val count = pill(group(ci.count), INK, Color.WHITE, 18f) { askCount(ci) }
        val plus = pill("+", Color.WHITE, INK, 22f) { ci.count += 1; updateCart() }
        row.addView(minus, LinearLayout.LayoutParams(dp(48), dp(44)))
        row.addView(count, LinearLayout.LayoutParams(dp(72), dp(44)))
        row.addView(plus, LinearLayout.LayoutParams(dp(48), dp(44)))
        row.addView(TextView(this).apply {
            text = fmt(unit * ci.count); textSize = 19f; setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val wrap = LinearLayout(this).apply { setPadding(dp(4), dp(5), dp(4), dp(5)) }
        wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun setMode(optom: Boolean) {
        optomMode = optom
        donaBtn.setTextColor(if (optom) GREEN else Color.WHITE)
        donaBtn.background = box(if (optom) Color.WHITE else GREEN, 22)
        optomBtn.setTextColor(if (optom) Color.WHITE else BLUE)
        optomBtn.background = box(if (optom) BLUE else Color.WHITE, 22)
        updateCart()
        toast(if (optom) "Hamma mahsulot OPTOM narxda" else "Hamma mahsulot DONA narxda")
    }

    private fun updateCart() {
        val pieces = cart.values.sumOf { it.count }
        val total = cart.values.sumOf { it.p.unitPrice(optomMode) * it.count }
        val mode = if (optomMode) "OPTOM" else "DONA"
        kassaBar.text = "🧮 Hisob: ${group(pieces)} ta  ·  ${fmt(total)}  ›"
        kassaBar.visibility = if (cart.isEmpty()) View.GONE else View.VISIBLE
        totalSub.text = "JAMI ($mode narxda) · ${cart.size} xil, ${group(pieces)} ta"
        totalText.text = if (total > 0) fmt(total) else "0 so'm"
        emptyCart.visibility = if (cart.isEmpty()) View.VISIBLE else View.GONE
        cartAdapter.notifyDataSetChanged()
    }

    private fun showKassa(show: Boolean) {
        hideKb(search)
        kassaScreen.visibility = if (show) View.VISIBLE else View.GONE
        searchScreen.visibility = if (show) View.GONE else View.VISIBLE
        navMark(show)
        if (!show) adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- Billz'ga qoralama
    private fun prefs() = getSharedPreferences("kassa", MODE_PRIVATE)

    private fun askKey(then: () -> Unit) {
        val input = EditText(this).apply {
            hint = "Ulanish kaliti"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs().getString("key", ""))
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        AlertDialog.Builder(this).setTitle("Billz ulanish kaliti")
            .setMessage("Do'kon egasidan olingan kalitni kiriting (bir marta).")
            .setView(wrap)
            .setPositiveButton("Saqlash") { _, _ ->
                val k = input.text.toString().trim()
                if (k.isNotEmpty()) { prefs().edit().putString("key", k).apply(); then() }
            }
            .setNegativeButton("Bekor", null).show()
    }

    private fun sendToBillz() {
        if (cart.isEmpty()) { toast("Kassa bo'sh"); return }
        if (KASSA_API.startsWith("__")) { toast("Billz ulanishi hali sozlanmagan"); return }
        val key = prefs().getString("key", "").orEmpty()
        if (key.isEmpty()) { askKey { sendToBillz() }; return }
        val items = cart.values.toList()
        val total = items.sumOf { it.p.unitPrice(optomMode) * it.count }
        val mode = if (optomMode) "OPTOM" else "DONA"
        AlertDialog.Builder(this).setTitle("Billz'ga yuborilsinmi?")
            .setMessage("Turfa kassasida qoralama (отложенный чек) ochiladi.\n\n${items.size} xil, ${group(items.sumOf { it.count })} ta\n$mode narxda: ${fmt(total)}\n\nTo'lovni kassir Billz'da oladi.")
            .setPositiveButton("Yuborish") { _, _ -> doSend(key, items) }
            .setNegativeButton("Bekor", null).show()
    }

    private fun doSend(key: String, items: List<CartItem>) {
        val wait = AlertDialog.Builder(this).setMessage("⏳ Billz'ga yuborilmoqda…").setCancelable(false).show()
        val body = org.json.JSONObject().apply {
            put("mode", if (optomMode) "optom" else "dona")
            put("items", org.json.JSONArray().apply {
                for (ci in items) put(org.json.JSONObject().apply {
                    put("sku", ci.p.code.ifBlank { ci.p.barcode.ifBlank { ci.p.name } })
                    if (ci.p.barcode.isNotBlank()) put("barcode", ci.p.barcode)
                    put("name", ci.p.name.take(300))
                    put("qty", ci.count)
                    put("price", ci.p.unitPrice(optomMode))
                })
            })
            put("note", "TUR-FA narxlar ilovasidan")
        }.toString()
        Thread {
            val (code, text) = try {
                val c = URL("$KASSA_API/api/kassa/qoralama").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 120000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-app-key", key)
                c.setRequestProperty("x-xodim", xodimCode())
                c.outputStream.use { it.write(body.toByteArray()) }
                val rc = c.responseCode
                val t = (if (rc in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                c.disconnect(); rc to t
            } catch (e: Exception) { -1 to "" }
            main.post {
                wait.dismiss()
                val j = try { org.json.JSONObject(text) } catch (e: Exception) { null }
                when {
                    code == -1 -> alert("Internet yo'q", "Billz'ga ulanib bo'lmadi. Internetni tekshirib, qayta urinib ko'ring.")
                    code == 401 -> { prefs().edit().remove("key").apply(); alert("Kalit noto'g'ri", "Ulanish kaliti noto'g'ri. Qayta bosib, to'g'ri kalitni kiriting.") }
                    j?.optBoolean("ok") == true -> {
                        val bySku = items.associate { it.p.code.ifBlank { it.p.barcode.ifBlank { it.p.name } } to it.p.name }
                        val nf = j.optJSONArray("not_found")
                        val fl = j.optJSONArray("failed")
                        val msg = buildString {
                            append("Turfa kassasida qoralama tayyor.\nBillz'da: Отложенные чеки.\n\n")
                            append("Qo'shildi: ${j.optInt("added")} xil tovar")
                            if (nf != null && nf.length() > 0) {
                                append("\n\n❓ Billz'da topilmadi: ${nf.length()} ta")
                                for (i in 0 until nf.length()) { val k = nf.optString(i); append("\n• ${bySku[k] ?: k}") }
                            }
                            if (fl != null && fl.length() > 0) {
                                append("\n\n⚠️ Qo'shilmadi: ${fl.length()} ta (kassir qo'lda qo'shsin)")
                                for (i in 0 until fl.length()) {
                                    val o = fl.optJSONObject(i) ?: continue
                                    val k = o.optString("sku")
                                    append("\n• ${bySku[k] ?: k} — ${o.optString("error")}")
                                }
                            }
                        }
                        AlertDialog.Builder(this).setTitle("✅ Billz'ga yuborildi").setMessage(msg)
                            .setPositiveButton("Kassani tozalash") { _, _ -> cart.clear(); updateCart() }
                            .setNegativeButton("Qoldirish", null).show()
                    }
                    else -> {
                        val err = j?.optString("error")?.ifBlank { null } ?: "Server xatosi ($code). Keyinroq urinib ko'ring."
                        val hint = if (err.contains("qo'shilmadi")) "\n\nEhtimol bu tovarlar Billz'da Turfa omborida yo'q (tugagan). Billz sozlamalarida minusga sotishga ruxsat bo'lmasa, Billz qabul qilmaydi. Kassir tovarni Billz'ning o'zida qo'shsin." else ""
                        alert("Yuborilmadi", err + hint)
                    }
                }
            }
        }.start()
    }

    private fun alert(t: String, m: String) =
        AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show()

    // ---------------------------------------------------------------- chop etish
    private var printView: android.webkit.WebView? = null

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun cartHtml(): String {
        val items = cart.values.toList()
        val mode = if (optomMode) "OPTOM" else "DONA"
        val total = items.sumOf { it.p.unitPrice(optomMode) * it.count }
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date())
        val rows = items.mapIndexed { i, ci ->
            val unit = ci.p.unitPrice(optomMode)
            "<tr><td>${i + 1}</td><td>${esc(ci.p.name)}<div class=k>${esc(ci.p.code)}</div></td>" +
                "<td class=n>${group(ci.count)}</td><td class=n>${group(unit)}</td><td class=n><b>${group(unit * ci.count)}</b></td></tr>"
        }.joinToString("")
        return """<!doctype html><html><head><meta charset="utf-8"><style>
            body{font-family:sans-serif;font-size:12pt;margin:16px;color:#111}
            h1{font-size:18pt;margin:0}.s{color:#555;margin:2px 0 12px}
            table{width:100%;border-collapse:collapse}th,td{border:1px solid #999;padding:5px 6px;vertical-align:top}
            th{background:#eee;text-align:left}.n{text-align:right;white-space:nowrap}.k{color:#666;font-size:9pt}
            .t{font-size:15pt;text-align:right;margin-top:12px}
            </style></head><body>
            <h1>TUR-FA sovg'alar</h1>
            <div class=s>Buyurtma ro'yxati · $date · $mode narxda</div>
            <table><tr><th>№</th><th>Tovar</th><th class=n>Soni</th><th class=n>Narxi</th><th class=n>Summa</th></tr>$rows</table>
            <div class=t>${items.size} xil, ${group(items.sumOf { it.count })} ta · <b>JAMI: ${group(total)} so'm</b></div>
            </body></html>"""
    }

    private fun printCart() {
        if (cart.isEmpty()) { toast("Kassa bo'sh"); return }
        val wv = android.webkit.WebView(this)
        printView = wv   // chop etish tugaguncha xotirada tursin
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
                val name = "TURFA-royxat-" + SimpleDateFormat("ddMM-HHmm", Locale.US).format(Date())
                pm.print(name, view.createPrintDocumentAdapter(name),
                    android.print.PrintAttributes.Builder().setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).build())
            }
        }
        wv.loadDataWithBaseURL(null, cartHtml(), "text/html", "UTF-8", null)
    }

    private fun clearCart() {
        if (cart.isEmpty()) return
        AlertDialog.Builder(this).setMessage("Kassani tozalaysizmi?")
            .setPositiveButton("Ha, tozalash") { _, _ -> cart.clear(); updateCart() }
            .setNegativeButton("Yo'q", null).show()
    }

    private fun askCount(ci: CartItem) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; textSize = 24f; gravity = Gravity.CENTER
            setText(ci.count.toString()); selectAll()
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        AlertDialog.Builder(this).setTitle("Soni: ${ci.p.name}").setView(wrap)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().toLongOrNull() ?: ci.count
                if (n <= 0) cart.remove(ci.p.key) else ci.count = n
                updateCart()
            }
            .setNegativeButton("Bekor", null).show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- ro'yxatni rasmdan o'qish
    private fun scanMenu() {
        val ai = aiKey().isNotEmpty()
        AlertDialog.Builder(this).setTitle(if (ai) "Ro'yxatni o'qish (🤖 AI yoqilgan)" else "Ro'yxatni rasmdan o'qish")
            .setItems(arrayOf("📷 Kamera bilan rasmga olish", "🖼 Galereyadan / skrinshot",
                if (ai) "⚙️ AI kalitini o'zgartirish" else "🤖 AI'ni yoqish (Gemini kaliti)")) { _, w ->
                when (w) { 0 -> openCamera(); 1 -> openGallery(); else -> askAiKey() }
            }.show()
    }

    private fun openGallery(req: Int = REQ_GALLERY) {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, req)
        } catch (e: Exception) { toast("Galereya ochilmadi") }
    }

    private fun openCamera(req: Int = REQ_CAMERA) {
        try {
            val f = File(File(cacheDir, "cam").apply { mkdirs() }, if (req == REQ_CAMERA) "royxat.jpg" else "tovar.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            camUri = uri
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, req)
        } catch (e: Exception) { toast("Kamera ochilmadi") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UP_CAM || requestCode == REQ_UP_GAL) { uploadResult(requestCode, resultCode, data); return }
        if (resultCode != RESULT_OK) { if (requestCode == REQ_PHOTO_CAM || requestCode == REQ_PHOTO_GAL) photoScope = null; return }
        val uri = when (requestCode) {
            REQ_GALLERY, REQ_PHOTO_GAL, REQ_SHELF_GAL -> data?.data
            REQ_KOD_CAM -> camUri ?: FileProvider.getUriForFile(this, "$packageName.files", File(File(cacheDir, "cam"), "tovar.jpg"))
            REQ_CAMERA, REQ_PHOTO_CAM, REQ_TEACH, REQ_SHELF_CAM -> camUri
            else -> null
        } ?: return
        when (requestCode) {
            REQ_PHOTO_CAM, REQ_PHOTO_GAL -> photoSearch(uri)
            REQ_SHELF_CAM, REQ_SHELF_GAL -> shelfSearch(uri)
            REQ_KOD_CAM -> kodPhotoSave(uri)
            REQ_TEACH -> teachFor?.let { teachSave(it, uri) }
            else -> recognize(uri)
        }
    }

    // ---------------------------------------------------------------- 🗄 etaj rasmi: bir rasmda ko'p tovar
    private fun shelfSearch(uri: Uri) {
        val target = polkaAdd ?: return
        if (!Visual.ready()) { toast("Rasm bazasi hali yuklanmagan — internetni yoqib 🔄 bosing"); return }
        val wait = AlertDialog.Builder(this).setMessage("🗄 Etajdagi buyumlar ajratilmoqda…").setCancelable(false).show()
        Thread {
            val res = try {
                val big = Visual.bitmap(this, uri, 1280)
                // 1) yozuvlardan kodlar (yorliqlar ko'rinsa — eng aniq)
                val codes = try {
                    val text = com.google.android.gms.tasks.Tasks.await(
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(big, 0)),
                        10, java.util.concurrent.TimeUnit.SECONDS).text
                    CodeReader.find(all, text)
                } catch (_: Throwable) { emptyList() }
                // 2) har buyum alohida
                val rows = ArrayList<Any>()
                val used = HashSet<String>()
                val crops = Shelf.crops(applicationContext, big)
                val byCode = codes.flatMap { it.second }.distinctBy { it.key }
                rows += "🗄 ${Polka.label(target)} — rasmda ${crops.size} ta buyum   ✕ yopish"
                if (byCode.isNotEmpty()) {
                    rows += "🔤 Yorliqdan o'qilgan kod: " + codes.joinToString(", ") { it.first }
                    byCode.forEach { used += it.key }; rows.addAll(byCode)
                }
                crops.forEachIndexed { i, c ->
                    val q = Visual.embedQuery(applicationContext, c)
                    val hits = Visual.search(q, all, 6).map { it.first }.filter { it.key !in used }.take(2)
                    rows += ShelfObj(Bitmap.createScaledBitmap(c, 160, (160f * c.height / c.width).toInt().coerceIn(60, 320), true), i + 1)
                    hits.forEach { used += it.key }
                    rows.addAll(hits)
                }
                Result.success(rows)
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                wait.dismiss()
                res.onSuccess { shelfRows = it; if (search.text.isNotEmpty()) search.setText("") else refresh(); listView.setSelection(0) }
                    .onFailure { toast("Rasmni o'qib bo'lmadi: ${it.message}") }
            }
        }.start()
    }

    private fun shelfObjView(o: ShelfObj): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(14), dp(10), dp(6))
        }
        row.addView(ImageView(this).apply {
            setImageBitmap(o.bmp); scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(LINE, 10); clipToOutline = true; contentDescription = "${o.n}-buyum"
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(12) })
        row.addView(TextView(this).apply {
            text = "${o.n}-buyum — mos tovarlar:"; textSize = 15f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
        })
        return row
    }

    // ---------------------------------------------------------------- rasm orqali qidirish
    private fun photoMenu() {
        AlertDialog.Builder(this).setTitle("📷 Tovarni rasm orqali topish")
            .setItems(arrayOf("🔍 Kamera bilan qidirish", "🔍 Galereyadagi rasm bilan qidirish",
                "📸 Tovarga rasm yuklash (kod bilan)", "🖼 Yuklangan rasmlar (Billz'ga qo'shish)",
                "🚫 Rasmi yo'q tovarlar", "📁 Rasmlar papkasi (ZIP yuklab olish)")) { _, w ->
                when (w) {
                    0 -> openCamera(REQ_PHOTO_CAM)
                    1 -> openGallery(REQ_PHOTO_GAL)
                    2 -> uploadStart()
                    3 -> rasmlarList()
                    4 -> openNoPhoto()
                    else -> zipMenu()
                }
            }.show()
    }

    private fun photoSearch(uri: Uri) {
        val vis = Visual.ready()
        val wait = AlertDialog.Builder(this).setMessage("📷 Rasm va undagi kod tekshirilmoqda…").setCancelable(false).show()
        Thread {
            val res = try {
                // 1) Rasmdagi yozilgan kod (43-25…) — telefonning o'zida o'qiladi; topilsa eng aniq natija shu
                val codes = try {
                    val big = Visual.bitmap(this, uri, 1280)
                    val text = com.google.android.gms.tasks.Tasks.await(
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(big, 0)),
                        10, java.util.concurrent.TimeUnit.SECONDS).text
                    CodeReader.find(all, text)
                } catch (_: Throwable) { emptyList() }
                // 2) Rasmga o'xshashlik
                val scope = photoScope
                val pool = if (scope == null) all else all.filter { Polka.matches(it, scope) }
                var ranked = codes
                var doubt = false
                val (v, hits) = if (vis) {
                    val qs = Visual.embedQuery(applicationContext, Visual.bitmap(this, uri))
                    // Kod bir necha tovarga to'g'ri kelsa (44-12 -> 3-4 ta) — ular orasida rasmga eng o'xshashi birinchi
                    ranked = codes.map { (c, list) ->
                        if (list.size < 2) c to list else {
                            val byLook = Visual.search(qs, list, list.size).map { it.first }.filter { p -> list.any { it.key == p.key } }
                            c to (byLook + list.filter { p -> byLook.none { it.key == p.key } })
                        }
                    }
                    val vh = Visual.search(qs, pool)
                    // Kod noto'g'ri o'qilgan yoki boshqa tovarniki bo'lishi mumkin (soatdagi 42-3 -> gul 42-3):
                    // kodli tovarlarning rasmi bor-u, suratga eng o'xshashidan ancha uzoq bo'lsa — kodga ishonmaymiz
                    val codeProds = codes.flatMap { it.second }
                    val codeLook = Visual.search(qs, codeProds, codeProds.size)
                    val best = vh.firstOrNull()?.second
                    // Faqat qisqa kod (42-3 kabi, bir nechta tovarda bor) va rasm juda uzoq bo'lsa.
                    // To'liq kod (26-3-44-12) yoki bitta tovarga tegishli kod o'qilsa — har doim kodga ishonamiz.
                    val shortOnly = codes.isNotEmpty() && codes.all { (c, l) -> c.count { it == '-' } <= 1 && l.size >= 2 }
                    if (shortOnly && best != null && codeLook.size == codeProds.size &&
                        codeLook.maxOf { it.second } < best - 0.15f) doubt = true
                    qs[0] to vh
                } else null to emptyList()
                if (!vis && codes.isEmpty()) throw IllegalStateException("Rasmda kod topilmadi, rasm bazasi esa hali yuklanmagan. Internetni yoqib 🔄 Yangilash ni bosing.")
                Result.success(Triple(v, hits, ranked to doubt))
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                wait.dismiss()
                res.onSuccess { (v, hits, cd) ->
                    val (codes, doubt) = cd
                    photoVec = v; photoHits = hits; photoCodes = codes; photoCodeDoubt = doubt
                    photoLearned.clear(); photoShared.clear(); photoUri = uri
                    favMode = false; catSel = null; noPhotoMode = false; polkaView = null; clearSpecial()
                    photoScopeShown = photoScope; photoScope = null
                    hideKb(search)
                    if (search.text.isNotEmpty()) search.setText("")
                    refresh()
                    when {
                        codes.isNotEmpty() -> toast("🔤 Kod o'qildi: " + codes.joinToString(", ") { it.first })
                        hits.isEmpty() -> toast(if (photoScopeShown != null) "${photoScopeShown}-polkada o'xshash tovar topilmadi" else "O'xshash tovar topilmadi")
                    }
                }.onFailure { e ->
                    photoScope = null
                    alert("Rasmni o'qib bo'lmadi", e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- kategoriyalar
    private fun catMenu() {
        val groups = all.groupBy { Cats.of(it) }
        fun count(c: String, sub: String? = null) =
            groups.entries.sumOf { (k, v) -> if (k.first == c && (sub == null || k.second == sub)) v.size else 0 }
        val order = Cats.order().filter { count(it.first) > 0 }
        if (order.isEmpty()) { toast("Kategoriyalar yuklanmagan. 🔄 ni bosing"); return }
        AlertDialog.Builder(this).setTitle("🗂 Kategoriyalar")
            .setItems(order.map { "${it.first}  (${count(it.first)})" }.toTypedArray()) { _, i ->
                val (c, subs) = order[i]
                val present = subs.filter { count(c, it) > 0 }
                if (present.isEmpty()) openCat(c, null)
                else {
                    val labels = listOf("Hammasi  (${count(c)})") + present.map { "$it  (${count(c, it)})" }
                    AlertDialog.Builder(this).setTitle(c)
                        .setItems(labels.toTypedArray()) { _, j -> openCat(c, if (j == 0) null else present[j - 1]) }
                        .show()
                }
            }.show()
    }

    // ---------------------------------------------------------------- 🏷 kod chiqarish ro'yxati (shu telefonda)
    private class KodItem(val id: String, val code: String, var name: String, val sku: String, var qty: Long, var photos: Int)

    private fun kodLoad(): MutableList<KodItem> {
        val out = ArrayList<KodItem>()
        try {
            val a = org.json.JSONArray(getSharedPreferences("kod", MODE_PRIVATE).getString("list", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                out += KodItem(it.optString("id").ifBlank { "k${System.nanoTime()}_$i" }, it.optString("code"), it.optString("name"),
                    it.optString("sku"), it.optLong("qty", 1), it.optInt("photos"))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun kodSave(list: List<KodItem>) {
        val a = org.json.JSONArray()
        for (k in list) a.put(org.json.JSONObject().put("id", k.id).put("code", k.code).put("name", k.name)
            .put("sku", k.sku).put("qty", k.qty).put("photos", k.photos))
        getSharedPreferences("kod", MODE_PRIVATE).edit().putString("list", a.toString()).apply()
        try { main.post { navKod() } } catch (_: Exception) {}
    }

    private fun newKodId() = "k" + System.currentTimeMillis() + "_" + (0..999).random()
    private fun kodDir(id: String) = File(File(filesDir, "kod"), id)
    private fun kodThumbs(id: String): List<File> =
        kodDir(id).listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()

    private var kodDlg: AlertDialog? = null

    /** 🏷 ro'yxati rasmlari bilan: kod yopishtirishda tovarni ko'rib topish uchun. */
    private fun kodList() {
        val list = kodLoad()
        kodSave(list)   // eski yozuvlarga id beriladi
        val byCode = all.filter { it.code.isNotBlank() }.associateBy { it.code }
        val total = list.sumOf { it.qty }
        val ad = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(i: Int) = list[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val k = list[i]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = box(Color.WHITE, 12); setPadding(dp(8), dp(8), dp(10), dp(8))
                    setOnClickListener { kodDlg?.dismiss(); kodItemMenu(list, i) }
                }
                val iv = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; background = box(Color.parseColor("#EEEEEE"), 10); clipToOutline = true
                }
                val local = kodThumbs(k.id)
                val url = byCode[k.sku]?.photo.orEmpty()
                when {
                    local.isNotEmpty() -> iv.setImageBitmap(BitmapFactory.decodeFile(local.last().path, BitmapFactory.Options().apply { inSampleSize = 2 }))
                    url.isNotBlank() -> Img.load(this@MainActivity, url, iv)
                    else -> { iv.setImageDrawable(null) }
                }
                iv.setOnClickListener { kodPhotos(k, url) }
                row.addView(iv, LinearLayout.LayoutParams(dp(84), dp(84)).apply { rightMargin = dp(12) })
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this@MainActivity).apply {
                    text = k.code; textSize = 22f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
                })
                col.addView(TextView(this@MainActivity).apply {
                    text = "${group(k.qty)} ta" + (if (local.isNotEmpty()) "   📷 ${local.size}" else if (url.isBlank()) "   📷 rasm yo'q" else "")
                    textSize = 16f; setTextColor(GREEN); typeface = Typeface.DEFAULT_BOLD
                })
                col.addView(TextView(this@MainActivity).apply {
                    text = k.name.ifBlank { "bazada yo'q" }; textSize = 13f; setTextColor(Color.GRAY); maxLines = 2
                })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                return LinearLayout(this@MainActivity).apply { setPadding(0, dp(4), 0, dp(4)); addView(row, -1, -2) }
            }
        }
        val lv = ListView(this).apply { adapter = ad; divider = null; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val empty = TextView(this).apply {
            text = "Ro'yxat bo'sh.\n\n\"➕ Qo'shish\" yoki tovar kartasidagi 🏷 tugmasi bilan qo'shing."
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.GRAY); setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        kodDlg = AlertDialog.Builder(this).setTitle("🏷 Kod chiqarish: ${list.size} ta kod, ${group(total)} ta")
            .setView(if (list.isEmpty()) empty else lv)
            .setPositiveButton("➕ Qo'shish") { _, _ -> kodAdd() }
            .setNeutralButton("📤 Yuborish") { _, _ ->
                if (list.isEmpty()) { toast("Ro'yxat bo'sh"); kodList() } else kodSend(list)
            }
            .setNegativeButton("⋯ Boshqa") { _, _ ->
                val ownerItems = if (isOwner()) arrayOf("📥 Kelgan ro'yxatlar (sotuvchilardan)") else arrayOf("ℹ️ Kirgan: ${myName()}")
                AlertDialog.Builder(this).setItems(ownerItems + arrayOf("📋 Ro'yxatni nusxalash (matn)",
                    "🗑 Ro'yxatni tozalash", "👤 Ismni o'zgartirish (${myName().ifBlank { "kiritilmagan" }})")) { _, w ->
                    when (w) {
                        0 -> if (isOwner()) royxatlar() else kodList()
                        1 -> {
                            val text = list.joinToString("\n") { "${it.code} — ${it.qty} ta" + if (it.name.isNotBlank()) " (${it.name})" else "" }
                            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("kodlar", text))
                            toast("📋 Nusxalandi: ${list.size} ta kod"); kodList()
                        }
                        2 -> if (list.isEmpty()) kodList() else AlertDialog.Builder(this)
                            .setMessage("Ro'yxat tozalansinmi? (${list.size} ta kod)\nYuklangan rasmlar umumiy bazada qoladi.")
                            .setPositiveButton("Tozalash") { _, _ ->
                                kodSave(emptyList()); File(filesDir, "kod").deleteRecursively(); toast("🗑 Tozalandi")
                            }
                            .setNegativeButton("Yo'q") { _, _ -> kodList() }.show()
                        else -> askName { kodList() }
                    }
                }.show()
            }
            .show()
    }

    // ---------------------------------------------------------------- xodimlar: shaxsiy kirish kodi
    private fun xprefs() = getSharedPreferences("xodim", MODE_PRIVATE)
    private fun xodimCode() = xprefs().getString("code", "").orEmpty()
    private fun isOwner() = xprefs().getBoolean("owner", false)
    private val OFFLINE_DAYS = 3

    /** POST /api/kassa/xodim -> (http kod, javob) */
    private fun xodimApi(body: org.json.JSONObject, code: String = xodimCode()): Pair<Int, org.json.JSONObject?> = try {
        val c = URL("$KASSA_API/api/kassa/xodim").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-app-key", appKey())
        c.setRequestProperty("x-xodim", code)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val rc = c.responseCode
        val t = (if (rc in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect(); rc to (try { org.json.JSONObject(t) } catch (e: Exception) { null })
    } catch (e: Exception) { -1 to null }

    private fun login(then: () -> Unit) {
        if (xodimCode().isNotEmpty()) { then(); verifyAccess(); return }
        askCode(then)
    }

    private fun askCode(then: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 24f; gravity = Gravity.CENTER; hint = "0000-0000"
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        val d = AlertDialog.Builder(this).setTitle("🔑 Kirish kodi")
            .setMessage("Do'kon egasidan olingan shaxsiy kodingizni kiriting.")
            .setView(wrap).setCancelable(false)
            .setPositiveButton("Kirish", null)
            .setNegativeButton("Chiqish") { _, _ -> finish() }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().filter { it.isDigit() }
                if (code.length < 6) { input.error = "Kod to'liq emas"; return@setOnClickListener }
                d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val (rc, j) = xodimApi(org.json.JSONObject().put("op", "login"), code)
                    main.post {
                        d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        when {
                            rc in 200..299 && j?.optBoolean("ok") == true -> {
                                xprefs().edit().putString("code", code).putString("name", j.optString("name"))
                                    .putBoolean("owner", j.optBoolean("owner")).putLong("ok", System.currentTimeMillis()).apply()
                                d.dismiss(); toast("👋 Xush kelibsiz, ${j.optString("name")}"); then()
                            }
                            rc == -1 -> input.error = "Internet yo'q. Birinchi kirish uchun internet kerak"
                            else -> { input.text.clear(); input.error = j?.optString("error")?.ifBlank { null } ?: "Kod noto'g'ri" }
                        }
                    }
                }.start()
            }
        }
        d.show()
    }

    /** Fonda tekshiradi: bloklangan bo'lsa qulflaydi; 3 kundan ortiq internetsiz bo'lsa — internet so'raydi. */
    private fun verifyAccess() {
        val code = xodimCode()
        if (code.isEmpty()) return
        Thread {
            val (rc, j) = xodimApi(org.json.JSONObject().put("op", "check"), code)
            main.post {
                when {
                    rc in 200..299 && j?.optBoolean("ok") == true -> xprefs().edit().putString("name", j.optString("name"))
                        .putBoolean("owner", j.optBoolean("owner")).putLong("ok", System.currentTimeMillis()).apply()
                    rc == 403 -> lockOut("🚫 Kirish bekor qilingan", "Sizning kirish kodingiz bloklangan yoki o'chirilgan. Do'kon egasiga murojaat qiling.")
                    rc == -1 && System.currentTimeMillis() - xprefs().getLong("ok", 0) > OFFLINE_DAYS * 86_400_000L ->
                        lockOut("🌐 Internet kerak", "Ilova $OFFLINE_DAYS kundan beri tekshirilmadi. Internetni yoqib, qayta urinib ko'ring.", keepCode = true)
                }
            }
        }.start()
    }

    private fun lockOut(t: String, m: String, keepCode: Boolean = false) {
        if (!keepCode) xprefs().edit().clear().apply()
        cart.clear(); updateCart()
        AlertDialog.Builder(this).setTitle(t).setMessage(m).setCancelable(false)
            .setPositiveButton(if (keepCode) "Qayta tekshirish" else "Boshqa kod kiritish") { _, _ ->
                if (keepCode) verifyAccess() else askCode { update(silent = true) }
            }
            .setNegativeButton("Chiqish") { _, _ -> finish() }.show()
    }

    // ---------------------------------------------------------------- egasi: xodimlar
    private fun ownerMenu() {
        AlertDialog.Builder(this).setTitle("👑 Egasi")
            .setItems(arrayOf("👥 Xodimlar (kodlar, bloklash)", "📥 Kelgan ro'yxatlar", "🖼 Yuklangan rasmlar")) { _, w ->
                when (w) { 0 -> xodimList(); 1 -> royxatlar(); else -> rasmlarList() }
            }.show()
    }

    private fun showCode(name: String, code: String) {
        val pretty = code.chunked(4).joinToString("-")
        AlertDialog.Builder(this).setTitle("🔑 $name uchun kirish kodi")
            .setMessage("\n$pretty\n\nBu kodni xodimga bering. Kod qayta ko'rsatilmaydi (kerak bo'lsa \"Yangi kod\" berasiz).")
            .setPositiveButton("📋 Nusxalash") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("kod", pretty))
                toast("📋 Nusxalandi"); xodimList()
            }
            .setNegativeButton("Yopish") { _, _ -> xodimList() }.show()
    }

    private fun xodimDo(body: org.json.JSONObject, done: (org.json.JSONObject) -> Unit) {
        val wait = AlertDialog.Builder(this).setMessage("⏳").setCancelable(false).show()
        Thread {
            val (rc, j) = xodimApi(body)
            main.post {
                wait.dismiss()
                if (rc in 200..299 && j?.optBoolean("ok") == true) done(j)
                else alert("Bajarilmadi", if (rc == -1) "Internet yo'q." else j?.optString("error")?.ifBlank { null } ?: "Server xatosi ($rc)")
            }
        }.start()
    }

    private fun xodimList() {
        xodimDo(org.json.JSONObject().put("op", "list")) { j ->
            val arr = j.optJSONArray("items") ?: org.json.JSONArray()
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }
            val labels = items.map { (if (it.optBoolean("active")) "✅ " else "🚫 ") + it.optString("name") + if (it.optBoolean("active")) "" else "  (bloklangan)" }
            AlertDialog.Builder(this).setTitle("👥 Xodimlar: ${items.count { it.optBoolean("active") }} faol / ${items.size}")
                .setItems(labels.toTypedArray()) { _, i -> xodimMenu(items[i]) }
                .setPositiveButton("➕ Xodim qo'shish") { _, _ -> xodimAdd() }
                .setNegativeButton("Yopish", null).show()
        }
    }

    private fun xodimAdd() {
        val input = EditText(this).apply { hint = "Ismi, masalan: Bekzod"; isSingleLine = true; textSize = 20f }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        AlertDialog.Builder(this).setTitle("➕ Yangi xodim").setView(wrap)
            .setPositiveButton("Kod berish") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("Ismni yozing"); xodimAdd(); return@setPositiveButton }
                xodimDo(org.json.JSONObject().put("op", "add").put("name", name)) { j -> showCode(name, j.optString("code")) }
            }
            .setNegativeButton("Bekor") { _, _ -> xodimList() }.show()
        input.requestFocus()
    }

    private fun xodimMenu(x: org.json.JSONObject) {
        val name = x.optString("name"); val id = x.optString("id"); val active = x.optBoolean("active")
        AlertDialog.Builder(this).setTitle((if (active) "✅ " else "🚫 ") + name)
            .setItems(arrayOf(if (active) "🚫 Bloklash (ilovasi qulflanadi)" else "✅ Qayta faollashtirish", "🔁 Yangi kod berish", "🗑 O'chirish")) { _, w ->
                when (w) {
                    0 -> xodimDo(org.json.JSONObject().put("op", if (active) "block" else "unblock").put("id", id)) {
                        toast(if (active) "🚫 $name bloklandi" else "✅ $name faollashtirildi"); xodimList()
                    }
                    1 -> xodimDo(org.json.JSONObject().put("op", "reset").put("id", id)) { j -> showCode(name, j.optString("code")) }
                    else -> AlertDialog.Builder(this).setMessage("$name o'chirilsinmi? Ilovasi qulflanadi.")
                        .setPositiveButton("O'chirish") { _, _ ->
                            xodimDo(org.json.JSONObject().put("op", "remove").put("id", id)) { toast("🗑 $name o'chirildi"); xodimList() }
                        }
                        .setNegativeButton("Yo'q") { _, _ -> xodimList() }.show()
                }
            }
            .setNegativeButton("Orqaga") { _, _ -> xodimList() }.show()
    }

    // ---------------------------------------------------------------- ro'yxatni egasiga yuborish
    private fun myName() = xprefs().getString("name", "").orEmpty().ifBlank {
        getSharedPreferences("kassa", MODE_PRIVATE).getString("ism", "").orEmpty()
    }

    private fun askName(then: () -> Unit) {
        val input = EditText(this).apply { hint = "Ismingiz, masalan: Bekzod"; isSingleLine = true; textSize = 20f; setText(myName()) }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        AlertDialog.Builder(this).setTitle("👤 Ismingiz").setMessage("Ro'yxatni kim yozganini bilish uchun (bir marta).")
            .setView(wrap)
            .setPositiveButton("Saqlash") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty()) { toast("Ismni yozing"); askName(then); return@setPositiveButton }
                getSharedPreferences("kassa", MODE_PRIVATE).edit().putString("ism", n.take(60)).apply()
                then()
            }
            .setNegativeButton("Bekor", null).show()
        input.requestFocus()
    }

    private fun appKey() = BuildConfig.RASM_APP_KEY.ifBlank { prefs().getString("key", "").orEmpty() }

    /** (kod, javob matni) — tarmoq so'rovi (asosiy oqimda chaqirmang). */
    private fun royxatApi(method: String, json: String? = null): Pair<Int, String> = try {
        val c = URL("$KASSA_API/api/kassa/kod-royxat").openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("x-app-key", appKey())
        c.setRequestProperty("x-xodim", xodimCode())
        if (json != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
        }
        val rc = c.responseCode
        val t = (if (rc in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect(); rc to t
    } catch (e: Exception) { -1 to "" }

    private fun kodSend(list: List<KodItem>) {
        if (myName().isBlank()) { askName { kodSend(list) }; return }
        if (appKey().isEmpty()) { askKey { kodSend(list) }; return }
        val total = list.sumOf { it.qty }
        AlertDialog.Builder(this).setTitle("📤 Yuborilsinmi?")
            .setMessage("Kimdan: ${myName()}\n${list.size} ta kod, ${group(total)} ta")
            .setPositiveButton("Yuborish") { _, _ ->
                val body = org.json.JSONObject().put("op", "send").put("from", myName()).put("items", org.json.JSONArray().apply {
                    for (k in list) put(org.json.JSONObject().put("code", k.code.take(80)).put("name", k.name.take(300))
                        .put("sku", k.sku.take(120)).put("qty", k.qty))
                }).toString()
                val wait = AlertDialog.Builder(this).setMessage("📤 Yuborilmoqda…").setCancelable(false).show()
                Thread {
                    val (rc, _) = royxatApi("POST", body)
                    main.post {
                        wait.dismiss()
                        when (rc) {
                            in 200..299 -> AlertDialog.Builder(this).setTitle("✅ Yuborildi")
                                .setMessage("${list.size} ta kod ${myName()} nomidan yuborildi.\nRo'yxatni tozalaysizmi?")
                                .setPositiveButton("🗑 Tozalash") { _, _ ->
                                    kodSave(emptyList()); File(filesDir, "kod").deleteRecursively(); toast("🗑 Tozalandi")
                                }
                                .setNegativeButton("Qoldirish", null).show()
                            -1 -> alert("Internet yo'q", "Ro'yxat yuborilmadi. Internetni tekshirib, qayta urinib ko'ring.")
                            401 -> alert("Ruxsat yo'q", "Eng yangi APK'ni o'rnating.")
                            else -> alert("Yuborilmadi", "Server xatosi ($rc). Keyinroq urinib ko'ring.")
                        }
                    }
                }.start()
            }
            .setNegativeButton("Bekor") { _, _ -> kodList() }.show()
    }

    /** Egasi uchun: sotuvchilardan kelgan ro'yxatlar. */
    private fun royxatlar() {
        if (appKey().isEmpty()) { askKey { royxatlar() }; return }
        val wait = AlertDialog.Builder(this).setMessage("⏳ Yuklanmoqda…").setCancelable(false).show()
        Thread {
            val (rc, t) = royxatApi("GET")
            main.post {
                wait.dismiss()
                val arr = try { org.json.JSONObject(t).getJSONArray("items") } catch (e: Exception) { null }
                if (rc !in 200..299 || arr == null) { alert("Ochilmadi", if (rc == -1) "Internet yo'q." else "Server xatosi ($rc)."); return@post }
                val lists = (0 until arr.length()).map { arr.getJSONObject(it) }
                if (lists.isEmpty()) { alert("📥 Kelgan ro'yxatlar", "Hozircha hech kim ro'yxat yubormagan."); return@post }
                val fmtT = SimpleDateFormat("dd.MM HH:mm", Locale.US)
                val labels = lists.map { o ->
                    val its = o.optJSONArray("items")
                    var sum = 0L; for (i in 0 until (its?.length() ?: 0)) sum += its!!.getJSONObject(i).optLong("qty")
                    (if (o.optBoolean("done")) "✅ " else "🆕 ") + o.optString("from") + " — ${its?.length() ?: 0} ta kod, ${group(sum)} ta" +
                        "\n     " + fmtT.format(Date(o.optLong("at")))
                }
                AlertDialog.Builder(this).setTitle("📥 Kelgan ro'yxatlar: ${lists.size} ta (🆕 ${lists.count { !it.optBoolean("done") }})")
                    .setItems(labels.toTypedArray()) { _, i -> royxatView(lists[i]) }
                    .setNegativeButton("Yopish", null).show()
            }
        }.start()
    }

    private fun royxatView(o: org.json.JSONObject) {
        val its = o.optJSONArray("items") ?: org.json.JSONArray()
        val items = (0 until its.length()).map { its.getJSONObject(it) }
        val bySku = all.filter { it.code.isNotBlank() }.associateBy { it.code }
        val ad = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val it = items[i]
                val p = bySku[it.optString("sku")]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = box(Color.WHITE, 12); setPadding(dp(8), dp(8), dp(10), dp(8))
                }
                val iv = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; background = box(Color.parseColor("#EEEEEE"), 10); clipToOutline = true
                }
                val url = p?.let { myPhoto(it) ?: it.photo }.orEmpty()
                if (url.isNotBlank()) { Img.load(this@MainActivity, url, iv); iv.setOnClickListener { p?.let { showPhoto(it) } } }
                row.addView(iv, LinearLayout.LayoutParams(dp(76), dp(76)).apply { rightMargin = dp(12) })
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this@MainActivity).apply { text = it.optString("code"); textSize = 20f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD })
                col.addView(TextView(this@MainActivity).apply { text = "${group(it.optLong("qty"))} ta"; textSize = 16f; setTextColor(GREEN); typeface = Typeface.DEFAULT_BOLD })
                col.addView(TextView(this@MainActivity).apply { text = it.optString("name").ifBlank { "bazada yo'q" }; textSize = 13f; setTextColor(Color.GRAY); maxLines = 2 })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                return LinearLayout(this@MainActivity).apply { setPadding(0, dp(4), 0, dp(4)); addView(row, -1, -2) }
            }
        }
        val lv = ListView(this).apply { adapter = ad; divider = null; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val id = o.optString("id")
        val done = o.optBoolean("done")
        fun mark(op: String, msg: String) {
            Thread {
                val (rc, _) = royxatApi("POST", org.json.JSONObject().put("op", op).put("id", id).toString())
                main.post { if (rc in 200..299) { toast(msg); royxatlar() } else toast("Bajarilmadi ($rc)") }
            }.start()
        }
        AlertDialog.Builder(this)
            .setTitle("${o.optString("from")} · ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(o.optLong("at")))}")
            .setView(lv)
            .setPositiveButton(if (done) "↩️ Qaytarish" else "✅ Bajarildi") { _, _ ->
                if (done) mark("undo", "↩️ Qaytarildi") else mark("done", "✅ Bajarildi deb belgilandi")
            }
            .setNeutralButton("📋 Nusxalash") { _, _ ->
                val text = "${o.optString("from")}:\n" + items.joinToString("\n") { "${it.optString("code")} — ${it.optLong("qty")} ta" +
                    it.optString("name").let { n -> if (n.isNotBlank()) " ($n)" else "" } }
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("ro'yxat", text))
                toast("📋 Nusxalandi")
            }
            .setNegativeButton("🗑 O'chirish") { _, _ ->
                AlertDialog.Builder(this).setMessage("Bu ro'yxat o'chirilsinmi?")
                    .setPositiveButton("O'chirish") { _, _ -> mark("remove", "🗑 O'chirildi") }
                    .setNegativeButton("Yo'q", null).show()
            }
            .show()
    }


    /** Ro'yxatdagi tovar rasmlarini katta ko'rish (o'zingiz olganlari, bo'lmasa bazadagi). */
    private fun kodPhotos(k: KodItem, url: String) {
        val local = kodThumbs(k.id)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        for (f in local) strip.addView(ImageView(this).apply {
            adjustViewBounds = true; setImageBitmap(BitmapFactory.decodeFile(f.path))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        if (local.isEmpty() && url.isNotBlank()) strip.addView(ImageView(this).apply {
            adjustViewBounds = true; Img.load(this@MainActivity, url, this, big = true)
        }, LinearLayout.LayoutParams(-1, -2))
        if (strip.childCount == 0) { toast("Rasm yo'q. Qatorni bosib 📷 Rasmga oling"); return }
        AlertDialog.Builder(this).setTitle("${k.code} — ${group(k.qty)} ta")
            .setView(android.widget.ScrollView(this).apply { addView(strip) })
            .setPositiveButton("Yopish", null).show()
    }

    private fun kodAdd() {
        val code = EditText(this).apply {
            hint = "Kod: 43-39"; isSingleLine = true; textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val qty = EditText(this).apply { hint = "Soni"; inputType = InputType.TYPE_CLASS_NUMBER; textSize = 20f; setText("1") }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0)
            addView(code, -1, -2); addView(qty, -1, -2)
        }
        AlertDialog.Builder(this).setTitle("🏷 Kod va soni").setView(box)
            .setPositiveButton("Saqlash") { _, _ ->
                val c = code.text.toString().trim()
                val n = qty.text.toString().toLongOrNull()?.takeIf { it > 0 } ?: 1
                if (c.isEmpty()) { toast("Kodni yozing"); kodAdd(); return@setPositiveButton }
                // Bazadan aniq kod bo'yicha (4339 -> 43-39 ham)
                val canon = Search.canonical(all, c)
                val found = Search.run(all, c).filter { p -> p.tokens.contains(canon.lowercase()) || p.code.equals(c, true) }
                    .ifEmpty { Search.run(all, c) }.take(20)
                fun save(p: Product?) {
                    val list = kodLoad()
                    val shown = if (canon.isNotBlank()) canon else c
                    list += KodItem(newKodId(), shown, p?.name.orEmpty(), p?.code.orEmpty(), n, 0)
                    kodSave(list)
                    val noPhoto = p == null || p.photo.isBlank()
                    AlertDialog.Builder(this).setTitle("✅ Saqlandi: $shown — $n ta" + if (noPhoto) "\n📷 Bu tovarning rasmi yo'q" else "")
                        .setItems(arrayOf("📷 Hozir rasmga olish", "➕ Yana kod qo'shish", "🏷 Ro'yxatni ko'rish")) { _, w ->
                            when (w) { 0 -> kodPhoto(list.size - 1); 1 -> kodAdd(); else -> kodList() }
                        }.show()
                }
                when {
                    found.size == 1 -> save(found[0])
                    found.isEmpty() -> save(null)
                    else -> AlertDialog.Builder(this).setTitle("Qaysi tovar? ($c)")
                        .setItems((found.map { "${it.name}\n${it.code}" } + "Bazada yo'q — faqat kod").toTypedArray()) { _, i ->
                            save(found.getOrNull(i))
                        }.show()
                }
            }
            .setNegativeButton("Bekor") { _, _ -> kodList() }.show()
        code.requestFocus()
    }

    private fun kodItemMenu(list: MutableList<KodItem>, i: Int) {
        val k = list[i]
        AlertDialog.Builder(this).setTitle("${k.code} — ${group(k.qty)} ta" + if (k.name.isNotBlank()) "\n${k.name}" else "")
            .setItems(arrayOf("📷 Rasmga olish (faqat ro'yxat uchun)", "✏️ Sonini o'zgartirish", "❌ Ro'yxatdan olib tashlash", "🖼 Rasmlarini ko'rish")) { _, w ->
                when (w) {
                    0 -> kodPhoto(i)
                    3 -> { kodPhotos(k, all.firstOrNull { it.code == k.sku }?.photo.orEmpty()); }
                    1 -> {
                        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; textSize = 24f; setText(k.qty.toString()); selectAll() }
                        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
                        AlertDialog.Builder(this).setTitle("Soni: ${k.code}").setView(wrap)
                            .setPositiveButton("OK") { _, _ ->
                                input.text.toString().toLongOrNull()?.takeIf { it > 0 }?.let { k.qty = it; kodSave(list) }
                                kodList()
                            }.setNegativeButton("Bekor") { _, _ -> kodList() }.show()
                    }
                    else -> { kodDir(k.id).deleteRecursively(); list.removeAt(i); kodSave(list); kodList() }
                }
            }
            .setNegativeButton("Orqaga") { _, _ -> kodList() }.show()
    }

    /** Tovar nomidagi do'kon kodi (masalan "gul 43-39" -> "43-39"), bo'lmasa Billz kodi. */
    private fun shopCode(p: Product): String {
        // Qavs ichidagilar (eski kod) hisobga olinmaydi; harflar asl holicha (B-041, ST-1916)
        val clean = p.name.replace(Regex("\\([^)]*\\)"), " ")
        val cands = clean.split(Regex("[\\s,;/|\\[\\]{}\"'«»]+"))
            .map { it.trim('.', ':', '!', '?', '*', '#', '-') }
            .filter { t -> t.contains('-') && t.any { it.isDigit() } }
        // Avval faqat raqamli kod (26-3-44-12, 40-7), birinchisi; bo'lmasa harfli (B-042, ST-1916), birinchisi
        return cands.firstOrNull { Regex("\\d+(-\\d+)+").matches(it) } ?: cands.firstOrNull() ?: p.code
    }

    /** Istalgan joydan (qidiruv, rasm qidiruvi, kategoriya, sevimlilar) tovarni 🏷 ro'yxatiga qo'shish. */
    private fun kodAddProduct(p: Product) {
        val list = kodLoad()
        val old = list.firstOrNull { it.sku.isNotBlank() && it.sku == p.code }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; textSize = 24f; gravity = Gravity.CENTER
            hint = "Soni"; setText("1"); selectAll()
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        AlertDialog.Builder(this).setTitle("🏷 ${shopCode(p)} — nechta?" + if (old != null) "\n(ro'yxatda allaqachon ${group(old.qty)} ta, qo'shiladi)" else "")
            .setView(wrap)
            .setPositiveButton("Qo'shish") { _, _ ->
                val n = input.text.toString().toLongOrNull()?.takeIf { it > 0 } ?: 1
                val l = kodLoad()
                val o = l.firstOrNull { it.sku.isNotBlank() && it.sku == p.code }
                val item = o ?: KodItem(newKodId(), shopCode(p), p.name, p.code, n, 0).also { l += it }
                if (o != null) o.qty += n
                kodSave(l)
                toast("🏷 Qo'shildi: ${shopCode(p)} — ${group(item.qty)} ta (ro'yxatda ${l.size} ta kod)")
                // Rasmi yo'q bo'lsa — darrov rasmga olishni taklif qilamiz
                if (p.photo.isBlank() && kodThumbs(item.id).isEmpty()) kodAskPhoto(item.id, p.name)
            }
            .setNegativeButton("Bekor", null).show()
        input.requestFocus()
    }

    private fun kodAskPhoto(id: String, name: String) {
        AlertDialog.Builder(this).setTitle("📷 Rasmi yo'q")
            .setMessage("$name\n\nHozir rasmga olasizmi? Rasm faqat 🏷 ro'yxatda ko'rinadi — kod chiqarib qaytganda qaysi tovarligini bilish uchun. Tovarning asosiy rasmi o'zgarmaydi.")
            .setPositiveButton("📷 Rasmga olish") { _, _ ->
                val i = kodLoad().indexOfFirst { it.id == id }
                if (i >= 0) kodPhoto(i, back = false)
            }
            .setNegativeButton("Keyin", null).show()
    }

    /** Ro'yxatdagi kod uchun rasm — FAQAT shu ro'yxat uchun, telefonda saqlanadi.
     *  Tovarning asosiy rasmiga aylanmaydi, serverga ham yuklanmaydi (kod chiqarib qaytganda qaysi tovarligini bilish uchun). */
    private fun kodPhoto(i: Int, back: Boolean = true) {
        val k = kodLoad().getOrNull(i) ?: return
        kodCamId = k.id; kodCamBack = back
        prefs().edit().putString("kodCam", k.id).apply()   // kamera paytida ilova yopilib qolsa ham
        openCamera(REQ_KOD_CAM)
    }

    private fun kodPhotoSave(uri: Uri) {
        val id = kodCamId ?: prefs().getString("kodCam", null) ?: return
        kodCamId = null; prefs().edit().remove("kodCam").apply()
        try {
            val b = Visual.bitmap(this, uri, 900)
            val dir = kodDir(id).apply { mkdirs() }
            File(dir, "${System.currentTimeMillis()}.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            val list = kodLoad()
            list.firstOrNull { it.id == id }?.let { it.photos += 1; kodSave(list) }
            toast("✅ Rasm ro'yxatga saqlandi (faqat shu telefonda)")
        } catch (e: Exception) { toast("Rasm saqlanmadi: ${e.message}") }
        if (kodCamBack) kodList()
    }

    // ---------------------------------------------------------------- kategoriyani qo'lda almashtirish
    private fun changeCat(p: Product) {
        if (p.code.isBlank()) { toast("Bu tovarning kodi yo'q"); return }
        val order = Cats.order()
        AlertDialog.Builder(this).setTitle("🗂 ${p.name}: qaysi kategoriya?")
            .setItems(order.map { it.first }.toTypedArray()) { _, i ->
                val (c, subs) = order[i]
                if (subs.isEmpty()) saveCatAsk(p, c)
                else AlertDialog.Builder(this).setTitle("$c: qaysi bo'lim?")
                    .setItems(subs.toTypedArray()) { _, j -> saveCatAsk(p, "$c|${subs[j]}") }
                    .show()
            }.show()
    }

    /** Seriyadagi boshqa tovarlar (shu kategoriyada) bo'lsa — hammasiga qo'llashni taklif qiladi. */
    private fun saveCatAsk(p: Product, value: String) {
        val s = Cats.series(p.name)
        val curCat = Cats.of(p).first
        val mates = if (s == null) emptyList() else all.filter {
            it.key != p.key && it.code.isNotBlank() && Cats.series(it.name) == s && Cats.of(it).first == curCat &&
                Cats.of(it).let { c -> (c.first + (c.second?.let { "|$it" } ?: "")) != value }
        }
        if (mates.isEmpty()) { saveCat(p, value); return }
        AlertDialog.Builder(this).setTitle("Butun seriyaga qo'llansinmi?")
            .setMessage("\"$s-…\" seriyasida yana ${mates.size} ta tovar bor (masalan: ${mates.take(3).joinToString(", ") { it.name }}).\n\nHammasini ${value.replace("|", " › ")} ga o'tkazaymi?")
            .setPositiveButton("Ha, hammasini (${mates.size + 1})") { _, _ -> saveCat(p, value, mates) }
            .setNegativeButton("Faqat shu tovar") { _, _ -> saveCat(p, value) }
            .show()
    }

    /** value = "Kategoriya|Bo'lim" / "Kategoriya" yoki null (avtomatik). Hamma telefonga saqlanadi. */
    private fun saveCat(p: Product, value: String?, more: List<Product> = emptyList()) {
        val key = BuildConfig.RASM_APP_KEY.ifBlank { prefs().getString("key", "").orEmpty() }
        if (key.isEmpty()) { askKey { saveCat(p, value) }; return }
        Thread {
            val code = try {
                val c = URL("$KASSA_API/api/kassa/kategoriya").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-app-key", key)
                c.setRequestProperty("x-xodim", xodimCode())
                val skus = org.json.JSONArray().apply { put(p.code); more.forEach { put(it.code) } }
                c.outputStream.use { it.write(org.json.JSONObject().put("skus", skus).put("value", value ?: org.json.JSONObject.NULL).toString().toByteArray()) }
                val rc = c.responseCode; c.disconnect(); rc
            } catch (e: Exception) { -1 }
            main.post {
                when (code) {
                    in 200..299 -> {
                        Cats.setLocal(applicationContext, p.code, value)
                        more.forEach { Cats.setLocal(applicationContext, it.code, value) }
                        refresh()
                        toast(if (value == null) "↩️ Avtomatik kategoriyaga qaytdi"
                            else "✅ ${1 + more.size} ta tovar: ${value.replace("|", " › ")} — hamma telefonda 🔄 dan keyin")
                    }
                    -1 -> toast("Internet yo'q — saqlanmadi")
                    401 -> alert("Ruxsat yo'q", "Eng yangi APK'ni o'rnating.")
                    403 -> alert("Ruxsat yo'q", "Kategoriyani faqat do'kon egasi o'zgartira oladi.")
                    else -> toast("Saqlanmadi (server $code). Keyinroq urinib ko'ring")
                }
            }
        }.start()
    }

    private fun zipMenu() {
        val base = "https://github.com/maxmuda725-jpg/turfa-narx/releases/download/rasmlar-papka/"
        AlertDialog.Builder(this).setTitle("📁 Rasmlar papkasi (har kecha yangilanadi)")
            .setItems(arrayOf("📸 Faqat ilovadan yuklanganlar", "🗂 Hammasi, Billz rasmlari bilan (katta, Wi-Fi'da)")) { _, w ->
                val url = base + if (w == 0) "rasmlar-ilova.zip" else "rasmlar-hammasi.zip"
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (e: Exception) { toast("Brauzer ochilmadi") }
            }.show()
    }

    private fun clearSpecial() { special = null; priceRange = null; altFor = null }

    private fun openNoPhoto() {
        noPhotoMode = true; polkaView = null; clearSpecial()
        catSel = null; favMode = false; photoHits = null; photoVec = null
        hideKb(search)
        if (search.text.isNotEmpty()) search.setText("")
        refresh()
    }

    private fun openCat(c: String, sub: String?) {
        catSel = c to sub; polkaView = null; special = null; altFor = null   // narx oralig'i saqlanadi: "vazalar, 200 minggacha"
        favMode = false; photoHits = null; photoVec = null; noPhotoMode = false
        hideKb(search)
        if (search.text.isNotEmpty()) search.setText("")
        refresh()
    }

    // ---------------------------------------------------------------- 📍 polkalar
    // "polka-etaj" (7-2 = 7-polka, 2-etaj), etaj TEPADAN sanaladi. Polkalarda etaj soni har xil bo'lishi mumkin.
    private fun polkaMenu() {
        val counts = all.flatMap { Polka.of(it) }.groupingBy { Polka.stellaj(it) }.eachCount()
        val noPlace = all.count { Polka.of(it).isEmpty() && Search.stockRank(it) == 0 }
        val items = arrayListOf(
            "🔎 Polkani ko'rish" + if (counts.isEmpty()) "" else "  (${counts.size} ta polka)",
            "📷 Bitta polkada rasm bilan qidirish",
            "❓ Joyi yozilmagan tovarlar ($noPlace ta)")
        val owner = isOwner()   // joylash faqat egasiga
        if (owner) items.add(0, "📍 Polkaga tovar joylash")
        AlertDialog.Builder(this).setTitle("📍 Polkalar (etaj tepadan sanaladi)")
            .setItems(items.toTypedArray()) { _, w0 ->
                val w = if (owner) w0 else w0 + 1
                when (w) {
                    0 -> askPolka("Qaysi polka va etajga joylaymiz?", prefs().getString("polka_last", "") ?: "", false) { startPolkaAdd(it) }
                    1 -> polkaPick()
                    2 -> askPolka("Qaysi polkada qidiramiz?", "", true) { st ->
                        photoScope = Polka.stellaj(st)
                        AlertDialog.Builder(this).setTitle("📷 ${photoScope}-polkada qidirish")
                            .setItems(arrayOf("Kamera bilan", "Galereyadan")) { _, k ->
                                if (k == 0) openCamera(REQ_PHOTO_CAM) else openGallery(REQ_PHOTO_GAL)
                            }.setOnCancelListener { photoScope = null }.show()
                    }
                    else -> openPolka("?")
                }
            }.show()
    }

    /** Raqam so'raydi: "7-2" (polka-etaj) yoki stellajOk bo'lsa "7" (butun polka) ham. */
    private fun askPolka(title: String, preset: String, stellajOk: Boolean, then: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = if (stellajOk) "Masalan: 7" else "Masalan: 7-2"; setText(preset); setSelection(preset.length)
            textSize = 24f; isSingleLine = true; inputType = InputType.TYPE_CLASS_PHONE
        }
        val d = AlertDialog.Builder(this).setTitle(title)
            .setMessage(if (stellajOk) "Polka raqami: 7  (yoki aniq etaj: 7-2)" else "Polka-etaj: 7-2 = 7-polka, 2-etaj.\nEtaj TEPADAN sanaladi: 7-1 — eng yuqori etaj.")
            .setView(input).setPositiveButton("OK", null).setNegativeButton("Bekor", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = input.text.toString().trim()
                val v = Polka.normalize(t) ?: if (stellajOk) t.toIntOrNull()?.takeIf { it in 1..999 }?.toString() else null
                if (v == null) { toast(if (stellajOk) "Raqam yozing: 7 yoki 7-2" else "Masalan: 7-2 (7-polka, 2-etaj)"); return@setOnClickListener }
                d.dismiss(); then(v)
            }
        }
        d.show()
    }

    private fun polkaPick() {
        val st = all.flatMap { Polka.of(it) }.groupingBy { Polka.stellaj(it) }.eachCount()
            .toList().sortedBy { it.first.toIntOrNull() ?: 999 }
        val labels = listOf("✏️ Raqamini yozish") + st.map { "${it.first}-polka  (${it.second} ta)" }
        AlertDialog.Builder(this).setTitle("🔎 Qaysi polka?").setItems(labels.toTypedArray()) { _, i ->
            if (i == 0) askPolka("Qaysi polka?", "", true) { openPolka(it) }
            else {
                val s0 = st[i - 1].first
                val levels = all.flatMap { Polka.of(it) }.filter { Polka.stellaj(it) == s0 }
                    .groupingBy { it }.eachCount().toList().sortedBy { Polka.sortKey(it.first) }
                val l2 = listOf("Hamma etajlar") + levels.map { "${it.first.substringAfter('-')}-etaj  (${it.second} ta)" }
                AlertDialog.Builder(this).setTitle("$s0-polka").setItems(l2.toTypedArray()) { _, j ->
                    openPolka(if (j == 0) s0 else levels[j - 1].first)
                }.show()
            }
        }.show()
    }

    private fun openPolka(sel: String) {
        polkaView = sel; clearSpecial()
        catSel = null; favMode = false; noPhotoMode = false; photoHits = null; photoVec = null
        hideKb(search)
        if (search.text.isNotEmpty()) search.setText("")
        refresh()
    }

    private fun startPolkaAdd(target: String) {
        polkaAdd = target; polkaView = null
        catSel = null; favMode = false; noPhotoMode = false; photoHits = null; photoVec = null
        prefs().edit().putString("polka_last", target).apply()
        if (search.text.isNotEmpty()) search.setText("")
        refresh()
        search.requestFocus()
    }

    private fun polkaAddMenu() {
        val t = polkaAdd ?: return
        val nx = Polka.next(t)
        AlertDialog.Builder(this).setTitle("📍 ${Polka.label(t)} ga joylash")
            .setItems(arrayOf("▶ Keyingi etaj (${Polka.label(nx)})", "✏️ Boshqa polka / etaj", "📷 Bitta tovarni rasm bilan topib qo'yish",
                "🗄 Etajni butunlay rasmga olish (ko'p tovar)", "✅ Tugatish")) { _, w ->
                when (w) {
                    0 -> { startPolkaAdd(nx); toast("📍 ${Polka.label(nx)} — pastroqdagi etaj") }
                    1 -> askPolka("Qaysi polkaga?", t, false) { startPolkaAdd(it) }
                    2 -> photoPick()
                    3 -> AlertDialog.Builder(this).setTitle("🗄 Etaj rasmi")
                        .setMessage("Etajni oldidan, tovarlar yaxshi ko'rinadigan qilib bitta rasmga oling. Ilova har bir buyumni alohida ajratib, mos tovarni taklif qiladi — to'g'risiga \"📍 qo'yish\" ni bosasiz.")
                        .setPositiveButton("📷 Kamera") { _, _ -> openCamera(REQ_SHELF_CAM) }
                        .setNegativeButton("🖼 Galereya") { _, _ -> openGallery(REQ_SHELF_GAL) }.show()
                    else -> { polkaAdd = null; shelfRows = null; refresh(); toast("✅ Joylash tugadi") }
                }
            }.show()
    }

    /** Joylash rejimida: tovarni shu qavatga qo'yish yoki olib tashlash. */
    private fun polkaPlace(p: Product, target: String, remove: Boolean) {
        if (p.code.isBlank()) { toast("Bu tovarning kodi yo'q"); return }
        val cur = Polka.of(p)
        fun done() {
            if (photoHits != null) closePhoto()
            if (search.text.isNotEmpty()) search.setText("")
            search.requestFocus()
        }
        when {
            remove -> polkaSave(p, (cur - target).joinToString(",").ifEmpty { null }) { toast("↩️ ${p.name}: ${Polka.label(target)} dan olindi") }
            cur.isEmpty() -> polkaSave(p, target) { toast("✅ ${p.name} → ${Polka.label(target)}"); done() }
            else -> {
                val opts = arrayListOf("➡️ Ko'chirish: faqat ${Polka.label(target)}")
                if (cur.size < 3) opts += "➕ Qo'shimcha joy: ${Polka.labels(cur + target)}"
                AlertDialog.Builder(this).setTitle("${p.name}\nhozir: ${Polka.labels(cur)}")
                    .setItems(opts.toTypedArray()) { _, w ->
                        val v = if (w == 0) target else (cur + target).joinToString(",")
                        polkaSave(p, v) { toast("✅ ${p.name} → ${Polka.labels(v.split(','))}"); done() }
                    }.setNegativeButton("Bekor", null).show()
            }
        }
    }

    /** Tovar menyusidan: joyini qo'lda yozish/o'zgartirish (vergul bilan 3 tagacha). */
    private fun polkaEdit(p: Product) {
        if (p.code.isBlank()) { toast("Bu tovarning kodi yo'q"); return }
        val cur = Polka.of(p)
        val input = EditText(this).apply {
            hint = "7-2   yoki   7-2, 15-4"; setText(cur.joinToString(", ")); setSelection(text.length)
            textSize = 22f; isSingleLine = true; inputType = InputType.TYPE_CLASS_PHONE
        }
        val b = AlertDialog.Builder(this).setTitle("📍 ${p.name}")
            .setMessage("Polka-etaj: 7-2 = 7-polka, 2-etaj (etaj tepadan sanaladi).\nBir necha joy bo'lsa vergul bilan: 7-2, 15-4 (3 tagacha).")
            .setView(input).setPositiveButton("Saqlash", null).setNegativeButton("Bekor", null)
        if (cur.isNotEmpty()) b.setNeutralButton("🗑 O'chirish") { _, _ -> polkaSave(p, null) { toast("🗑 Joyi o'chirildi") } }
        val d = b.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parts = input.text.toString().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                val norm = parts.map { Polka.normalize(it) }
                if (parts.isEmpty() || norm.any { it == null } || parts.size > 3) { toast("Masalan: 7-2 yoki 7-2, 15-4"); return@setOnClickListener }
                val v = norm.filterNotNull().distinct().joinToString(",")
                d.dismiss()
                polkaSave(p, v) { toast("✅ ${p.name} → ${Polka.labels(v.split(','))}") }
            }
        }
        d.show()
    }

    /** Serverga yozadi (hamma telefonga), muvaffaqiyatli bo'lsa shu telefonda darrov qo'llaydi. */
    private fun polkaSave(p: Product, value: String?, ok: () -> Unit) {
        val key = BuildConfig.RASM_APP_KEY.ifBlank { prefs().getString("key", "").orEmpty() }
        if (key.isEmpty()) { askKey { polkaSave(p, value, ok) }; return }
        Thread {
            val rc = Polka.save(listOf(p.code), value, key, xodimCode())
            main.post {
                when (rc) {
                    in 200..299 -> { Polka.setLocal(applicationContext, listOf(p.code), value); refresh(); ok() }
                    -1 -> toast("Internet yo'q — saqlanmadi")
                    401 -> alert("Ruxsat yo'q", "Eng yangi APK'ni o'rnating.")
                    403 -> alert("Ruxsat yo'q", if (isOwner()) "Egasi kodi bilan qayta kiring." else "Polkani faqat do'kon egasi o'zgartira oladi.")
                    429 -> toast("Juda tez — 1 daqiqa kutib davom eting")
                    else -> toast("Saqlanmadi (server $rc). Keyinroq urinib ko'ring")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- sevimlilar
    private fun toggleFav(p: Product) {
        if (!favs.remove(p.key)) { favs += p.key; toast("⭐ Sevimlilarga qo'shildi") }
        getSharedPreferences("sevimli", MODE_PRIVATE).edit().putStringSet("keys", HashSet(favs)).apply()
        if (favMode) refresh() else adapter.notifyDataSetChanged()
    }

    private fun toggleFavMode() {
        favMode = !favMode
        if (favMode) { photoHits = null; photoVec = null; catSel = null; noPhotoMode = false; polkaView = null; clearSpecial() }
        hideKb(search)
        refresh()
    }

    private fun closePhoto() {
        photoHits = null; photoVec = null; photoUri = null; photoCodes = emptyList(); photoCodeDoubt = false
        refresh()
    }

    /** Rasm bo'yicha topilgan tovar kassaga qo'shilsa — telefon shu rasmni eslab qoladi. */
    private fun rememberPhoto(p: Product) {
        val v = photoVec ?: return
        if (photoHits?.any { it.first.key == p.key } != true || !photoLearned.add(p.key)) return
        Thread { Visual.learn(applicationContext, p, v) }.start()
    }

    /**
     * Rasm orqali qidirilib, do'konchi tasdiqlagan tovar: qidiruv rasmi oddiy "📸 Rasmini o'rgatish" yo'li bilan
     * serverga yuboriladi — GitHub'da vektorga aylanib (scripts/embed.py), ~1 soatda hamma telefonlar ham taniydi.
     * Do'kon sharoitida olingan haqiqiy rasm bo'lgani uchun keyingi qidiruvlarda eng foydali rasm shu.
     */
    private fun sharePhoto(p: Product) {
        val uri = photoUri ?: run { toast("✅ Eslab qolindi"); return }
        val key = BuildConfig.RASM_APP_KEY.ifBlank { prefs().getString("key", "").orEmpty() }
        if (key.isEmpty()) { toast("✅ Shu telefonda eslab qolindi"); return }
        if (!photoShared.add(p.key)) return
        Thread {
            val res = try {
                val bmp = Visual.bitmap(this, uri, 1024)
                val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                val c = URL("$KASSA_API/api/kassa/rasm").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 60000
                c.setRequestProperty("Content-Type", "image/jpeg")
                c.setRequestProperty("x-app-key", key)
                c.setRequestProperty("x-xodim", xodimCode())
                c.setRequestProperty("x-sku", java.net.URLEncoder.encode(p.code.ifBlank { p.name }, "UTF-8"))
                c.outputStream.use { it.write(bytes) }
                val rc = c.responseCode
                val t = (if (rc in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                c.disconnect()
                rc in 200..299 && (try { org.json.JSONObject(t).optBoolean("ok") } catch (_: Exception) { false })
            } catch (_: Throwable) { false }
            main.post {
                if (res) toast("✅ Eslab qolindi va hamma telefonga yuborildi")
                else { photoShared.remove(p.key); toast("✅ Shu telefonda eslab qolindi (serverga yuborilmadi — keyin uzoq bosib qayta urining)") }
            }
        }.start()
    }

    private fun productMenu(p: Product) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (photoVec != null && photoHits?.any { it.first.key == p.key } == true && p.key !in photoLearned) {
            labels += "✅ Rasmdagi tovar shu (hamma telefonga o'rgat)"
            actions += { rememberPhoto(p); sharePhoto(p) }
        } else if (photoVec != null && photoUri != null && p.key in photoLearned && p.key !in photoShared &&
            photoHits?.any { it.first.key == p.key } == true) {
            labels += "📤 Shu rasmni hamma telefonga o'rgatish"
            actions += { sharePhoto(p) }
        }
        val owner = isOwner()   // kategoriya va polkani faqat egasi o'zgartiradi (server ham tekshiradi)
        val locs = Polka.of(p)
        if (owner) {
            labels += if (locs.isEmpty()) "📍 Joyini yozish (qaysi polka, qaysi etaj)" else "📍 Joyini o'zgartirish (hozir: ${Polka.labels(locs)})"
            actions += { polkaEdit(p) }
        }
        labels += "🏷 Kod chiqarish ro'yxatiga qo'shish"
        actions += { kodAddProduct(p) }
        labels += "📸 Rasmini o'rgatish (hamma telefonga)"
        actions += { uploadFor(p) }
        val cur = Cats.of(p)
        if (owner) {
            labels += "🗂 Kategoriyasini o'zgartirish (hozir: ${cur.first}${cur.second?.let { " › $it" } ?: ""})"
            actions += { changeCat(p) }
        }
        if (owner && Cats.override(p) != null) {
            labels += "↩️ Kategoriyani avtomatikka qaytarish"
            actions += { saveCat(p, null) }
        }
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }.show()
    }

    private fun teachSave(p: Product, uri: Uri) {
        Thread {
            val ok = try { Visual.learn(applicationContext, p, Visual.embed(applicationContext, Visual.bitmap(this, uri))); true }
                catch (e: Throwable) { false }
            main.post { toast(if (ok) "✅ Rasm eslab qolindi: ${p.name}" else "Rasmni o'qib bo'lmadi") }
        }.start()
    }

    private fun aiKey() = getSharedPreferences("kassa", MODE_PRIVATE).getString("gemini", "").orEmpty()

    private fun askAiKey() {
        val input = EditText(this).apply {
            hint = "AIza..."; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(aiKey())
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        AlertDialog.Builder(this).setTitle("Gemini AI kaliti")
            .setMessage("aistudio.google.com/apikey dan olingan kalitni kiriting. Bo'sh qoldirsangiz, AI o'chadi.")
            .setView(wrap)
            .setPositiveButton("Saqlash") { _, _ ->
                getSharedPreferences("kassa", MODE_PRIVATE).edit().putString("gemini", input.text.toString().trim()).apply()
                toast(if (input.text.isBlank()) "AI o'chirildi" else "🤖 AI yoqildi")
            }
            .setNegativeButton("Bekor", null).show()
    }

    private fun showParsed(rows: List<String>) {
        val idx = codeIndex ?: ListParser.index(all).also { codeIndex = it }
        val lines = ListParser.parse(rows, idx, all)
        if (lines.isEmpty()) AlertDialog.Builder(this).setTitle("Kod topilmadi")
            .setMessage("Rasmda tovar kodi (masalan 43-25) topilmadi. Ro'yxat aniq va yorug' joyda rasmga olinganini tekshiring.")
            .setPositiveButton("OK", null).show()
        else showReview(lines)
    }

    private fun recognize(uri: Uri) {
        val key = aiKey()
        if (key.isEmpty()) { recognizeLocal(uri); return }
        val wait = AlertDialog.Builder(this).setMessage("🤖 AI ro'yxatni o'qiyapti…").setCancelable(false).show()
        Thread {
            val res = try { Result.success(AiReader.read(this, uri, key)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                wait.dismiss()
                res.onSuccess { rows ->
                    if (rows.isEmpty()) { toast("AI ro'yxat topmadi, oddiy o'qishga o'tildi"); recognizeLocal(uri) }
                    else showParsed(rows)
                }.onFailure { e ->
                    toast("AI: ${e.message ?: "xato"}. Oddiy o'qishga o'tildi")
                    recognizeLocal(uri)
                }
            }
        }.start()
    }

    private fun recognizeLocal(uri: Uri) {
        val wait = AlertDialog.Builder(this).setMessage("⏳ Ro'yxat o'qilmoqda…").setCancelable(false).show()
        val img = try { InputImage.fromFilePath(this, uri) } catch (e: Exception) {
            wait.dismiss(); toast("Rasmni ochib bo'lmadi"); return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
            .addOnSuccessListener { vt ->
                wait.dismiss()
                val ocr = vt.textBlocks.flatMap { b -> b.lines.map { OcrLine(it.text, it.boundingBox) } }
                showParsed(ListParser.rows(ocr))
            }
            .addOnFailureListener { e ->
                wait.dismiss()
                alert("Matnni o'qib bo'lmadi", "Sabab: ${e.message ?: e.javaClass.simpleName}\n\nRasmni yorug'roq va tikroq qilib qayta oling.")
            }
    }

    private fun showReview(lines: MutableList<ScanLine>) {
        var dlg: AlertDialog? = null
        var ad: BaseAdapter? = null
        fun okText() = "Kassaga qo'shish (${lines.count { it.include && it.chosen != null }})"
        val changed: () -> Unit = {
            ad?.notifyDataSetChanged()
            dlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.text = okText()
        }
        ad = object : BaseAdapter() {
            override fun getCount() = lines.size
            override fun getItem(p: Int) = lines[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View = reviewRow(lines[p], changed)
        }
        val list = ListView(this).apply { adapter = ad; divider = null; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val found = lines.count { it.chosen != null }
        val d = AlertDialog.Builder(this)
            .setTitle("Topildi: $found ta · tekshiring: ${lines.size - found} ta")
            .setView(list)
            .setPositiveButton(okText(), null)
            .setNegativeButton("Bekor", null)
            .create()
        dlg = d
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var added = 0L
                for (l in lines) {
                    val p = l.chosen ?: continue
                    if (!l.include) continue
                    cart.getOrPut(p.key) { CartItem(p, 0) }.count += l.count
                    added += l.count
                }
                d.dismiss(); updateCart(); adapter.notifyDataSetChanged()
                toast("✅ Kassaga qo'shildi: ${group(added)} ta")
            }
        }
        d.show()
    }

    private fun reviewRow(l: ScanLine, changed: () -> Unit): View {
        val ok = l.chosen != null && l.include
        val (icon, color) = when {
            l.chosen == null && l.candidates.size > 1 -> "⚠️" to ORANGE
            l.chosen == null -> "❓" to RED
            !l.include -> "⛔" to Color.GRAY
            else -> "✅" to GREEN
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = box(if (ok) Color.WHITE else tint(color, 20), 12)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { reviewOptions(l, changed) }
        }
        row.addView(TextView(this).apply { text = icon; textSize = 20f }, LinearLayout.LayoutParams(dp(34), -2))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = l.chosen?.name ?: if (l.candidates.size > 1) "${l.code}: ${l.candidates.size} xil tovar — tanlang"
                else "Bazada yo'q: ${l.code}"
            textSize = 15f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
        })
        mid.addView(TextView(this).apply {
            text = "Rasmda: ${l.raw}"; textSize = 12f; setTextColor(Color.GRAY); maxLines = 2
        })
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "${group(l.count)} ta"; textSize = 16f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        })
        val wrap = LinearLayout(this).apply { setPadding(0, dp(3), 0, dp(3)) }
        wrap.addView(row, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun reviewOptions(l: ScanLine, changed: () -> Unit) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (l.candidates.size > 1) {
            labels += "Tovarni tanlash"
            actions += {
                AlertDialog.Builder(this).setTitle(l.code)
                    .setItems(l.candidates.map { "${it.name}\nOptom: ${it.optomText} · Dona: ${it.donaText}" }.toTypedArray()) { _, i ->
                        l.chosen = l.candidates[i]; l.include = true; changed()
                    }.show()
            }
        }
        if (l.chosen == null && l.candidates.size <= 1) {
            labels += "🔍 Qo'lda qidirish"
            actions += {
                val input = EditText(this).apply {
                    hint = "Kod yoki nom"; isSingleLine = true; textSize = 20f
                    setText(l.code.takeIf { it != "kod?" }.orEmpty())
                }
                val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
                AlertDialog.Builder(this).setTitle("Rasmda: ${l.raw.take(60)}").setView(wrap)
                    .setPositiveButton("Qidirish") { _, _ ->
                        val found = Search.run(all, input.text.toString()).filter { it.donaList.isNotEmpty() }.take(40)
                        if (found.isEmpty()) toast("Topilmadi")
                        else AlertDialog.Builder(this).setTitle("Tanlang")
                            .setItems(found.map { "${it.name}\nOptom: ${it.optomText} · Dona: ${it.donaText}" }.toTypedArray()) { _, i ->
                                l.chosen = found[i]; l.include = true; changed()
                            }.show()
                    }
                    .setNegativeButton("Bekor", null).show()
            }
        }
        labels += "Sonini o'zgartirish (${group(l.count)} ta)"
        actions += {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; textSize = 24f; gravity = Gravity.CENTER
                setText(l.count.toString()); selectAll()
            }
            val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
            AlertDialog.Builder(this).setTitle("Soni").setView(wrap)
                .setPositiveButton("OK") { _, _ ->
                    input.text.toString().toLongOrNull()?.takeIf { it > 0 }?.let { l.count = it }
                    changed()
                }
                .setNegativeButton("Bekor", null).show()
        }
        if (l.chosen != null) {
            labels += if (l.include) "Qo'shmaslik" else "Qo'shish"
            actions += { l.include = !l.include; changed() }
        }
        AlertDialog.Builder(this).setTitle(l.chosen?.name ?: l.raw)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }.show()
    }

    // ---------------------------------------------------------------- umumiy
    private fun showPhoto(p: Product, start: Int = 0) {
        val pics = allPhotos(p)
        if (pics.isEmpty()) return
        var i = start.coerceIn(0, pics.lastIndex)
        val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val iv = ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(Color.WHITE); contentDescription = "Tovar rasmi" }
        frame.addView(iv, LinearLayout.LayoutParams(-1, -2))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        val counter = TextView(this).apply { textSize = 16f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        fun show() { Img.load(this, pics[i], iv, big = true); counter.text = "${i + 1} / ${pics.size}" }
        if (pics.size > 1) {
            nav.addView(pill("◀", INK, CHIP, 20f) { i = (i - 1 + pics.size) % pics.size; show() }.apply { contentDescription = "Oldingi rasm" },
                LinearLayout.LayoutParams(dp(64), dp(48)))
            nav.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
            nav.addView(pill("▶", INK, CHIP, 20f) { i = (i + 1) % pics.size; show() }.apply { contentDescription = "Keyingi rasm" },
                LinearLayout.LayoutParams(dp(64), dp(48)))
            frame.addView(nav)
            iv.setOnClickListener { i = (i + 1) % pics.size; show() }
        }
        show()
        val db = AlertDialog.Builder(this).setTitle(p.name).setView(frame).setPositiveButton("Yopish", null)
        var dd: AlertDialog? = null
        if (isOwner()) db.setNeutralButton("🗑 Shu rasmni o'chirish") { _, _ -> deletePhoto(p, pics[i]) { } }
        val d = db.create(); dd = d
        if (pics.size <= 1) iv.setOnClickListener { d.dismiss() }
        d.show()
    }

    private fun refresh() {
        val q = search.text.toString()
        if (q != lastQuery) { similarOpen = false; lastQuery = q }
        // Bo'lim o'zgarsa (bosh ekran, kategoriya, rasm natijasi…) ro'yxat tepadan boshlanadi
        val modeKey = listOf(photoHits != null, shelfRows != null, polkaAdd, polkaView, special, priceRange, altFor?.key, catSel, favMode, noPhotoMode, q.isBlank()).toString()
        if (modeKey != lastModeKey) {
            lastModeKey = modeKey
            listView.smoothScrollBy(0, 0)   // aylanayotgan ro'yxatni to'xtatish
            listView.setSelection(0)
            listView.post { listView.smoothScrollBy(0, 0); listView.setSelection(0) }
        }
        subTitle.text = "Baza: ${baseDate()} · ${group(all.size.toLong())} tovar"
        fillChips()
        info.visibility = View.VISIBLE
        setBanner(false)
        photoHits?.let { hits ->
            // Rasm natijalari ichidan so'z bilan qisqartirish ("gul", "vaza"), o'xshashlik tartibi saqlanadi
            val list = hits.map { it.first }
            val keep = if (q.isBlank()) null else Search.run(list, q).map { it.key }.toHashSet()
            val res = if (keep == null) list else list.filter { it.key in keep }
            val rows = ArrayList<Any>()
            val codeAll = if (keep == null) photoCodes.flatMap { it.second }.distinctBy { it.key } else emptyList()
            val byCode = if (photoCodeDoubt) emptyList() else codeAll
            val lateCode = if (photoCodeDoubt) codeAll else emptyList()
            rows += "📷 Rasm natijasi" + (photoScopeShown?.let { " ($it-polkada)" } ?: "") + ": ${res.size + byCode.size} ta   ✕ yopish"
            if (byCode.isNotEmpty()) {
                // Rasmdagi kod o'qildi — aynan shu kodli tovar(lar) eng tepada
                rows += "🔤 Rasmdagi kod: " + photoCodes.joinToString(", ") { it.first } +
                    if (byCode.size > 1) " — ${byCode.size} ta, rasmga eng o'xshashi birinchi" else ""
                rows += TopHit(byCode[0])
                rows.addAll(byCode.drop(1))
                val codeKeys = byCode.map { it.key }.toHashSet()
                val rest = res.filter { it.key !in codeKeys }
                if (rest.isNotEmpty()) { rows += "Rasmga o'xshashlari"; rows.addAll(rest) }
            } else if (keep == null && res.isNotEmpty()) {
                // 1-o'rin alohida; uning rasmi yo'q seriya egizaklari (Visual.search ularni darrov keyin qo'yadi) — o'z sarlavhasi bilan
                rows += TopHit(res[0])
                val ser = Cats.series(res[0].name)
                var i = 1
                val twins = ArrayList<Product>()
                while (ser != null && i < res.size && res[i].photo.isBlank() && myPhoto(res[i]) == null && Cats.series(res[i].name) == ser) { twins += res[i]; i++ }
                if (twins.isNotEmpty()) { rows += "↔️ Shu seriyadan, o'lchami boshqa:"; rows.addAll(twins) }
                if (i < res.size) { rows += "Boshqa o'xshashlari"; rows.addAll(res.subList(i, res.size)) }
            } else rows.addAll(res)
            if (lateCode.isNotEmpty()) {
                // Kod o'qildi, lekin o'sha kodli tovarlar suratga o'xshamaydi — pastda, ogohlantirish bilan
                val lk = lateCode.map { it.key }.toHashSet()
                rows.removeAll { it is Product && it.key in lk }
                rows += "🔤 Rasmdagi «" + photoCodes.joinToString(", ") { it.first } + "» kodli tovarlar (rasmga o'xshamadi):"
                rows.addAll(lateCode)
            }
            shown = rows
            info.text = when {
                q.isBlank() -> "Ko'p chiqsa, nomini yozing (masalan: gul). To'g'ri tovarni uzoq bosing → ✅ Rasmdagi tovar shu — hamma telefon o'rganadi."
                res.isEmpty() -> "Rasm natijalari ichida \"${q.trim()}\" yo'q. ✕ yopib, oddiy qidiring."
                else -> "Rasm natijalari ichida \"${q.trim()}\": ${res.size} ta"
            }
            adapter.notifyDataSetChanged()
            return
        }
        if (shelfRows != null && polkaAdd != null && q.isBlank()) {
            shown = shelfRows!!
            info.text = "Har buyum ostida — ilova taklif qilgan tovarlar. To'g'risiga \"📍 qo'yish\". Yo'q bo'lsa, kodini yozib qidiring."
            info.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
            return
        }
        polkaAdd?.let { target ->
            // Joylash: bo'sh qidiruvda — shu qavatdagilar; yozilsa — butun bazadan (qo'shish uchun)
            val here = all.filter { Polka.matches(it, target) }.sortedBy { it.nameLower }
            val res = if (q.isBlank()) here else Search.run(all, q)
            shown = listOf<Any>("📍 ${Polka.label(target)} ga joylash: ${here.size} ta   ☰ menyu") + res
            info.text = when {
                q.isBlank() -> "Kod, nom yoki shtrix-kodni yozing yoki 📷 rasm bilan toping → \"📍 ga qo'yish\". Etaj tepadan sanaladi."
                res.isEmpty() -> "Topilmadi: \"${q.trim()}\""
                else -> "Topildi: ${res.size} ta — kerakligini \"📍 ga qo'yish\" bilan qo'shing"
            }
            adapter.notifyDataSetChanged()
            return
        }
        polkaView?.let { sel ->
            val list = if (sel == "?") all.filter { Polka.of(it).isEmpty() && Search.stockRank(it) == 0 }.sortedBy { it.nameLower }
                else all.filter { Polka.matches(it, sel) }
                    .sortedWith(compareBy<Product>({ p -> Polka.of(p).filter { if (sel.contains('-')) it == sel else Polka.stellaj(it) == sel }.minOf { Polka.sortKey(it) } }, { it.nameLower }))
            val res = if (q.isBlank()) list else Search.run(list, q)
            val title = when { sel == "?" -> "❓ Joyi yozilmagan (do'konda bor)"; sel.contains('-') -> "📍 ${Polka.label(sel)}"; else -> "📍 $sel-polka (hamma etajlar)" }
            shown = listOf<Any>("$title: ${res.size} ta   ✕ yopish") + res
            info.text = when {
                list.isEmpty() && sel != "?" -> "Bu yerga hali tovar joylanmagan. 📍 → Polkaga tovar joylash."
                q.isBlank() -> if (sel == "?") "Tovarni uzoq bosing → 📍 Joyini yozish. Yoki 📍 → Polkaga tovar joylash." else "Ichidan qidirish uchun yozing"
                res.isEmpty() -> "Topilmadi: \"${q.trim()}\""
                else -> "Topildi: ${res.size} ta"
            }
            adapter.notifyDataSetChanged()
            return
        }
        if (special != null || priceRange != null || altFor != null) {
            val (title, base) = specialList()
            val res = if (q.isBlank()) base else Search.run(base, q).let { r -> val k = r.map { it.key }.toHashSet(); base.filter { it.key in k } }
            shown = listOf<Any>("$title: ${res.size} ta   ✕ yopish") + res
            info.text = when {
                base.isEmpty() -> when {
                    special == "hot" -> "Ro'yxat hali yuklanmagan — internetni yoqib 🔄 ni bosing"
                    special == "new" -> "Oxirgi 20 kunda yangi tovar qo'shilmagan"
                    else -> "Mos tovar topilmadi"
                }
                q.isBlank() -> if (priceRange != null) "Faqat do'konda bor tovarlar. Bitta turini ko'rish uchun tepadagi tugmani bosing (masalan 🏺 Vazalar)" else "Ichidan qidirish uchun yozing"
                res.isEmpty() -> "Topilmadi: \"${q.trim()}\""
                else -> "Topildi: ${res.size} ta"
            }
            adapter.notifyDataSetChanged()
            return
        }
        priceQuery(q)?.let { v ->
            if (catSel == null && !favMode && !noPhotoMode) {
                // 💰 Narx bilan qidirish: "30000", "30 000", "30k", "30 ming" — optom yoki dona narxi aynan shu
                val opt = all.filter { v in it.optomList }.sortedWith(compareBy<Product>({ Search.stockRank(it) }, { it.nameLower }))
                val dona = all.filter { v in it.donaList && v !in it.optomList }.sortedWith(compareBy<Product>({ Search.stockRank(it) }, { it.nameLower }))
                val codes = Search.run(all, q).filter { p -> opt.none { it.key == p.key } && dona.none { it.key == p.key } }
                val rows = ArrayList<Any>()
                rows += "💰 Optom narxi ${fmt(v)}: ${opt.size} ta"; rows.addAll(opt)
                if (dona.isNotEmpty()) { rows += "💰 Dona narxi ${fmt(v)}: ${dona.size} ta"; rows.addAll(dona) }
                if (codes.isNotEmpty()) { rows += "🔤 Kodi «${q.trim()}» ga o'xshash: ${codes.size} ta"; rows.addAll(codes) }
                shown = rows
                info.text = if (opt.isEmpty() && dona.isEmpty()) "Narxi aynan ${fmt(v)} bo'lgan tovar yo'q. Oraliq kerak bo'lsa: 💰 Puliga mos tovar"
                    else "Narxi aynan ${fmt(v)} bo'lgan tovarlar (avval optom, keyin dona)"
                info.visibility = View.VISIBLE
                adapter.notifyDataSetChanged()
                return
            }
        }
        if (noPhotoMode) {
            // Omborda borlari tepada: avval shularni rasmga olish foydali
            val list = all.filter { it.photo.isBlank() }
                .sortedWith(compareBy<Product>({ Search.stockRank(it) }, { it.nameLower }))
            val res = if (q.isBlank()) list else Search.run(list, q)
            shown = listOf<Any>("🚫 Rasmi yo'q: ${res.size} ta   ✕ yopish") + res
            info.text = when {
                q.isBlank() -> "Tovarni uzoq bosing → 📸 Rasmini o'rgatish. Ichidan qidirish uchun yozing."
                res.isEmpty() -> "Rasmi yo'qlar ichida topilmadi: \"${q.trim()}\""
                else -> "Rasmi yo'qlar ichida topildi: ${res.size} ta"
            }
            adapter.notifyDataSetChanged()
            return
        }
        catSel?.let { (c, sub) ->
            val list = all.filter { p -> Cats.of(p).let { it.first == c && (sub == null || it.second == sub) } }
                .sortedWith(compareBy<Product>({ Search.stockRank(it) }, { it.nameLower }))
            val res = if (q.isBlank()) list else Search.run(list, q)
            val title = if (sub == null) c else "$c › $sub"
            shown = listOf<Any>("🗂 $title: ${res.size} ta   ✕ yopish") + res
            info.text = when {
                q.isBlank() -> "Kategoriya ichidan qidirish uchun yozing"
                res.isEmpty() -> "Bu kategoriyada topilmadi: \"${q.trim()}\""
                else -> "Kategoriya ichida topildi: ${res.size} ta"
            }
            adapter.notifyDataSetChanged()
            return
        }
        if (favMode) {
            val byKey = all.associateBy { it.key }
            val list = favs.mapNotNull { byKey[it] }.reversed()   // oxirgi qo'shilgani tepada
            val res = if (q.isBlank()) list else Search.run(list, q)
            shown = listOf<Any>("⭐ Sevimlilar: ${res.size} ta   ✕ yopish") + res
            info.text = when {
                list.isEmpty() -> "Sevimli tovar yo'q. Tovar yonidagi ☆ belgisini bosing."
                q.isBlank() -> "Sevimlilar ichidan qidirish uchun yozing"
                res.isEmpty() -> "Sevimlilar ichida topilmadi: \"${q.trim()}\""
                else -> "Sevimlilar ichida topildi: ${res.size} ta"
            }
            adapter.notifyDataSetChanged()
            return
        }
        if (q.isBlank() && !searchOpen && all.isNotEmpty() && catSel == null && !favMode && !noPhotoMode && special == null && priceRange == null && altFor == null) {
            shown = listOf<Any>(HomeTiles)
            info.visibility = View.GONE
            setBanner(true)
            adapter.notifyDataSetChanged()
            return
        }
        val exact = Search.run(all, q)
        val (ser, sim) = if (q.isBlank()) "" to emptyList<Product>() else Search.similar(all, Search.canonical(all, q), exact)
        val open = similarOpen || exact.isEmpty()
        val rows = ArrayList<Any>()
        if (exact.isNotEmpty() && sim.isNotEmpty()) rows += "🎯 Topildi: ${exact.size} ta"
        rows.addAll(exact)
        if (sim.isNotEmpty()) {
            rows += "🔍 Shu seriyadan ($ser): ${sim.size} ta  " + if (open) "▴" else "▾ ochish"
            if (open) rows.addAll(sim)
        }
        shown = rows
        info.text = when {
            all.isEmpty() -> "Baza bo'sh. Internetni yoqib 🔄 Yangilash ni bosing."
            q.isBlank() -> "Baza yangilangan: ${baseDate()}"
            exact.isEmpty() && sim.isEmpty() -> "Topilmadi: \"${q.trim()}\""
            exact.isEmpty() -> "Aniq \"${q.trim()}\" topilmadi. Shu seriyadan:"
            else -> "Topildi: ${exact.size} ta"
        }
        adapter.notifyDataSetChanged()
    }

    private fun baseDate(): String {
        val t = Db.updatedAt(this)
        return if (t == 0L) "ilova ichidagi" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(t))
    }

    private fun update(silent: Boolean) {
        if (busy) return
        busy = true
        refreshBtn.text = "⏳"; refreshBtn.isEnabled = false
        Thread {
            val res = try { Result.success(Db.download(this)) } catch (e: Exception) { Result.failure(e) }
            try { Visual.download(applicationContext) } catch (_: Exception) {}
            try { Cats.download(applicationContext) } catch (_: Exception) {}
            try { Cats.downloadOverrides(applicationContext) } catch (_: Exception) {}
            try { Polka.download(applicationContext) } catch (_: Exception) {}
            try { Hot.download(applicationContext) } catch (_: Exception) {}
            try { if (Fon.download(applicationContext)) main.post { Fon.bitmap(this)?.let { bannerImg.setImageBitmap(it) } } } catch (_: Exception) {}
            main.post {
                busy = false
                refreshBtn.text = "🔄"; refreshBtn.isEnabled = true
                res.onSuccess {
                    all = Db.load(this); codeIndex = null
                    // Kassadagi mahsulotlar yangi narxga o'tadi
                    val byKey = all.associateBy { it.key }
                    val old = cart.values.toList(); cart.clear()
                    old.forEach { ci -> val np = byKey[ci.p.key] ?: ci.p; cart[np.key] = CartItem(np, ci.count) }
                    refresh(); updateCart()
                    if (!silent) toast("✅ Baza yangilandi")
                }.onFailure {
                    if (!silent) toast("Internet yo'q yoki baza topilmadi. Eski baza bilan ishlayapsiz.")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- tovarga rasm yuklash
    private fun uploadStart() {
        val input = EditText(this).apply {
            hint = "Tovar kodi: 43-39"; isSingleLine = true; textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        AlertDialog.Builder(this).setTitle("📸 Qaysi tovarga rasm?")
            .setMessage("Kodini yoki nomini yozing")
            .setView(wrap)
            .setPositiveButton("Topish") { _, _ ->
                val found = Search.run(all, input.text.toString()).take(40)
                when (found.size) {
                    0 -> { toast("Topilmadi: ${input.text}"); uploadStart() }
                    1 -> uploadFor(found[0])
                    else -> AlertDialog.Builder(this).setTitle("Qaysi biri?")
                        .setItems(found.map { "${it.name}\n${it.code}" }.toTypedArray()) { _, i -> uploadFor(found[i]) }
                        .show()
                }
            }
            .setNegativeButton("Bekor", null).show()
        input.requestFocus()
    }

    private fun uploadFor(p: Product) {
        upProduct = p; upFiles.clear()
        uploadDialog()
    }

    private fun uploadDialog() {
        val p = upProduct ?: return
        val items = arrayOf("📷 Rasmga olish", "🖼 Galereyadan (bir nechtasini tanlash)",
            "⬆️ Yuklash (${upFiles.size} ta rasm)", "🗑 Tanlanganlarni tozalash")
        // Diqqat: setMessage bilan setItems birga ishlamaydi (ro'yxat ko'rinmay qoladi), shuning uchun izoh sarlavhada
        val hint = if (upFiles.isEmpty()) "3–4 xil tomondan rasmga oling" else "Tanlandi: ${upFiles.size} ta rasm"
        AlertDialog.Builder(this).setTitle("📸 ${p.name} · ${p.code}\n$hint")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> openUpCamera()
                    1 -> openUpGallery()
                    2 -> doUpload()
                    else -> { upFiles.clear(); uploadDialog() }
                }
            }
            .setNegativeButton("Bekor") { _, _ ->
                upFiles.clear(); upProduct = null
                val back = upDone; upDone = null; back?.invoke(0)
            }
            .show()
    }

    private fun upDir() = File(cacheDir, "cam").apply { mkdirs() }

    private fun openUpCamera() {
        if (upFiles.size >= 8) { toast("Bir tovarga 8 tagacha rasm"); uploadDialog(); return }
        try {
            val f = File(upDir(), "up_${System.currentTimeMillis()}.jpg")
            upPending = f
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQ_UP_CAM)
        } catch (e: Exception) { toast("Kamera ochilmadi"); uploadDialog() }
    }

    private fun openUpGallery() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, REQ_UP_GAL)
        } catch (e: Exception) { toast("Galereya ochilmadi"); uploadDialog() }
    }

    private fun uploadResult(req: Int, result: Int, data: Intent?) {
        if (result == RESULT_OK) {
            if (req == REQ_UP_CAM) {
                upPending?.takeIf { it.exists() && it.length() > 0 }?.let { upFiles += it }
            } else {
                val uris = ArrayList<Uri>()
                data?.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
                if (uris.isEmpty()) data?.data?.let { uris += it }
                for (u in uris) {
                    if (upFiles.size >= 8) { toast("Bir tovarga 8 tagacha rasm"); break }
                    try {
                        val f = File(upDir(), "up_${System.nanoTime()}.jpg")
                        contentResolver.openInputStream(u)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                        upFiles += f
                    } catch (e: Exception) { toast("Rasm ochilmadi") }
                }
            }
        }
        upPending = null
        uploadDialog()
    }

    private fun doUpload() {
        val p = upProduct ?: return
        if (upFiles.isEmpty()) { toast("Avval rasm oling yoki tanlang"); uploadDialog(); return }
        // Ilova ichidagi kalit; bo'lmasa (eski yig'ilish) — Billz ulanish kaliti
        val key = BuildConfig.RASM_APP_KEY.ifBlank { prefs().getString("key", "").orEmpty() }
        if (key.isEmpty()) { askKey { doUpload() }; return }
        val files = upFiles.toList()
        val wait = AlertDialog.Builder(this).setMessage("⬆️ ${files.size} ta rasm yuklanmoqda…").setCancelable(false).show()
        Thread {
            var ok = 0; var lastErr = ""; var unauthorized = false
            val sent = ArrayList<File>()
            for (f in files) {
                try {
                    val bmp = Visual.bitmap(this, Uri.fromFile(f), 1024)
                    val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                    val c = URL("$KASSA_API/api/kassa/rasm").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true
                    c.connectTimeout = 15000; c.readTimeout = 60000
                    c.setRequestProperty("Content-Type", "image/jpeg")
                    c.setRequestProperty("x-app-key", key)
                c.setRequestProperty("x-xodim", xodimCode())
                    c.setRequestProperty("x-sku", java.net.URLEncoder.encode(p.code.ifBlank { p.name }, "UTF-8"))
                    c.outputStream.use { it.write(bytes) }
                    val rc = c.responseCode
                    val t = (if (rc in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                    c.disconnect()
                    val j = try { org.json.JSONObject(t) } catch (e: Exception) { null }
                    when {
                        rc == 401 -> { unauthorized = true; break }
                        j?.optBoolean("ok") == true -> {
                            ok++; sent += f
                            try { Visual.learn(applicationContext, p, Visual.embed(applicationContext, bmp)) } catch (_: Throwable) {}
                        }
                        else -> lastErr = j?.optString("error")?.ifBlank { null } ?: "Server xatosi ($rc)"
                    }
                } catch (e: Exception) { lastErr = "Internet yo'q yoki server javob bermadi" }
            }
            if (sent.isNotEmpty()) {   // kartada darrov ko'rinsin (baza yangilanguncha) — HAMMA yuklangan rasmlar
                val dir = File(filesDir, "myphoto").apply { mkdirs() }
                val paths = ArrayList<String>()
                for ((n, f) in sent.reversed().withIndex()) try {   // oxirgisi birinchi
                    val out = File(dir, "${p.key.hashCode().toUInt()}_${System.currentTimeMillis()}_$n.jpg")
                    Visual.bitmap(this, Uri.fromFile(f), 640).let { b -> out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
                    paths += out.path
                } catch (_: Exception) {}
                val sp = getSharedPreferences("myphoto", MODE_PRIVATE)
                val old = (sp.getString(p.key + "#all", null)?.split('\n') ?: listOfNotNull(sp.getString(p.key, null))).filter { File(it).exists() }
                val all = (paths + old).distinct().take(12)
                if (all.isNotEmpty()) sp.edit().putString(p.key, all[0]).putString(p.key + "#all", all.joinToString("\n")).apply()
            }
            upKodId?.let { id ->   // 🏷 ro'yxatida ko'rinishi uchun kichik nusxa
                val dir = kodDir(id).apply { mkdirs() }
                for (f in sent) try {
                    val b = Visual.bitmap(this, Uri.fromFile(f), 640)
                    File(dir, "${System.currentTimeMillis()}_${f.name.hashCode().toUInt()}.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                } catch (_: Exception) {}
            }
            main.post {
                wait.dismiss()
                if (ok > 0) adapter.notifyDataSetChanged()
                if (unauthorized) {
                    if (BuildConfig.RASM_APP_KEY.isBlank()) prefs().edit().remove("key").apply()
                    alert("Ruxsat yo'q", if (BuildConfig.RASM_APP_KEY.isBlank()) "Ulanish kaliti noto'g'ri. Qayta yuklab, to'g'ri kalitni kiriting."
                        else "Server bu ilovani tanimadi. Eng yangi APK'ni o'rnating.")
                    return@post
                }
                sent.forEach { it.delete() }
                upFiles.removeAll(sent.toSet())
                val msg = buildString {
                    append("${p.name}\n\n✅ Yuklandi: $ok ta rasm")
                    if (ok < files.size) append("\n⚠️ Yuklanmadi: ${files.size - ok} ta — $lastErr")
                    if (ok > 0) append("\n\nBu telefonda darrov ishlaydi. Boshqa telefonlarda taxminan 1 soatda (🔄 bosilganda) ko'rinadi.")
                }
                AlertDialog.Builder(this).setTitle(if (ok == files.size) "✅ Tayyor" else "Yuklash").setMessage(msg)
                    .setPositiveButton(if (upDone != null) "✅ Tayyor" else "Boshqa tovar") { _, _ ->
                        upProduct = null; upFiles.clear()
                        val back = upDone; upDone = null
                        if (back != null) back(ok) else uploadStart()
                    }
                    .setNegativeButton(if (upFiles.isEmpty()) "Yopish" else "Qayta urinish") { _, _ ->
                        if (upFiles.isEmpty()) upProduct = null else uploadDialog()
                    }.show()
            }
        }.start()
    }

    /** Shu telefonda yuklangan rasm: baza yangilanguncha kartada darrov ko'rinsin. */
    private fun myPhoto(p: Product): String? =
        getSharedPreferences("myphoto", MODE_PRIVATE).getString(p.key, null)?.takeIf { File(it).exists() }?.let { "file:$it" }

    /** Shu telefonda yuklanganlar (yangisi birinchi). */
    private fun myPhotos(p: Product): List<String> {
        val sp = getSharedPreferences("myphoto", MODE_PRIVATE)
        val list = sp.getString(p.key + "#all", null)?.split('\n') ?: listOfNotNull(sp.getString(p.key, null))
        return list.filter { it.isNotBlank() && File(it).exists() }.map { "file:$it" }
    }

    /** Tovarning hamma rasmlari: shu telefonda yuklanganlar + bazadagi asosiy va qo'shimchalari. */
    private fun allPhotos(p: Product): List<String> {
        val hidden = hiddenPhotos()
        return (myPhotos(p) + p.photos + listOf(p.photo)).filter { it.isNotBlank() && it !in hidden }.distinct()
    }

    /** O'chirilgan rasmlar: baza yangilanguncha (30 daqiqa) ham darrov ko'rinmasin. */
    private fun hiddenPhotos(): Set<String> = getSharedPreferences("myphoto", MODE_PRIVATE).getStringSet("#hidden", emptySet()).orEmpty()

    private fun isBillzPhoto(u: String) = u.contains("billz", ignoreCase = true)

    /** Egasi: noto'g'ri qo'yilgan rasmni o'chirish. */
    private fun deletePhoto(p: Product, url: String, done: () -> Unit) {
        val sp = getSharedPreferences("myphoto", MODE_PRIVATE)
        fun hideLocal() {
            sp.edit().putStringSet("#hidden", hiddenPhotos() + url).apply()
            // shu telefonda olingan nusxa bo'lsa — o'shani ham
            val mine = (sp.getString(p.key + "#all", null)?.split('\n') ?: listOfNotNull(sp.getString(p.key, null))).filter { "file:$it" != url }
            sp.edit().putString(p.key + "#all", mine.joinToString("\n")).apply {
                if (mine.isEmpty()) remove(p.key) else putString(p.key, mine[0])
            }.apply()
            if (url.startsWith("file:")) File(url.removePrefix("file:")).delete()
            adapter.notifyDataSetChanged(); done()
        }
        when {
            url.startsWith("file:") -> AlertDialog.Builder(this).setMessage("Bu rasm faqat shu telefonda turibdi. O'chirilsinmi?")
                .setPositiveButton("🗑 O'chirish") { _, _ -> hideLocal(); toast("🗑 O'chirildi") }.setNegativeButton("Yo'q", null).show()
            isBillzPhoto(url) -> AlertDialog.Builder(this).setTitle("Bu rasm Billz'da turibdi")
                .setMessage("Uni Billz'ning o'zidan o'chirish kerak: Billz → Tovarlar → ${p.name} → rasm → o'chirish. Keyin 30 daqiqada ilovadan ham yo'qoladi.\n\nHozircha ilovada yashirib turaymi?")
                .setPositiveButton("🙈 Yashirish") { _, _ -> hideLocal(); toast("Yashirildi (shu telefonda)") }
                .setNegativeButton("Yo'q", null).show()
            else -> AlertDialog.Builder(this).setTitle("🗑 Rasm o'chirilsinmi?")
                .setMessage("Bu rasm ilova orqali yuklangan. U serverdan o'chiriladi va 30 daqiqada hamma telefonda yo'qoladi (rasm bilan qidirishda ham).")
                .setPositiveButton("🗑 O'chirish") { _, _ ->
                    val wait = AlertDialog.Builder(this).setMessage("⏳ O'chirilmoqda…").setCancelable(false).show()
                    Thread {
                        // Serverdagi yozuvni url bo'yicha topamiz (Billz'ga qo'shilganlari ham)
                        val r = try {
                            val c = URL("$KASSA_API/api/public/rasmlar?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                            c.connectTimeout = 15000; c.readTimeout = 30000
                            val arr = org.json.JSONObject(c.inputStream.use { it.bufferedReader().readText() }).getJSONArray("items"); c.disconnect()
                            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("url") == url }
                                ?.let { Rasm(it.optString("sku"), it.optString("name"), it.optString("url"), it.optLong("at")) }
                        } catch (_: Exception) { null }
                        main.post {
                            wait.dismiss()
                            if (r == null) { hideLocal(); toast("Serverda topilmadi — shu telefonda yashirildi"); return@post }
                            markRasmlar(listOf(r), remove = true) { ok -> if (ok) hideLocal() }
                        }
                    }.start()
                }.setNegativeButton("Yo'q", null).show()
        }
    }

    // ---------------------------------------------------------------- yuklangan rasmlar (egasi uchun)
    private class Rasm(val sku: String, val name: String, val url: String, val at: Long)

    private fun rasmlarList() {
        val wait = AlertDialog.Builder(this).setMessage("⏳ Rasmlar yuklanmoqda…").setCancelable(false).show()
        Thread {
            val res = try {
                val c = URL("$KASSA_API/api/public/rasmlar?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val t = c.inputStream.use { it.bufferedReader().readText() }; c.disconnect()
                val arr = org.json.JSONObject(t).getJSONArray("items")
                val list = ArrayList<Rasm>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (!o.optBoolean("done")) list += Rasm(o.optString("sku"), o.optString("name"), o.optString("url"), o.optLong("at"))
                }
                Result.success(list)
            } catch (e: Exception) { Result.failure(e) }
            main.post {
                wait.dismiss()
                res.onSuccess { showRasmlar(it) }.onFailure { alert("Ochilmadi", "Rasmlar ro'yxati olinmadi. Internetni tekshiring yoki server hali yangilanmagan bo'lishi mumkin.") }
            }
        }.start()
    }

    private fun showRasmlar(items: List<Rasm>) {
        val byCode = all.filter { it.code.isNotBlank() }.groupBy { it.code }
        val groups = items.groupBy { it.sku }.entries.sortedByDescending { e -> e.value.maxOf { it.at } }
            .map { it.key to it.value }.toMutableList()
        if (groups.isEmpty()) { alert("🖼 Yuklangan rasmlar", "Billz'ga qo'shilmagan rasm yo'q. ✅"); return }
        var dlg: AlertDialog? = null
        lateinit var ad: BaseAdapter
        ad = object : BaseAdapter() {
            override fun getCount() = groups.size
            override fun getItem(i: Int) = groups[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val (sku, rs) = groups[i]
                val p = byCode[sku]?.firstOrNull()
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; background = box(Color.WHITE, 12)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener {
                        rasmMenu(sku, p, rs) { done ->
                            if (done) { groups.removeAt(groups.indexOfFirst { it.first == sku }); ad.notifyDataSetChanged()
                                dlg?.setTitle("🖼 Billz'ga qo'shilmagan: ${groups.size} ta tovar") }
                        }
                    }
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = (p?.name ?: "Bazada yo'q") + "\n" + sku + " · ${rs.size} ta rasm"
                    textSize = 15f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
                })
                val strip = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                for (r in rs.take(4)) {
                    val iv = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.parseColor("#EEEEEE")) }
                    Img.load(this@MainActivity, r.url, iv)
                    strip.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(6); topMargin = dp(6) })
                }
                row.addView(strip)
                return LinearLayout(this@MainActivity).apply { setPadding(0, dp(4), 0, dp(4)); addView(row, -1, -2) }
            }
        }
        val list = ListView(this).apply { adapter = ad; divider = null; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        dlg = AlertDialog.Builder(this).setTitle("🖼 Billz'ga qo'shilmagan: ${groups.size} ta tovar")
            .setView(list).setPositiveButton("Yopish", null).show()
    }

    private fun rasmMenu(sku: String, p: Product?, rs: List<Rasm>, after: (Boolean) -> Unit) {
        AlertDialog.Builder(this).setTitle((p?.name ?: sku) + " · $sku")
            .setItems(arrayOf("📤 Rasmlarni ulashish / saqlash (${rs.size} ta)", "📋 Kodni nusxalash") +
                (if (isOwner()) arrayOf("✅ Billz'ga qo'shildi", "🗑 Rasmlarni o'chirish") else emptyArray())) { _, w ->
                when (w) {
                    0 -> shareRasmlar(rs)
                    1 -> {
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("kod", sku))
                        toast("Nusxalandi: $sku")
                    }
                    2 -> markRasmlar(rs, remove = false, after = after)
                    else -> AlertDialog.Builder(this).setMessage("${rs.size} ta rasm o'chirilsinmi?")
                        .setPositiveButton("O'chirish") { _, _ -> markRasmlar(rs, remove = true, after = after) }
                        .setNegativeButton("Yo'q", null).show()
                }
            }.show()
    }

    private fun shareRasmlar(rs: List<Rasm>) {
        val wait = AlertDialog.Builder(this).setMessage("⏳ Tayyorlanmoqda…").setCancelable(false).show()
        Thread {
            val uris = ArrayList<Uri>()
            val dir = File(cacheDir, "share").apply { mkdirs() }
            for (r in rs) try {
                val f = File(dir, r.name.substringAfterLast('_').let { "tovar_$it" })
                if (!f.exists()) {
                    val c = URL(r.url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }; c.disconnect()
                }
                uris += FileProvider.getUriForFile(this, "$packageName.files", f)
            } catch (_: Exception) {}
            main.post {
                wait.dismiss()
                if (uris.isEmpty()) { toast("Rasmlar yuklanmadi"); return@post }
                val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
                    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
                i.type = "image/jpeg"; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(Intent.createChooser(i, "Rasmlarni ulashish")) } catch (e: Exception) { toast("Ulashib bo'lmadi") }
            }
        }.start()
    }

    private fun markRasmlar(rs: List<Rasm>, remove: Boolean, after: (Boolean) -> Unit) {
        val key = BuildConfig.RASM_APP_KEY.ifBlank { prefs().getString("key", "").orEmpty() }
        if (key.isEmpty()) { askKey { markRasmlar(rs, remove, after) }; return }
        Thread {
            var ok = 0; var code = 0
            for (r in rs) try {
                val c = URL("$KASSA_API/api/kassa/rasm-belgi").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-app-key", key)
                c.setRequestProperty("x-xodim", xodimCode())
                c.outputStream.use { it.write(org.json.JSONObject().put("name", r.name).put("done", true).put("remove", remove).toString().toByteArray()) }
                code = c.responseCode; c.disconnect()
                if (code in 200..299) ok++ else if (code == 401) break
            } catch (_: Exception) {}
            main.post {
                if (code == 401) {
                    if (BuildConfig.RASM_APP_KEY.isBlank()) prefs().edit().remove("key").apply()
                    alert("Ruxsat yo'q", "Eng yangi APK'ni o'rnating yoki ulanish kalitini qayta kiriting."); return@post
                }
                toast(if (ok == rs.size) (if (remove) "🗑 O'chirildi" else "✅ Belgilandi") else "Bajarildi: $ok / ${rs.size}")
                after(ok == rs.size)
            }
        }.start()
    }

    // ---------------------------------------------------------------- ilovani yangilash
    private var updateBusy = false

    /** GitHub'dagi eng yangi relizni tekshiradi; yangisi bo'lsa yuklab, o'rnatishni taklif qiladi. */
    private fun checkAppUpdate(silent: Boolean) {
        if (updateBusy) return
        updateBusy = true
        Thread {
            val res = try {
                val c = URL("https://api.github.com/repos/maxmuda725-jpg/turfa-narx/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val j = org.json.JSONObject(c.inputStream.use { it.bufferedReader().readText() }); c.disconnect()
                val tag = j.optString("tag_name")                         // "v1.45"
                val num = tag.substringAfterLast('.').toIntOrNull() ?: 0
                val assets = j.optJSONArray("assets")
                var url = ""
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                Result.success(Triple(num, tag, url))
            } catch (e: Exception) { Result.failure(e) }
            main.post {
                updateBusy = false
                res.onSuccess { (num, tag, url) ->
                    val mine = BuildConfig.VERSION_CODE
                    if (num > mine && url.isNotEmpty()) {
                        AlertDialog.Builder(this).setTitle("⬆️ Yangi versiya: $tag")
                            .setMessage("Sizda: v${BuildConfig.VERSION_NAME}\nYangilansinmi? (~50 MB, Wi-Fi'da yaxshi)")
                            .setPositiveButton("Yangilash") { _, _ -> downloadUpdate(url, tag) }
                            .setNegativeButton("Keyin", null).show()
                    } else if (!silent) toast("✅ Eng yangi versiya: v${BuildConfig.VERSION_NAME}")
                }.onFailure { if (!silent) toast("Yangilanishni tekshirib bo'lmadi (internet?)") }
            }
        }.start()
    }

    private fun downloadUpdate(url: String, tag: String) {
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val txt = TextView(this).apply { text = "Yuklanmoqda…"; setPadding(0, dp(8), 0, 0) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(bar, -1, -2); addView(txt)
        }
        val dlg = AlertDialog.Builder(this).setTitle("⬆️ $tag").setView(box).setCancelable(false).show()
        Thread {
            val f = File(File(cacheDir, "update").apply { mkdirs() }, "TURFA-Narxlar.apk")
            val ok = try {
                var u = URL(url)
                var c: HttpURLConnection
                var hops = 0
                while (true) {   // GitHub boshqa domenga yo'naltiradi
                    c = u.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15000; c.readTimeout = 60000
                    val code = c.responseCode
                    if (code in 300..399 && hops++ < 5) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue }
                    if (code != 200) throw Exception("HTTP $code")
                    break
                }
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { inp -> f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; main.post { bar.progress = pct; txt.text = "Yuklanmoqda… $pct%" } }
                    }
                } }
                c.disconnect()
                total <= 0 || done == total
            } catch (e: Exception) { false }
            main.post {
                dlg.dismiss()
                if (ok) installApk(f) else alert("Yuklanmadi", "Internetni tekshirib, qayta urinib ko'ring (🔄 ni uzoq bosing).")
            }
        }.start()
    }

    private fun installApk(f: File) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("Ruxsat kerak")
                .setMessage("Ilovani yangilash uchun \"Noma'lum ilovalarni o'rnatish\" ga ruxsat bering, keyin ilovaga qaytib 🔄 tugmasini uzoq bosing.")
                .setPositiveButton("Sozlamalar") { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                    catch (e: Exception) { toast("Sozlamalar ochilmadi") }
                }
                .setNegativeButton("Bekor", null).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) { alert("O'rnatib bo'lmadi", e.message ?: "xato") }
    }

    private fun askPin(pin: String, title: String, ok: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 24f; gravity = Gravity.CENTER
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        val d = AlertDialog.Builder(this).setTitle(title).setView(wrap).setCancelable(false)
            .setPositiveButton("Kirish", null)
            .setNegativeButton("Chiqish") { _, _ -> finish() }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() == pin) { d.dismiss(); ok() }
                else { input.text.clear(); input.error = "Noto'g'ri PIN" }
            }
        }
        d.show()
    }
}
