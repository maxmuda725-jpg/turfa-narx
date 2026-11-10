package uz.turfa.narx

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
            if (w.any { it.isDigit() }) w in p.tokens
            else p.nameLower.contains(w)
        }

    fun run(all: List<Product>, query: String): List<Product> {
        val words = tokens(query).toList()
        if (words.isEmpty()) return emptyList()
        return all.filter { matches(it, words) }.take(300)
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
    private var shown: List<Product> = emptyList()

    // Kassa (faqat hisob-kitob, Billz'ga hech narsa yuborilmaydi)
    private val cart = LinkedHashMap<String, CartItem>()
    private var optomMode = false

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
        if (kassaScreen.visibility == View.VISIBLE) showKassa(false) else super.onBackPressed()
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
        refreshBtn = pill("🔄 Yangilash", GREEN, Color.WHITE) { update(silent = false) }
        top.addView(refreshBtn, LinearLayout.LayoutParams(-2, dp(44)))
        screen.addView(top)

        search = EditText(this).apply {
            hint = "Kod yoki nom: 43-25"; textSize = 20f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = box(Color.WHITE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        screen.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(12), dp(12), dp(4)) })

        info = TextView(this).apply { textSize = 13f; setTextColor(Color.GRAY); setPadding(dp(16), dp(4), dp(16), dp(4)) }
        screen.addView(info)

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View = productRow(shown[p])
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
        card.addView(head)

        titleBox.addView(TextView(this).apply {
            text = p.name; textSize = 17f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
        })
        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sub.addView(TextView(this).apply { text = p.code; textSize = 12f; setTextColor(Color.GRAY) },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (p.qty.isNotBlank()) {
            val n = p.qty.toDoubleOrNull()?.toLong() ?: 0
            val c = if (n > 0) ORANGE else RED
            sub.addView(TextView(this).apply {
                text = if (n > 0) "Qoldiq: ${group(n)} ta" else "Tugagan"
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
                updateCart(); adapter.notifyDataSetChanged()
            }, LinearLayout.LayoutParams(-2, dp(44)))
        card.addView(addRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val wrap = LinearLayout(this).apply { setPadding(dp(4), dp(5), dp(4), dp(5)) }
        wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
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
            text = "Faqat hisob-kitob. Billz kassasiga yuborilmaydi."
            textSize = 11f; setTextColor(Color.parseColor("#999999")); setPadding(0, dp(4), 0, 0)
        })
        screen.addView(totalBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(12)) })
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
        shown = Search.run(all, q)
        info.text = when {
            all.isEmpty() -> "Baza bo'sh. Internetni yoqib 🔄 Yangilash ni bosing."
            q.isBlank() -> "Baza yangilangan: ${baseDate()}"
            shown.isEmpty() -> "Topilmadi: \"${q.trim()}\""
            else -> "Topildi: ${shown.size} ta"
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
        refreshBtn.text = "⏳ Yuklanmoqda"; refreshBtn.isEnabled = false
        Thread {
            val res = try { Result.success(Db.download(this)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                busy = false
                refreshBtn.text = "🔄 Yangilash"; refreshBtn.isEnabled = true
                res.onSuccess {
                    all = Db.load(this)
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
