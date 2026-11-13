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

    /**
     * -> [(o'qilgan kod, shu kodli tovarlar)].
     * Bazada kod ba'zan qisqa yozilgan (tovarda 26-3-44-12, bazada 44-12) — to'liq kod topilmasa, oxirgi
     * qismlari bilan qayta qidiriladi. Bitta kod bir necha tovarga to'g'ri kelsa (44-12 -> 3-4 ta), qaysi
     * biri ekanini keyin rasmga o'xshashlik hal qiladi (MainActivity.photoSearch).
     */
    fun find(all: List<Product>, text: String): List<Pair<String, List<Product>>> {
        if (text.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, List<Product>>>()
        for (c in candidates(text)) {
            val w = c.lowercase()
            val dashed = w.contains('-') || w.any { it.isLetter() }
            val limit = if (dashed) 10 else 2          // yolg'iz raqam — faqat juda aniq bo'lsa
            // to'liq kod, keyin oxirgi qismlari: 26-3-44-12 -> 3-44-12 -> 44-12
            val tries = if (w.contains('-')) w.split('-').let { parts -> (0..parts.size - 2).map { parts.drop(it).joinToString("-") } } else listOf(w)
            for (t in tries) {
                val hits = all.filter { p ->
                    p.key !in seen && Search.codeHit(p, t) > 0 &&
                        // Yolg'iz son (500, 240) faqat kodi shu bo'lgan tovarga: nomida chiziqchali kod yo'q va son oxirgi raqamli so'z
                        (dashed || (p.tokens.none { k -> k.contains('-') && k.any { it.isDigit() } } &&
                            p.tokens.lastOrNull { k -> k.any { it.isDigit() } } == t))
                }
                if (hits.isEmpty() || hits.size > limit) continue
                hits.forEach { seen += it.key }
                out += t to hits.sortedWith(compareBy<Product>({ -Search.codeHit(it, t) }, { Search.stockRank(it) }))
                break
            }
            if (out.size >= 3) break
        }
        return out
    }
}
