package com.kisreplay.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KiwoomResearchClient(private val credentialStore: CredentialStore) {
    private var token: String = ""
    private var lastRequestMs = 0L

    fun testToken(): Boolean {
        token = ""
        return ensureToken()
    }

    fun fetchMinute(symbol: String, targetDay: String, maxPages: Int = 120): List<Candle> {
        val raw = fetchChartPages(
            apiId = "ka10080",
            listKey = "stk_min_pole_chart_qry",
            body = JSONObject().put("stk_cd", symbol).put("tic_scope", "1").put("upd_stkpc_tp", "1"),
            targetDay = targetDay,
            maxPages = maxPages,
        )
        return normalize(raw, targetDay)
    }

    fun fetchDaily(symbol: String, baseDay: String, maxPages: Int = 12): List<Candle> {
        val raw = fetchChartPages(
            apiId = "ka10081",
            listKey = "stk_dt_pole_chart_qry",
            body = JSONObject().put("stk_cd", symbol).put("base_dt", baseDay).put("upd_stkpc_tp", "1"),
            targetDay = "",
            maxPages = maxPages,
        )
        return normalize(raw, "")
    }

    private fun ensureToken(): Boolean {
        if (token.isNotBlank()) return true
        val creds = credentialStore.load() ?: return false
        val conn = open("https://api.kiwoom.com/oauth2/token")
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
        conn.doOutput = true
        val body = JSONObject().put("grant_type", "client_credentials").put("appkey", creds.appKey).put("secretkey", creds.secretKey)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val json = JSONObject(readBody(conn))
        token = json.optString("token").ifBlank { json.optString("access_token") }
        conn.disconnect()
        return token.isNotBlank()
    }

    private fun fetchChartPages(apiId: String, listKey: String, body: JSONObject, targetDay: String, maxPages: Int): List<JSONObject> {
        check(ensureToken()) { "키움 API Key/Secret을 설정에서 먼저 저장하세요" }
        val out = mutableListOf<JSONObject>()
        var cont = "N"
        var nextKey = ""
        repeat(maxPages.coerceAtLeast(1)) {
            throttle()
            val conn = open("https://api.kiwoom.com/api/dostk/chart")
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            conn.setRequestProperty("authorization", "Bearer $token")
            conn.setRequestProperty("api-id", apiId)
            conn.setRequestProperty("cont-yn", cont)
            conn.setRequestProperty("next-key", nextKey)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val json = JSONObject(readBody(conn))
            val arr = json.optJSONArray(listKey) ?: JSONArray()
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            val responseCont = conn.getHeaderField("cont-yn").orEmpty().uppercase()
            val responseNext = conn.getHeaderField("next-key").orEmpty()
            conn.disconnect()
            lastRequestMs = System.currentTimeMillis()

            if (targetDay.isNotBlank()) {
                val days = out.mapNotNull { digits(it.optString("cntr_tm").ifBlank { it.optString("dt") }).takeIf { s -> s.length >= 8 }?.take(8) }.toSet()
                if (targetDay in days && days.any { it < targetDay }) return out
            }
            if (responseCont != "Y" || responseNext.isBlank()) return out
            cont = "Y"
            nextKey = responseNext
        }
        return out
    }

    private fun normalize(raw: List<JSONObject>, targetDay: String): List<Candle> {
        val byTime = linkedMapOf<String, Candle>()
        raw.forEach { x ->
            val stamp = digits(x.optString("cntr_tm").ifBlank { x.optString("dt") })
            if (stamp.length < 8) return@forEach
            if (targetDay.isNotBlank() && stamp.take(8) != targetDay) return@forEach
            val close = price(x, "cur_prc", "close_pric", "close_price")
            if (close <= 0) return@forEach
            val open = price(x, "open_pric", "open_price").takeIf { it > 0 } ?: close
            val high = maxOf(price(x, "high_pric", "high_price").takeIf { it > 0 } ?: close, open, close)
            val low = minOf(price(x, "low_pric", "low_price").takeIf { it > 0 } ?: close, open, close)
            val volume = number(x.optString("trde_qty").ifBlank { x.optString("volume") }).toLong()
            val fullStamp = when (stamp.length) { 8 -> stamp; 12 -> stamp + "00"; else -> stamp.take(14) }
            byTime[fullStamp] = Candle(fullStamp, open, high, low, close, kotlin.math.abs(volume))
        }
        return byTime.toSortedMap().values.toList()
    }

    private fun price(obj: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            val n = kotlin.math.abs(number(obj.optString(k)).toLong())
            if (n > 0) return n
        }
        return 0
    }

    private fun number(text: String): Double = text.replace(",", "").trim().toDoubleOrNull()
        ?: text.filter { it.isDigit() || it == '.' || it == '-' || it == '+' }.toDoubleOrNull() ?: 0.0

    private fun digits(text: String): String = text.filter { it.isDigit() }

    private fun throttle() {
        val phase = (System.currentTimeMillis() / 1000.0) % 30.0
        if (phase >= 27.8) Thread.sleep(((32.8 - phase) * 1000).toLong())
        else if (phase < 2.8) Thread.sleep(((2.8 - phase) * 1000).toLong())
        val wait = 400L - (System.currentTimeMillis() - lastRequestMs)
        if (wait > 0) Thread.sleep(wait)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        useCaches = false
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        check(conn.responseCode in 200..299) { "Kiwoom HTTP ${conn.responseCode}: $text" }
        return text
    }
}
