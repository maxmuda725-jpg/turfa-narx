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
    private val ODD_DASH = Regex("[–—−‐‑_~]")
    private val DASH_SPACE_L = Regex("(\\d) +-(\\d)")
    private val DASH_SPACE_R = Regex("(\\d)- +(\\d)")
    private val CODEISH = Regex("[0-9OoОоIlІ|ЗзSsBbZ]+(?:-[0-9OoОоIlІ|ЗзSsBbZ]+)+")
    private val LOOKALIKE = mapOf('O' to '0', 'o' to '0', 'О' to '0', 'о' to '0', 'I' to '1', 'l' to '1', 'І' to '1', '|' to '1',
        'З' to '3', 'з' to '3', 'S' to '5', 's' to '5', 'B' to '8', 'b' to '6', 'Z' to '2')

    /** Kamera xatolarini tuzatish: "43 -25" -> "43-25", "4З-2O" -> "43-20". */
    fun normalize(row: String): String {
        var l = ODD_DASH.replace(row, "-")
        l = DASH_SPACE_L.replace(l, "$1-$2")
        l = DASH_SPACE_R.replace(l, "$1-$2")
        return CODEISH.replace(l) { m ->
            val v = m.value
            if (v.count { it.isDigit() } * 2 >= v.count { it != '-' }) v.map { LOOKALIKE[it] ?: it }.joinToString("") else v
        }
    }
    private val QTY_X = Regex("(?:^|\\s)(?:x|х|×|\\*)\\s*(\\d{1,5})(?!\\d)", RegexOption.IGNORE_CASE)
    private val QTY_UNIT = Regex("(?<![\\d-])(\\d{1,5})\\s*(?:ta|tа|dona|шт|sht|pcs|та|дона)\\b", RegexOption.IGNORE_CASE)
    private val QTY_ANY = Regex("(?<![\\d.,-])(\\d{1,4})(?![\\d.,-])")
    private val LOOKS_LIKE_CODE = Regex("\\d+-\\d+")
    // Narxga o'xshash son: "34 000", "34.000", "34,000", "34000"
    private val PRICE = Regex("(?<![\\d-])(\\d{1,3}(?:[ .,\u00A0]\\d{3})+|\\d{4,9})(?![\\d-])")

    /** Qatordagi narxga o'xshash sonlar (matni va qiymati). */
    fun prices(line: String, code: String): List<Pair<String, Long>> {
        val rest = line.lowercase().replace(code.lowercase(), " ")
        return PRICE.findAll(rest).mapNotNull { m ->
            val v = m.value.filter { it.isDigit() }.toLongOrNull()
            if (v == null || v < 100) null else m.value to v
        }.toList()
    }

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

    fun parse(rows: List<String>, index: Map<String, List<Product>>, all: List<Product> = emptyList()): MutableList<ScanLine> {
        val out = ArrayList<ScanLine>()
        for (row in rows) {
            val line = normalize(row)
            val toks = Search.tokens(line)
            var code = toks.firstOrNull { t ->
                t.any { it.isDigit() } && (t.contains('-') || t.length >= 8) && index.containsKey(t)
            }
            var suffixCands: List<Product>? = null
            if (code == null) {
                // Qisqa kod: "8-8" -> "25-11-8-8" (faqat chiziqcha chegarasida)
                val short = toks.firstOrNull { t -> t.contains('-') && t.any { it.isDigit() } }
                if (short != null) {
                    val hits = all.filter { p -> p.tokens.any { it.endsWith("-$short") } }
                    if (hits.isNotEmpty()) { code = short; suffixCands = hits }
                }
            }
            if (code == null) {
                // Kodga o'xshash, lekin bazada yo'q — ko'rsatamiz, do'konchi o'zi hal qiladi
                // Topilmadi — baribir ko'rsatamiz (raqami bor qator), do'konchi nima o'qilganini ko'radi
                val guess = LOOKS_LIKE_CODE.find(line)?.value
                if (guess != null || (line.count { it.isDigit() } >= 2 && line.trim().length >= 3))
                    out += ScanLine(row.trim(), guess ?: "kod?", emptyList(), null, if (guess != null) qty(line, guess) else 1, false)
                continue
            }
            var cands: List<Product> = (suffixCands ?: index[code].orEmpty()).filter { it.donaList.isNotEmpty() }
            if (cands.size > 1) {
                val words = toks.filter { w -> w.length >= 3 && w.none { it.isDigit() } }
                val narrowed = cands.filter { p -> words.any { p.nameLower.contains(it) } }
                if (narrowed.isNotEmpty()) cands = narrowed
            }
            // Bir nechta tovar qolsa — ro'yxatdagi narx bo'yicha: avval OPTOM, keyin DONA
            val ps = prices(line, code)
            if (cands.size > 1 && ps.isNotEmpty()) {
                val byOptom = cands.filter { p -> ps.any { it.second in p.optomList } }
                val byDona = cands.filter { p -> ps.any { it.second in p.donaList } }
                cands = when {
                    byOptom.size == 1 -> byOptom
                    byDona.size == 1 -> byDona
                    byOptom.isNotEmpty() -> byOptom
                    byDona.isNotEmpty() -> byDona
                    else -> cands
                }
            }
            val chosen = cands.singleOrNull()
            // Narxni son (miqdor) deb o'qib qolmaslik uchun qatordan olib tashlaymiz
            val known = cands.flatMap { it.optomList + it.donaList }.toHashSet()
            var qline = line.lowercase()
            for ((t, v) in ps) if (v in known || t.length >= 5) qline = qline.replace(t, " ")
            out += ScanLine(row.trim(), code, cands, chosen, qty(qline, code), chosen != null)
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
