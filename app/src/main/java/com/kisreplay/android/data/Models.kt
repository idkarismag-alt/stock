package com.kisreplay.android.data

data class Candle(
    val time: String,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long = 0L,
)

data class RankRow(
    val symbol: String,
    val name: String,
    val bestRank: Int,
    val latestPrice: Long,
    val changePct: Double,
    val actualRankChange: String,
    val firstTime: String,
    val lastTime: String,
    val signalsText: String = "",
    val occurrenceCount: Int = 1,
)

data class MoverRow(
    val symbol: String,
    val name: String,
    val closeChangePct: Double,
    val highChangePct: Double,
    val lastPrice: Long,
    val source: String,
)

data class LearningZone(
    val sampleId: Long,
    val tradeDay: String,
    val conceptId: String,
    val symbol: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val low: Double,
    val high: Double,
    val strength: Int,
    val note: String,
)

data class SimTrade(
    val id: Long,
    val tradeDay: String,
    val symbol: String,
    val name: String,
    val barTime: String,
    val side: String,
    val price: Long,
    val quantity: Int,
)

data class SimSummary(
    val buyQuantity: Int = 0,
    val sellQuantity: Int = 0,
    val holdingQuantity: Int = 0,
    val avgBuyPrice: Double = 0.0,
    val realizedPnl: Long = 0,
    val unrealizedPnl: Long = 0,
    val totalPnl: Long = 0,
    val returnPct: Double = 0.0,
)

data class ApiCredentials(val appKey: String, val secretKey: String)

fun calculateSimSummary(trades: List<SimTrade>, currentPrice: Long): SimSummary {
    var holding = 0
    var avg = 0.0
    var realized = 0.0
    var buyQty = 0
    var sellQty = 0
    var totalBuyAmount = 0.0
    trades.forEach { t ->
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
    return SimSummary(
        buyQuantity = buyQty,
        sellQuantity = sellQty,
        holdingQuantity = holding,
        avgBuyPrice = avg,
        realizedPnl = realized.toLong(),
        unrealizedPnl = unrealized.toLong(),
        totalPnl = total.toLong(),
        returnPct = if (totalBuyAmount > 0) total / totalBuyAmount * 100.0 else 0.0,
    )
}
