package uz.turfa.narx

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kategoriyalar tovar nomi bo'yicha aniqlanadi (Billz'da kategoriya deyarli to'ldirilmagan).
 * Qoidalar baza/kategoriyalar.txt faylida: uni GitHub'da o'zgartirib, ilovada 🔄 Yangilash bosiladi.
 */
object Cats {
    const val URL_TXT = "https://raw.githubusercontent.com/maxmuda725-jpg/turfa-narx/main/baza/kategoriyalar.txt"
    private const val FILE = "kategoriyalar.txt"
    const val OTHER = "📦 Boshqalar"
    const val OTHER_SUB = "Boshqalar"

    class Sub(val name: String, val kws: List<String>)
    class Cat(val name: String, val kws: List<String>, val subs: List<Sub>)

    @Volatile var cats: List<Cat> = emptyList()
        private set
    private val cache = HashMap<String, Pair<String, String?>>()

    private val APOS = Regex("['`ʻʼ‘’]")
    private val WORD = Regex("[a-zа-яёé]+")

    fun words(s: String): List<String> = WORD.findAll(APOS.replace(s.lowercase(), "")).map { it.value }.toList()
    private fun hit(t: String, k: String) = t == k || (k.length >= 4 && t.startsWith(k))
    private fun any(ws: List<String>, kws: List<String>) = ws.any { w -> kws.any { hit(w, it) } }

    fun parse(text: String): List<Cat> {
        val out = ArrayList<Cat>()
        val subs = ArrayList<Sub>()
        var cur: Pair<String, List<String>>? = null
        fun flush() { cur?.let { out += Cat(it.first, it.second, subs.toList()) }; subs.clear() }
        for (line in text.removePrefix("﻿").lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || !t.contains('|')) continue
            val name = t.substringBefore('|').trim()
            val kws = t.substringAfter('|').split(',')
                .map { APOS.replace(it.trim().lowercase(), "") }.filter { it.isNotEmpty() }
            if (line.startsWith(" ") || line.startsWith("\t")) { if (cur != null) subs += Sub(name, kws) }
            else { flush(); cur = name to kws }
        }
        flush()
        return out
    }

    @Synchronized
    private fun set(list: List<Cat>) { if (list.isNotEmpty()) { cats = list; cache.clear() } }

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, FILE)
        val text = try {
            if (f.exists()) f.readText() else ctx.assets.open(FILE).use { it.bufferedReader().readText() }
        } catch (e: Exception) { "" }
        set(parse(text))
    }

    fun download(ctx: Context) {
        val c = URL(URL_TXT + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        try {
            if (c.responseCode != 200) return
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val list = parse(text)
            if (list.isEmpty()) return
            File(ctx.filesDir, FILE).writeText(text)
            set(list)
        } finally { c.disconnect() }
    }

    /** Tovar -> (kategoriya, bo'lim). Bo'limi yo'q kategoriyada bo'lim = null. */
    @Synchronized
    fun of(p: Product): Pair<String, String?> = cache.getOrPut(p.name) {
        val ws = words(p.name)
        val c = cats.firstOrNull { any(ws, it.kws) } ?: return@getOrPut OTHER to null
        if (c.subs.isEmpty()) c.name to null
        else c.name to (c.subs.firstOrNull { any(ws, it.kws) }?.name ?: OTHER_SUB)
    }

    /** Fayldagi tartibda: kategoriya nomi -> bo'limlar nomi (bo'sh bo'lishi mumkin). */
    fun order(): List<Pair<String, List<String>>> =
        cats.map { c -> c.name to (if (c.subs.isEmpty()) emptyList() else c.subs.map { it.name } + OTHER_SUB) } +
            (OTHER to emptyList())
}
