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

data class ApiCredentials(val appKey: String, val secretKey: String)
