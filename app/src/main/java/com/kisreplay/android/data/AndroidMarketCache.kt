package com.kisreplay.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AndroidMarketCache(context: Context) : SQLiteOpenHelper(
    context, "android_research_cache.sqlite3", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE candles(
                symbol TEXT NOT NULL,
                interval_name TEXT NOT NULL,
                bar_time TEXT NOT NULL,
                open_price INTEGER NOT NULL,
                high_price INTEGER NOT NULL,
                low_price INTEGER NOT NULL,
                close_price INTEGER NOT NULL,
                volume INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(symbol,interval_name,bar_time)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX ix_android_candles ON candles(symbol,interval_name,bar_time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(symbol: String, interval: String, rows: List<Candle>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { c ->
                val v = ContentValues().apply {
                    put("symbol", symbol.uppercase())
                    put("interval_name", interval)
                    put("bar_time", c.time)
                    put("open_price", c.open)
                    put("high_price", c.high)
                    put("low_price", c.low)
                    put("close_price", c.close)
                    put("volume", c.volume)
                }
                db.insertWithOnConflict("candles", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun bars(symbol: String, interval: String, day: String? = null, limit: Int = 1500): List<Candle> {
        val where = if (day.isNullOrBlank()) "symbol=? AND interval_name=?" else "symbol=? AND interval_name=? AND substr(bar_time,1,8)=?"
        val args = if (day.isNullOrBlank()) arrayOf(symbol.uppercase(), interval) else arrayOf(symbol.uppercase(), interval, day)
        val out = mutableListOf<Candle>()
        readableDatabase.query(
            "candles", arrayOf("bar_time","open_price","high_price","low_price","close_price","volume"),
            where, args, null, null, "bar_time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += Candle(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))
            }
        }
        return out.asReversed()
    }
}
