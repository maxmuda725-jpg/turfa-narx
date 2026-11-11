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
            codeSet = all.flatMapTo(HashSet()) { p -> p.tokens.filter { t -> t.any { it.isDigit() } } }
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
        if (d.length in 3..9 && d.all { it.isDigit() }) {
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
                { stockRank(it) },                                                                           // keyin omborda borlar
            ))
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
        val iPhoto = idx("rasm", "photo", "image", "фото")

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

    fun load(ctx: Context, url: String, view: ImageView, big: Boolean = false) {
        val key = (if (big) "b:" else "s:") + url
        view.tag = key
        mem.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        pool.execute {
            val bmp = try {
                val f = file(ctx, url, big)
                if (!f.exists()) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000; c.readTimeout = 20000
                    val bytes = c.inputStream.use { it.readBytes() }; c.disconnect()
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
    private val GREEN = Color.parseColor("#0F7B5F")
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
    private val REQ_TEACH = 75
    private var photoHits: List<Pair<Product, Float>>? = null
    private var photoVec: FloatArray? = null
    private val photoLearned = HashSet<String>()
    private var teachFor: Product? = null
    // Sevimlilar (shu telefonda saqlanadi)
    private val favs = LinkedHashSet<String>()
    private var favMode = false
    // Kategoriya: (kategoriya, bo'lim yoki null = hammasi)
    private var catSel: Pair<String, String?>? = null

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
        val root = FrameLayout(this).apply { setBackgroundColor(BG); fitsSystemWindows = true }
        searchScreen = buildSearch()
        kassaScreen = buildKassa().apply { visibility = View.GONE }
        root.addView(searchScreen, -1, -1)
        root.addView(kassaScreen, -1, -1)
        setContentView(root)
        refresh(); updateCart()
        askPin(SELLER_PIN, "Do'konchi PIN-kodi") { update(silent = true) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            kassaScreen.visibility == View.VISIBLE -> showKassa(false)
            photoHits != null -> closePhoto()
            favMode -> { favMode = false; refresh() }
            catSel != null -> { catSel = null; refresh() }
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

    // ---------------------------------------------------------------- qidiruv ekrani
    private fun buildSearch(): View {
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = topBar()
        top.addView(title("TUR-FA narxlar"), LinearLayout.LayoutParams(0, -2, 1f))
        refreshBtn = pill("🔄", GREEN, Color.WHITE, 18f) { update(silent = false) }
        top.addView(pill("🗂", GREEN, Color.WHITE, 18f) { catMenu() },
            LinearLayout.LayoutParams(dp(52), dp(44)).apply { rightMargin = dp(6) })
        top.addView(pill("⭐", GREEN, Color.WHITE, 18f) { toggleFavMode() },
            LinearLayout.LayoutParams(dp(52), dp(44)).apply { rightMargin = dp(6) })
        top.addView(refreshBtn, LinearLayout.LayoutParams(-2, dp(44)))
        screen.addView(top)

        search = EditText(this).apply {
            hint = "Kod yoki nom: 43-25"; textSize = 20f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = box(Color.WHITE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(pill("📷", Color.WHITE, GREEN, 22f) { photoMenu() },
            LinearLayout.LayoutParams(dp(60), dp(56)).apply { leftMargin = dp(8) })
        screen.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(12), dp(12), dp(4)) })

        info = TextView(this).apply { textSize = 13f; setTextColor(Color.GRAY); setPadding(dp(16), dp(4), dp(16), dp(4)) }
        screen.addView(info)

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View =
                when (val r = shown[p]) { is Product -> productRow(r); else -> sectionHeader(r as String) }
        }
        val list = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            divider = null; clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(12))
            setOnTouchListener { v, _ -> hideKb(v); false }
        }
        screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        kassaBar = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = box(INK, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { showKassa(true) }
        }
        screen.addView(kassaBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(12)) })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        return screen
    }

    private fun productRow(p: Product): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(Color.WHITE, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (p.photo.isNotBlank()) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = box(Color.parseColor("#EEEEEE"), 10)
                clipToOutline = true
                setOnClickListener { showPhoto(p) }
            }
            Img.load(this, p.photo, iv)
            head.addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(12) })
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val fav = p.key in favs
        head.addView(TextView(this).apply {
            text = if (fav) "★" else "☆"; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(if (fav) Color.parseColor("#F2A900") else Color.parseColor("#BBBBBB"))
            setOnClickListener { toggleFav(p) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        card.addView(head)

        titleBox.addView(TextView(this).apply {
            text = p.name; textSize = 17f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
        })
        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sub.addView(TextView(this).apply { text = p.code; textSize = 12f; setTextColor(Color.GRAY) },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (p.qty.isNotBlank()) {
            val n = p.qty.toDoubleOrNull()?.toLong() ?: 0
            val c = if (n > 0) GREEN else RED
            sub.addView(TextView(this).apply {
                text = if (n > 0) "✅ Bor" else "❌ Tugagan"
                textSize = 13f; setTextColor(c); typeface = Typeface.DEFAULT_BOLD
                background = box(tint(c, 30), 8); setPadding(dp(8), dp(3), dp(8), dp(3))
            })
        }
        titleBox.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val prices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prices.addView(priceBox("DONA", p.donaText, p.donaList.size > 1, GREEN),
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        prices.addView(priceBox("OPTOM", p.optomText, p.optomList.size > 1, BLUE),
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        card.addView(prices, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (p.donaList.size > 1 || p.optomList.size > 1) card.addView(TextView(this).apply {
            text = "Do'konga qarab farq qiladi, kassadan aniqlang"
            textSize = 12f; setTextColor(ORANGE); setPadding(0, dp(6), 0, 0)
        })

        // Kassaga qo'shish
        val inCart = cart[p.key]?.count ?: 0
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addRow.addView(TextView(this).apply {
            text = if (inCart > 0) "🧾 Kassada: ${group(inCart)} ta" else ""
            textSize = 14f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (p.donaList.isNotEmpty()) addRow.addView(
            pill("＋ Kassaga", Color.WHITE, INK, 15f) {
                val ci = cart.getOrPut(p.key) { CartItem(p, 0) }
                ci.count += 1
                rememberPhoto(p)
                if (Search.stockRank(p) == 2 && ci.count == 1L) toast("Bazada tugagan, lekin kassaga qo'shildi")
                updateCart(); adapter.notifyDataSetChanged()
            }, LinearLayout.LayoutParams(-2, dp(44)))
        card.addView(addRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        card.setOnLongClickListener { productMenu(p); true }

        if (Search.stockRank(p) == 2) card.background = box(Color.parseColor("#E6E6E6"), 14)   // tugagan — kulrang (kassaga baribir qo'shiladi)

        val wrap = LinearLayout(this).apply { setPadding(dp(4), dp(5), dp(4), dp(5)) }
        wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun sectionHeader(t: String): View {
        val tv = TextView(this).apply {
            text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (t.startsWith("🔍")) BLUE else INK)
            setPadding(dp(12), dp(14), dp(12), dp(8))
            if (t.startsWith("🗂")) {
                background = box(tint(BLUE, 22), 12)
                setOnClickListener { catSel = null; refresh() }
            }
            if (t.startsWith("⭐")) {
                background = box(tint(Color.parseColor("#F2A900"), 34), 12)
                setOnClickListener { favMode = false; refresh() }
            }
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
            background = box(tint(color), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(TextView(context).apply {
                text = if (two) "$label0 · ⚠️ 2 xil narx" else label0
                textSize = 11f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = v; textSize = if (two) 16f else 19f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
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
        kassaBar.text = if (cart.isEmpty()) "🧾 Kassa bo'sh"
            else "🧾 Kassa: ${group(pieces)} ta · ${fmt(total)}  ›"
        totalSub.text = "JAMI ($mode narxda) · ${cart.size} xil, ${group(pieces)} ta"
        totalText.text = if (total > 0) fmt(total) else "0 so'm"
        emptyCart.visibility = if (cart.isEmpty()) View.VISIBLE else View.GONE
        cartAdapter.notifyDataSetChanged()
    }

    private fun showKassa(show: Boolean) {
        hideKb(search)
        kassaScreen.visibility = if (show) View.VISIBLE else View.GONE
        searchScreen.visibility = if (show) View.GONE else View.VISIBLE
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
        if (resultCode != RESULT_OK) return
        val uri = when (requestCode) {
            REQ_GALLERY, REQ_PHOTO_GAL -> data?.data
            REQ_CAMERA, REQ_PHOTO_CAM, REQ_TEACH -> camUri
            else -> null
        } ?: return
        when (requestCode) {
            REQ_PHOTO_CAM, REQ_PHOTO_GAL -> photoSearch(uri)
            REQ_TEACH -> teachFor?.let { teachSave(it, uri) }
            else -> recognize(uri)
        }
    }

    // ---------------------------------------------------------------- rasm orqali qidirish
    private fun photoMenu() {
        AlertDialog.Builder(this).setTitle("📷 Tovarni rasm orqali topish")
            .setItems(arrayOf("📷 Kamera bilan rasmga olish", "🖼 Galereyadan")) { _, w ->
                if (w == 0) openCamera(REQ_PHOTO_CAM) else openGallery(REQ_PHOTO_GAL)
            }.show()
    }

    private fun photoSearch(uri: Uri) {
        if (!Visual.ready()) {
            alert("Rasm bazasi yo'q", "Rasm orqali qidirish uchun bazani yuklash kerak. Internetni yoqib 🔄 Yangilash ni bosing.")
            return
        }
        val wait = AlertDialog.Builder(this).setMessage("📷 Rasmga o'xshash tovarlar qidirilmoqda…").setCancelable(false).show()
        Thread {
            val res = try {
                val v = Visual.embed(applicationContext, Visual.bitmap(this, uri))
                Result.success(v to Visual.search(v, all))
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                wait.dismiss()
                res.onSuccess { (v, hits) ->
                    photoVec = v; photoHits = hits; photoLearned.clear(); favMode = false; catSel = null
                    hideKb(search)
                    if (search.text.isNotEmpty()) search.setText("")
                    refresh()
                    if (hits.isEmpty()) toast("O'xshash tovar topilmadi")
                }.onFailure { e ->
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

    private fun openCat(c: String, sub: String?) {
        catSel = c to sub
        favMode = false; photoHits = null; photoVec = null
        hideKb(search)
        if (search.text.isNotEmpty()) search.setText("")
        refresh()
    }

    // ---------------------------------------------------------------- sevimlilar
    private fun toggleFav(p: Product) {
        if (!favs.remove(p.key)) { favs += p.key; toast("⭐ Sevimlilarga qo'shildi") }
        getSharedPreferences("sevimli", MODE_PRIVATE).edit().putStringSet("keys", HashSet(favs)).apply()
        if (favMode) refresh() else adapter.notifyDataSetChanged()
    }

    private fun toggleFavMode() {
        favMode = !favMode
        if (favMode) { photoHits = null; photoVec = null; catSel = null }
        hideKb(search)
        refresh()
    }

    private fun closePhoto() {
        photoHits = null; photoVec = null
        refresh()
    }

    /** Rasm bo'yicha topilgan tovar kassaga qo'shilsa — telefon shu rasmni eslab qoladi. */
    private fun rememberPhoto(p: Product) {
        val v = photoVec ?: return
        if (photoHits?.any { it.first.key == p.key } != true || !photoLearned.add(p.key)) return
        Thread { Visual.learn(applicationContext, p, v) }.start()
    }

    private fun productMenu(p: Product) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (photoVec != null && photoHits?.any { it.first.key == p.key } == true && p.key !in photoLearned) {
            labels += "✅ Rasmdagi tovar shu (eslab qol)"
            actions += { rememberPhoto(p); toast("✅ Eslab qolindi") }
        }
        labels += "📷 Rasmini o'rgatish (kamera)"
        actions += { teachFor = p; openCamera(REQ_TEACH) }
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
    private fun showPhoto(p: Product) {
        val iv = ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(Color.WHITE) }
        Img.load(this, p.photo, iv, big = true)
        val d = AlertDialog.Builder(this).setTitle(p.name).setView(iv).setPositiveButton("Yopish", null).create()
        iv.setOnClickListener { d.dismiss() }
        d.show()
    }

    private fun refresh() {
        val q = search.text.toString()
        if (q != lastQuery) { similarOpen = false; lastQuery = q }
        photoHits?.let { hits ->
            // Rasm natijalari ichidan so'z bilan qisqartirish ("gul", "vaza"), o'xshashlik tartibi saqlanadi
            val list = hits.map { it.first }
            val keep = if (q.isBlank()) null else Search.run(list, q).map { it.key }.toHashSet()
            val res = if (keep == null) list else list.filter { it.key in keep }
            shown = listOf<Any>("📷 Rasmga eng o'xshashlari: ${res.size} ta   ✕ yopish") + res
            info.text = when {
                q.isBlank() -> "Ko'p chiqsa, nomini yozing (masalan: gul). To'g'ri tovarni \"＋ Kassaga\" bosing — telefon eslab qoladi."
                res.isEmpty() -> "Rasm natijalari ichida \"${q.trim()}\" yo'q. ✕ yopib, oddiy qidiring."
                else -> "Rasm natijalari ichida \"${q.trim()}\": ${res.size} ta"
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
