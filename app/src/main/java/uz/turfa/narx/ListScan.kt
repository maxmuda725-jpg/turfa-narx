package uz.turfa.narx

import android.graphics.Rect

/** Rasmdan o'qilgan bitta qator (ML Kit). */
class OcrLine(val text: String, val box: Rect?)

/** Ro'yxatning bitta qatori: topilgan tovar(lar), soni, kassaga qo'shilsinmi. */
class ScanLine(
    val raw: String,
    val code: String,
    val candidates: List<Product>,
    var chosen: Product?,
    var count: Long,
    var include: Boolean,
)

object ListParser {
    private val ODD_DASH = Regex("[–—−‐‑]")
    private val QTY_X = Regex("(?:^|\\s)(?:x|х|×|\\*)\\s*(\\d{1,5})(?!\\d)", RegexOption.IGNORE_CASE)
    private val QTY_UNIT = Regex("(?<![\\d-])(\\d{1,5})\\s*(?:ta|tа|dona|шт|sht|pcs|та|дона)\\b", RegexOption.IGNORE_CASE)
    private val QTY_ANY = Regex("(?<![\\d.,-])(\\d{1,4})(?![\\d.,-])")
    private val LOOKS_LIKE_CODE = Regex("\\d+-\\d+")

    /** Raqamli tokenlar bo'yicha indeks: "43-25" -> [tovarlar]. */
    fun index(all: List<Product>): Map<String, List<Product>> {
        val m = HashMap<String, MutableList<Product>>()
        for (p in all) for (t in p.tokens) if (t.any { it.isDigit() }) m.getOrPut(t) { ArrayList(2) }.add(p)
        return m
    }

    /**
     * Jadval ko'rinishidagi ro'yxatda kod va son alohida ustunda bo'lishi mumkin.
     * Shu sababli bir balandlikdagi qatorlarni chapdan o'ngga birlashtiramiz.
     */
    fun rows(lines: List<OcrLine>): List<String> {
        val withBox = lines.filter { it.box != null }.sortedBy { it.box!!.centerY() }
        val out = ArrayList<String>()
        val group = ArrayList<OcrLine>()
        var cy = 0f; var h = 0f
        fun flush() {
            if (group.isNotEmpty()) out += group.sortedBy { it.box!!.left }.joinToString("  ") { it.text }
            group.clear()
        }
        for (l in withBox) {
            val b = l.box!!
            if (group.isNotEmpty() && kotlin.math.abs(b.centerY() - cy) > 0.6f * h) flush()
            group += l
            cy = group.map { it.box!!.centerY().toFloat() }.average().toFloat()
            h = group.map { it.box!!.height().toFloat() }.average().toFloat().coerceAtLeast(1f)
        }
        flush()
        lines.filter { it.box == null }.forEach { out += it.text }
        return out
    }

    fun parse(rows: List<String>, index: Map<String, List<Product>>): MutableList<ScanLine> {
        val out = ArrayList<ScanLine>()
        for (row in rows) {
            val line = ODD_DASH.replace(row, "-")
            val toks = Search.tokens(line)
            val code = toks.firstOrNull { t ->
                t.any { it.isDigit() } && (t.contains('-') || t.length >= 8) && index.containsKey(t)
            }
            if (code == null) {
                // Kodga o'xshash, lekin bazada yo'q — ko'rsatamiz, do'konchi o'zi hal qiladi
                val guess = LOOKS_LIKE_CODE.find(line)?.value
                if (guess != null) out += ScanLine(row.trim(), guess, emptyList(), null, qty(line, guess), false)
                continue
            }
            var cands: List<Product> = index[code].orEmpty().filter { it.donaList.isNotEmpty() }
            if (cands.size > 1) {
                val words = toks.filter { w -> w.length >= 3 && w.none { it.isDigit() } }
                val narrowed = cands.filter { p -> words.any { p.nameLower.contains(it) } }
                if (narrowed.isNotEmpty()) cands = narrowed
            }
            val chosen = cands.singleOrNull()
            out += ScanLine(row.trim(), code, cands, chosen, qty(line, code), chosen != null)
        }
        return out
    }

    /** Sonini topish: "x10", "10 ta", aks holda koddan tashqaridagi oxirgi son; topilmasa 1. */
    fun qty(line: String, code: String): Long {
        val rest = line.lowercase().replace(code.lowercase(), " ")
        val n = QTY_X.find(rest)?.groupValues?.get(1)
            ?: QTY_UNIT.find(rest)?.groupValues?.get(1)
            ?: QTY_ANY.findAll(rest).lastOrNull()?.groupValues?.get(1)
        return n?.toLongOrNull()?.takeIf { it in 1..99999 } ?: 1
    }
}
