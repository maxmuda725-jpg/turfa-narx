package uz.turfa.narx

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.InputStream

// ======================= SOZLAMALAR =======================
// Do'konchilar kiradigan PIN
const val SELLER_PIN = "1234"
// Bazani yangilash uchun (faqat egasi) PIN
const val ADMIN_PIN = "7777"
// ==========================================================

data class Product(val name: String, val code: String, val dona: Long, val optom: Long) {
    val nameLower = name.lowercase()
    val tokens: Set<String> = Search.tokens(name) + Search.tokens(code)
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

    /** Yangi fayl to'g'ri o'qilsa saqlaydi va mahsulotlar sonini qaytaradi. */
    fun import(ctx: Context, uri: Uri): Int {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val list = parse(bytes.inputStream())
        if (list.isEmpty()) return 0
        File(ctx.filesDir, FILE).writeBytes(bytes)
        return list.size
    }

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
                dona = money(c.getOrNull(iDona)),
                optom = if (iOptom >= 0) money(c.getOrNull(iOptom)) else 0L,
            )
        }
        return out
    }

    private fun money(s: String?): Long {
        if (s == null) return 0
        val clean = s.replace(" ", "").replace(" ", "").replace(",", ".")
            .filter { it.isDigit() || it == '.' }
        return clean.toDoubleOrNull()?.toLong() ?: 0
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

fun fmt(n: Long): String {
    if (n <= 0) return "—"
    val s = n.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "$s so'm"
}

class MainActivity : Activity() {
    private val GREEN = Color.parseColor("#0F7B5F")
    private val BLUE = Color.parseColor("#1F4E9E")
    private val BG = Color.parseColor("#F4F5F7")
    private val REQ_IMPORT = 42

    private var all: List<Product> = emptyList()
    private var shown: List<Product> = emptyList()
    private lateinit var adapter: BaseAdapter
    private lateinit var info: TextView
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        all = Db.load(this)
        buildUi()
        askPin(SELLER_PIN, "Do'konchi PIN-kodi", cancelable = false) { }
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
        top.addView(Button(this).apply {
            text = "⋮"; textSize = 20f
            setTextColor(Color.WHITE); background = null
            setOnClickListener { menu() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
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
        card.addView(TextView(this).apply {
            text = p.name; textSize = 17f
            setTextColor(Color.parseColor("#1A1A1A"))
            typeface = Typeface.DEFAULT_BOLD
        })
        if (p.code.isNotBlank()) card.addView(TextView(this).apply {
            text = p.code; textSize = 12f; setTextColor(Color.GRAY)
        })
        val prices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prices.addView(price("DONA", p.dona, GREEN), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        prices.addView(price("OPTOM", p.optom, BLUE), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        card.addView(prices, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val wrap = LinearLayout(this).apply { setPadding(dp(4), dp(5), dp(4), dp(5)) }
        wrap.addView(card, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun price(label: String, v: Long, color: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(TextView(context).apply {
                text = label; textSize = 11f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = fmt(v); textSize = 19f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
            })
        }

    private fun refresh() {
        val q = search.text.toString()
        shown = Search.run(all, q)
        info.text = when {
            all.isEmpty() -> "Baza bo'sh. ⋮ → Bazani yangilash"
            q.isBlank() -> "Bazada ${all.size} ta mahsulot. Kod yoki nom yozing."
            shown.isEmpty() -> "Topilmadi: \"${q.trim()}\""
            else -> "Topildi: ${shown.size} ta"
        }
        adapter.notifyDataSetChanged()
    }

    private fun menu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Bazani yangilash (CSV)", "Ilova haqida")) { _, w ->
                if (w == 0) askPin(ADMIN_PIN, "Egasi PIN-kodi", cancelable = true) { pickFile() }
                else AlertDialog.Builder(this).setTitle("TUR-FA narxlar 1.0")
                    .setMessage("Bazada ${all.size} ta mahsulot.\nInternetsiz ishlaydi.")
                    .setPositiveButton("OK", null).show()
            }.show()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || uri == null) return
        val n = try { Db.import(this, uri) } catch (e: Exception) { 0 }
        if (n > 0) {
            all = Db.load(this); refresh()
            Toast.makeText(this, "Baza yangilandi: $n ta mahsulot", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Faylni o'qib bo'lmadi. CSV formatini tekshiring.", Toast.LENGTH_LONG).show()
        }
    }

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
