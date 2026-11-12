package uz.turfa.narx

/**
 * Tovar rasmidagi yozilgan (qo'lda yoki bosma) do'kon kodini topadi: "43-25", "25-4-38-4", "Z068"…
 * Matnni ML Kit o'qiydi (telefonning o'zida), bu yerda faqat undan kod ajratiladi va bazadan AYNAN shu
 * kodli tovar qidiriladi (43-25 -> 43-250 emas). Kod rasmdan ko'ra aniqroq — topilsa, tepada ko'rsatiladi.
 */
object CodeReader {
    // 43-25, 43 - 25, 43.25, 43–25, 25-4-38-4 (OCR chiziqchani ba'zan nuqta/uzun chiziq deb o'qiydi)
    private val DASHED = Regex("""(?<![\d])\d{1,3}(?:\s?[-–—._/]\s?\d{1,3}){1,4}(?![\d])""")
    // Z068, C016-4, W06-14 kabi harfli kodlar
    private val LETTERED = Regex("""(?<![A-Za-z\d])[A-Za-z]{1,2}\d{2,4}(?:-\d{1,3}){0,3}(?![A-Za-z\d])""")
    // yolg'iz 3-4 xonali kod (masalan 4325) — faqat bazada aniq bo'lsa
    private val PLAIN = Regex("""(?<![\d])\d{3,4}(?![\d])""")

    /** OCR adashadigan harflarni raqamga: O/o -> 0, I/l/| -> 1 (faqat raqam yonida). */
    private fun fixDigits(s: String): String =
        s.replace(Regex("""(?<=\d)[Oo]|[Oo](?=\d)"""), "0")
            .replace(Regex("""(?<=\d)[Il|]|[Il|](?=\d)"""), "1")

    fun candidates(text: String): List<String> {
        val t = fixDigits(text)
        val out = LinkedHashSet<String>()
        DASHED.findAll(t).forEach { m -> out += m.value.replace(Regex("""\s?[-–—._/]\s?"""), "-") }
        LETTERED.findAll(t).forEach { out += it.value.lowercase() }
        PLAIN.findAll(t).forEach { out += it.value }
        // uzunroq (aniqroq) kodlar birinchi
        return out.sortedByDescending { it.length }
    }

    /** -> [(o'qilgan kod, shu kodli tovarlar)]; juda ko'p tovarga to'g'ri keladigan (noaniq) raqamlar tashlanadi. */
    fun find(all: List<Product>, text: String): List<Pair<String, List<Product>>> {
        if (text.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, List<Product>>>()
        for (c in candidates(text)) {
            val w = c.lowercase()
            val hits = all.filter { Search.codeHit(it, w) == 2 && it.key !in seen }
            val limit = if (w.contains('-') || w.any { it.isLetter() }) 6 else 2   // yolg'iz raqam — faqat juda aniq bo'lsa
            if (hits.isEmpty() || hits.size > limit) continue
            hits.forEach { seen += it.key }
            out += c to hits.sortedBy { Search.stockRank(it) }
            if (out.size >= 3) break
        }
        return out
    }
}
