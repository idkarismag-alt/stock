package com.kisreplay.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SimTradeStore(context: Context) : SQLiteOpenHelper(context, "sim_trades_v3.sqlite3", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sim_trades(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trade_day TEXT NOT NULL,
                symbol TEXT NOT NULL,
                name TEXT NOT NULL,
                bar_time TEXT NOT NULL,
                side TEXT NOT NULL,
                price INTEGER NOT NULL,
                quantity INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_sim_trade_symbol ON sim_trades(trade_day,symbol,id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun trades(day: String, symbol: String): List<SimTrade> {
        val out = mutableListOf<SimTrade>()
        readableDatabase.rawQuery(
            "SELECT id,trade_day,symbol,name,bar_time,side,price,quantity FROM sim_trades WHERE trade_day=? AND symbol=? ORDER BY id",
            arrayOf(day, symbol.uppercase())
        ).use { c ->
            while (c.moveToNext()) out += SimTrade(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getLong(6), c.getInt(7)
            )
        }
        return out
    }

    fun add(day: String, symbol: String, name: String, barTime: String, side: String, price: Long, quantity: Int) {
        require(price > 0 && quantity > 0)
        val normalizedSide = side.uppercase()
        require(normalizedSide == "BUY" || normalizedSide == "SELL")
        if (normalizedSide == "SELL") {
            val holding = summary(day, symbol, price).holdingQuantity
            require(holding >= quantity) { "보유수량 부족: 현재 ${holding}주" }
        }
        writableDatabase.execSQL(
            "INSERT INTO sim_trades(trade_day,symbol,name,bar_time,side,price,quantity) VALUES(?,?,?,?,?,?,?)",
            arrayOf(day, symbol.uppercase(), name, barTime, normalizedSide, price, quantity)
        )
    }

    fun clear(day: String, symbol: String) {
        writableDatabase.delete("sim_trades", "trade_day=? AND symbol=?", arrayOf(day, symbol.uppercase()))
    }

    fun summary(day: String, symbol: String, currentPrice: Long): SimSummary {
        val rows = trades(day, symbol)
        var holding = 0
        var avg = 0.0
        var realized = 0.0
        var buyQty = 0
        var sellQty = 0
        var totalBuyAmount = 0.0
        rows.forEach { t ->
            if (t.side == "BUY") {
                val newQty = holding + t.quantity
                avg = if (newQty > 0) (avg * holding + t.price * t.quantity) / newQty else 0.0
                holding = newQty
                buyQty += t.quantity
                totalBuyAmount += t.price * t.quantity
            } else {
                val q = minOf(holding, t.quantity)
                realized += (t.price - avg) * q
                holding -= q
                sellQty += q
                if (holding == 0) avg = 0.0
            }
        }
        val unrealized = if (holding > 0 && currentPrice > 0) (currentPrice - avg) * holding else 0.0
        val total = realized + unrealized
        val ret = if (totalBuyAmount > 0.0) total / totalBuyAmount * 100.0 else 0.0
        return SimSummary(
            buyQuantity = buyQty,
            sellQuantity = sellQty,
            holdingQuantity = holding,
            avgBuyPrice = avg,
            realizedPnl = realized.toLong(),
            unrealizedPnl = unrealized.toLong(),
            totalPnl = total.toLong(),
            returnPct = ret,
        )
    }
}
