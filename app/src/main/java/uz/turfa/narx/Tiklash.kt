package uz.turfa.narx

import java.net.HttpURLConnection
import java.net.URL

/** Server ko'chganda yo'qolgan polka / qo'lda kategoriyalarni telefondagi nusxadan serverga qaytaradi (faqat yo'q kalitlar). */
object Tiklash {
    fun send(kind: String, items: Map<String, String>, key: String): Int = try {
        val c = URL("$KASSA_API/api/kassa/tiklash").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-app-key", key)
        val body = org.json.JSONObject().put("kind", kind).put("items", org.json.JSONObject(items as Map<*, *>))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val rc = c.responseCode; c.disconnect(); rc
    } catch (e: Exception) { -1 }
}
