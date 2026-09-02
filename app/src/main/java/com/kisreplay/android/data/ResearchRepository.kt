package com.kisreplay.android.data

import android.content.Context
import android.net.Uri

class ResearchRepository(private val context: Context) {
    private val importer = TransferPackImporter(context)
    private val credentialStore = CredentialStore(context)
    private val api = KiwoomResearchClient(credentialStore)
    private val androidCache = AndroidMarketCache(context)

    var activePack: ImportedPack? = importer.loadActive()
        private set

    fun importPack(uri: Uri): ImportedPack {
        val pack = importer.import(uri)
        activePack = pack
        return pack
    }

    fun saveCredentials(appKey: String, secret: String) = credentialStore.save(ApiCredentials(appKey.trim(), secret.trim()))
    fun hasCredentials(): Boolean = credentialStore.load()?.let { it.appKey.isNotBlank() && it.secretKey.isNotBlank() } == true
    fun testCredentials(): Boolean = api.testToken()

    fun rankDays(): List<String> = LegacyDbReader(activePack).rankDays()
    fun rankRows(day: String): List<RankRow> = LegacyDbReader(activePack).rankRows(day)
    fun moverDays(): List<String> = LegacyDbReader(activePack).moverDays()
    fun moverRows(day: String, highRule: Boolean, threshold: Double): List<MoverRow> = LegacyDbReader(activePack).moverRows(day, highRule, threshold)
    fun learningZones(): List<LearningZone> = LegacyDbReader(activePack).learningZones()

    fun dailyBars(symbol: String, day: String): List<Candle> {
        val local = androidCache.bars(symbol, "D", null, 800)
        if (local.isNotEmpty()) return local
        val imported = LegacyDbReader(activePack).longBars(symbol, "D", 800)
        if (imported.isNotEmpty()) return imported
        if (!hasCredentials()) return emptyList()
        val fetched = api.fetchDaily(symbol, day)
        if (fetched.isNotEmpty()) androidCache.save(symbol, "D", fetched)
        return fetched
    }

    fun minuteBars(symbol: String, day: String): Pair<List<Candle>, String> {
        val local = androidCache.bars(symbol, "1m", day, 2000)
        if (local.isNotEmpty()) return local to "Android 캐시"
        val imported = LegacyDbReader(activePack).minuteBars(day, symbol)
        if (imported.isNotEmpty()) return imported to "Windows 전송팩 캐시"
        if (!hasCredentials()) return emptyList<Candle>() to "분봉 없음 · 설정에서 API Key 필요"
        val fetched = api.fetchMinute(symbol, day)
        if (fetched.isNotEmpty()) androidCache.save(symbol, "1m", fetched)
        return fetched to if (fetched.isNotEmpty()) "키움 API 자동보완 → Android 캐시 저장" else "분봉 조회 결과 없음"
    }
}
