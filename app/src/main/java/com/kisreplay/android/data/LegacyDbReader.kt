package com.kisreplay.android.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

class LegacyDbReader(private val pack: ImportedPack?) {
    private fun open(role: String): SQLiteDatabase? {
        val file = pack?.file(role) ?: return null
        if (!file.isFile) return null
        return runCatching { SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()
    }

    fun rankDays(): List<String> {
        val db = open("main_replay") ?: return emptyList()
        return db.useDb {
            queryStrings("SELECT DISTINCT substr(source_tm,1,8) d FROM rank_snapshots WHERE length(source_tm)>=8 ORDER BY d DESC")
        }
    }

    fun rankRows(day: String): List<RankRow> {
        val db = open("main_replay") ?: return emptyList()
        return db.useDb {
            val sql = """SELECT s.source_tm,s.event_ms,i.rank_no,i.symbol,i.name,i.price,
                coalesce(i.change_pct,0),coalesce(i.rank_change,''),coalesce(i.is_new,0)
                FROM rank_snapshots s JOIN rank_items i ON i.snapshot_id=s.id
                WHERE substr(s.source_tm,1,8)=? ORDER BY s.event_ms,i.rank_no""".trimIndent()
            val map = linkedMapOf<String, MutableRank>()
            rawQuery(sql, arrayOf(day)).use { c ->
                while (c.moveToNext()) {
                    val tm = c.getString(0).orEmpty()
                    val eventMs = c.getLong(1)
                    val rank = c.getInt(2)
                    val symbol = c.getString(3).orEmpty()
                    if (symbol.isBlank()) continue
                    val name = c.getString(4).orEmpty().ifBlank { symbol }
                    val price = c.getLong(5)
                    val changePct = c.getDouble(6)
                    val rawChange = c.getString(7).orEmpty()
                    val isNew = c.getInt(8) != 0 || rawChange.uppercase() in setOf("N", "NEW", "신규")
                    val delta = rankDelta(rawChange)
                    if (!isNew && delta < 10) continue

                    val signal = if (isNew) "N" else if (delta >= 20) "+20" else "+10"
                    val x = map.getOrPut(symbol) {
                        MutableRank(
                            symbol = symbol,
                            name = name,
                            firstEventMs = eventMs,
                            bestRank = rank,
                            latestPrice = price,
                            changePct = changePct,
                            maxRankChange = if (isNew) 0 else delta,
                            firstTime = tm,
                            lastTime = tm,
                        )
                    }
                    x.bestRank = minOf(x.bestRank.takeIf { it > 0 } ?: rank, rank.takeIf { it > 0 } ?: x.bestRank)
                    x.latestPrice = price
                    x.changePct = changePct
                    x.lastTime = tm
                    x.maxRankChange = maxOf(x.maxRankChange, if (isNew) 0 else delta)
                    x.occurrenceCount++
                    if (!x.signals.contains(signal)) x.signals += signal
                }
            }
            map.values
                .sortedWith(compareBy<MutableRank> { it.firstEventMs }.thenBy { it.bestRank }.thenBy { it.symbol })
                .map { it.toRow() }
        }
    }

    fun moverDays(): List<String> {
        val db = open("mover15") ?: return emptyList()
        return db.useDb {
            val table = if (hasTable(this, "daily_metrics")) "daily_metrics" else "movers"
            queryStrings("SELECT DISTINCT trade_day FROM $table ORDER BY trade_day DESC")
        }
    }

    fun moverRows(day: String, highRule: Boolean, threshold: Double): List<MoverRow> {
        val db = open("mover15") ?: return emptyList()
        return db.useDb {
            val out = mutableListOf<MoverRow>()
            if (hasTable(this, "daily_metrics")) {
                val metric = if (highRule) "high_change_pct" else "close_change_pct"
                rawQuery(
                    "SELECT symbol,name,close_change_pct,high_change_pct,close_price,source FROM daily_metrics WHERE trade_day=? AND $metric>=? ORDER BY $metric DESC",
                    arrayOf(day, threshold.toString())
                ).use { c ->
                    while (c.moveToNext()) out += MoverRow(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getLong(4), c.getString(5))
                }
            } else if (hasTable(this, "movers")) {
                val metric = if (highRule) "high_change_pct" else "close_change_pct"
                rawQuery(
                    "SELECT symbol,name,close_change_pct,high_change_pct,last_price,metric_source FROM movers WHERE trade_day=? AND $metric>=? ORDER BY $metric DESC",
                    arrayOf(day, threshold.toString())
                ).use { c ->
                    while (c.moveToNext()) out += MoverRow(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getLong(4), c.getString(5))
                }
            }
            out
        }
    }

    fun minuteBars(day: String, symbol: String): List<Candle> {
        val fromResearch = open("minute_cache")?.useDb {
            if (!hasTable(this, "minute_bars")) emptyList() else queryCandles(
                "SELECT bar_time,open_price,high_price,low_price,close_price,volume FROM minute_bars WHERE trade_day=? AND symbol=? ORDER BY bar_time",
                arrayOf(day, symbol.uppercase())
            )
        }.orEmpty()
        if (fromResearch.isNotEmpty()) return fromResearch
        return open("mover15")?.useDb {
            if (!hasTable(this, "minute_bars")) emptyList() else queryCandles(
                "SELECT bar_time,open_price,high_price,low_price,close_price,volume FROM minute_bars WHERE trade_day=? AND symbol=? ORDER BY bar_time",
                arrayOf(day, symbol.uppercase())
            )
        }.orEmpty()
    }

    fun longBars(symbol: String, interval: String = "D", limit: Int = 600): List<Candle> {
        val db = open("main_replay") ?: return emptyList()
        return db.useDb {
            if (!hasTable(this, "chart_bars")) emptyList() else queryCandles(
                "SELECT bar_time,open_price,high_price,low_price,close_price,volume FROM chart_bars WHERE symbol=? AND interval_name=? ORDER BY bar_time DESC LIMIT ?",
                arrayOf(symbol.uppercase(), interval, limit.toString())
            ).asReversed()
        }
    }

    fun learningZones(): List<LearningZone> {
        val db = open("concept_lab") ?: return emptyList()
        return db.useDb {
            if (!hasTable(this, "samples") || !hasTable(this, "zones")) return@useDb emptyList()
            val out = mutableListOf<LearningZone>()
            rawQuery(
                """SELECT s.id,s.trade_day,s.concept_id,s.symbol,coalesce(s.name,''),
                    coalesce(z.start_time,''),coalesce(z.end_time,''),z.zone_low,z.zone_high,
                    coalesce(z.meta_json,''),coalesce(z.note,'')
                    FROM samples s JOIN zones z ON z.sample_id=s.id
                    ORDER BY s.trade_day DESC,s.id DESC,z.id""".trimIndent(), null
            ).use { c ->
                while (c.moveToNext()) {
                    val meta = runCatching { JSONObject(c.getString(9) ?: "{}") }.getOrNull()
                    val strength = meta?.optInt("strength", 2) ?: 2
                    val explanation = meta?.optString("explanation", "").orEmpty()
                    val note = c.getString(10).orEmpty().ifBlank { explanation }
                    out += LearningZone(
                        c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getString(4).orEmpty(),
                        c.getString(5).orEmpty(), c.getString(6).orEmpty(), c.getDouble(7), c.getDouble(8), strength, note
                    )
                }
            }
            out
        }
    }

    private fun rankDelta(value: String): Int = runCatching {
        val text = value.replace(",", "").trim()
        if (text.uppercase() in setOf("N", "NEW", "신규", "")) 0 else text.replace("+", "").toDouble().toInt()
    }.getOrDefault(0)

    private data class MutableRank(
        val symbol: String,
        val name: String,
        val firstEventMs: Long,
        var bestRank: Int,
        var latestPrice: Long,
        var changePct: Double,
        var maxRankChange: Int,
        val firstTime: String,
        var lastTime: String,
        var occurrenceCount: Int = 0,
        val signals: MutableList<String> = mutableListOf(),
    ) {
        fun toRow() = RankRow(
            symbol = symbol,
            name = name,
            bestRank = bestRank,
            latestPrice = latestPrice,
            changePct = changePct,
            actualRankChange = if (maxRankChange > 0) "+$maxRankChange" else "N",
            firstTime = firstTime,
            lastTime = lastTime,
            signalsText = signals.joinToString("/"),
            occurrenceCount = occurrenceCount,
        )
    }

    private fun <T> SQLiteDatabase.useDb(block: SQLiteDatabase.() -> T): T = try { block() } finally { close() }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)
    ).use { it.moveToFirst() }

    private fun SQLiteDatabase.queryStrings(sql: String): List<String> {
        val out = mutableListOf<String>()
        rawQuery(sql, null).use { c -> while (c.moveToNext()) out += c.getString(0).orEmpty() }
        return out
    }

    private fun SQLiteDatabase.queryCandles(sql: String, args: Array<String>): List<Candle> {
        val out = mutableListOf<Candle>()
        rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += Candle(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))
        }
        return out
    }
}
