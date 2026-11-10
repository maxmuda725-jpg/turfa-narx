package uz.turfa.narx

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
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
import android.util.LruCache

// ======================= SOZLAMALAR =======================
// Do'konchilar kiradigan PIN
const val SELLER_PIN = "1234"
// Baza shu manzildan yuklanadi (GitHub). Tannarx bu faylda YO'Q.
const val BASE_URL = "https://raw.githubusercontent.com/maxmuda725-jpg/turfa-narx/main/baza/products.csv"
// ==========================================================

data class Product(val name: String, val code: String, val barcode: String, val dona: String, val optom: String, val qty: String, val photo: String = "") {
    val nameLower = name.lowercase()
    val tokens: Set<String> = Search.tokens(name) + Search.tokens(code) + Search.tokens(barcode)
}

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
                dona = money(c.getOrNull(iDona)),
                optom = if (iOptom >= 0) money(c.getOrNull(iOptom)) else "—",
                qty = if (iQty >= 0) c.getOrNull(iQty)?.trim().orEmpty() else "",
                photo = if (iPhoto >= 0) c.getOrNull(iPhoto)?.trim().orEmpty() else "",
            )
        }
        return out
    }

    /** "17600" -> "17 600 so'm";  "11200-17600" -> "11 200 – 17 600 so'm" */
    private fun money(s: String?): String {
        if (s.isNullOrBlank()) return "—"
        val nums = s.replace("\u00A0", "").replace(" ", "").replace(",", ".")
            .split('-', '–')
            .mapNotNull { it.filter { ch -> ch.isDigit() || ch == '.' }.toDoubleOrNull()?.toLong() }
            .filter { it > 0 }
        if (nums.isEmpty()) return "—"
        val lo = nums.min(); val hi = nums.max()
        return if (lo == hi) fmt(lo) else "${group(lo)} / ${fmt(hi)}"
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

fun group(n: Long): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

fun fmt(n: Long): String = if (n <= 0) "—" else group(n) + " so'm"

class MainActivity : Activity() {
    private val GREEN = Color.parseColor("#0F7B5F")
    private val BLUE = Color.parseColor("#1F4E9E")
    private val BG = Color.parseColor("#F4F5F7")
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private lateinit var refreshBtn: Button

    private var all: List<Product> = emptyList()
    private var shown: List<Product> = emptyList()
    private lateinit var adapter: BaseAdapter
    private lateinit var info: TextView
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        all = Db.load(this)
        buildUi()
        askPin(SELLER_PIN, "Do'konchi PIN-kodi", cancelable = false) { update(silent = true) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(color: Int, radius: Int = 12): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        // Yuqori panel
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(8), dp(14))
            setBackgroundColor(GREEN)
        }
        top.addView(TextView(this).apply {
            text = "TUR-FA narxlar"
            setTextColor(Color.WHITE); textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        refreshBtn = Button(this).apply {
            text = "🔄 Yangilash"; textSize = 15f; isAllCaps = false
            setTextColor(GREEN); typeface = Typeface.DEFAULT_BOLD
            background = box(Color.WHITE, 22)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { update(silent = false) }
        }
        top.addView(refreshBtn, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(top)

        search = EditText(this).apply {
            hint = "Kod yoki nom: 43-25"
            textSize = 20f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = box(Color.WHITE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(12), dp(12), dp(4))
        })

        info = TextView(this).apply {
            textSize = 13f; setTextColor(Color.GRAY)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        root.addView(info)

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View =
                row(shown[p])
        }
        val list = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            divider = null
            setPadding(dp(8), 0, dp(8), dp(16))
            clipToPadding = false
            setOnTouchListener { v, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0); false
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        setContentView(root)
        refresh()
    }

    private fun row(p: Product): View {
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
            text = p.name; textSize = 17f
            setTextColor(Color.parseColor("#1A1A1A"))
            typeface = Typeface.DEFAULT_BOLD
        })
        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sub.addView(TextView(this).apply {
            text = p.code; textSize = 12f; setTextColor(Color.GRAY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (p.qty.isNotBlank()) {
            val n = p.qty.toDoubleOrNull()?.toLong() ?: 0
            val c = if (n > 0) Color.parseColor("#B26A00") else Color.parseColor("#C62828")
            sub.addView(TextView(this).apply {
                text = if (n > 0) "Qoldiq: ${group(n)} ta" else "Tugagan"
                textSize = 13f; setTextColor(c); typeface = Typeface.DEFAULT_BOLD
                background = box(Color.argb(30, Color.red(c), Color.green(c), Color.blue(c)), 8)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            })
        }
        titleBox.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val prices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prices.addView(price("DONA", p.dona, GREEN), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        prices.addView(price("OPTOM", p.optom, BLUE), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        card.addView(prices, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (p.dona.contains(" / ") || p.optom.contains(" / ")) card.addView(TextView(this).apply {
            text = "Do'konga qarab farq qiladi, kassadan aniqlang"
            textSize = 12f; setTextColor(Color.parseColor("#B26A00"))
            setPadding(0, dp(6), 0, 0)
        })

        val wrap = LinearLayout(this).apply { setPadding(dp(4), dp(5), dp(4), dp(5)) }
        wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun price(label0: String, v: String, color: Int): View {
        val two = v.contains(" / ")
        val label = if (two) "$label0 · ⚠️ 2 xil narx" else label0
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(TextView(context).apply {
                text = label; textSize = 11f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = v; textSize = if (two) 16f else 19f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            })
        }
    }

    private fun showPhoto(p: Product) {
        val iv = ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(Color.WHITE) }
        Img.load(this, p.photo, iv, big = true)
        val d = AlertDialog.Builder(this).setTitle(p.name).setView(iv)
            .setPositiveButton("Yopish", null).create()
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
        return if (t == 0L) "ilova ichidagi"
        else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(t))
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
                res.onSuccess { n ->
                    all = Db.load(this); refresh()
                    if (!silent) toast("✅ Baza yangilandi")
                }.onFailure {
                    if (!silent) toast("Internet yo'q yoki baza topilmadi. Eski baza bilan ishlayapsiz.")
                }
            }
        }.start()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun askPin(pin: String, title: String, cancelable: Boolean, ok: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 24f; gravity = Gravity.CENTER
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, -1, -2) }
        val b = AlertDialog.Builder(this).setTitle(title).setView(wrap)
            .setCancelable(cancelable)
            .setPositiveButton("Kirish", null)
        if (cancelable) b.setNegativeButton("Bekor", null)
        else b.setNegativeButton("Chiqish") { _, _ -> finish() }
        val d = b.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() == pin) { d.dismiss(); ok() }
                else { input.text.clear(); input.error = "Noto'g'ri PIN" }
            }
        }
        d.show()
    }
}
