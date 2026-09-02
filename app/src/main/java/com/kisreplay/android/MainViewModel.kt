package com.kisreplay.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kisreplay.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SourceMode { RANK0198, MOVER15 }

data class AppState(
    val pack: ImportedPack? = null,
    val busy: Boolean = false,
    val message: String = "",
    val source: SourceMode = SourceMode.RANK0198,
    val day: String = "",
    val rankRows: List<RankRow> = emptyList(),
    val moverRows: List<MoverRow> = emptyList(),
    val selectedSymbol: String = "",
    val selectedName: String = "",
    val dailyBars: List<Candle> = emptyList(),
    val minuteBars: List<Candle> = emptyList(),
    val minuteSource: String = "",
    val learningZones: List<LearningZone> = emptyList(),
    val highRule: Boolean = true,
    val threshold: Double = 15.0,
    val credentialsSaved: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ResearchRepository(app)
    private val _state = MutableStateFlow(AppState(pack = repo.activePack, credentialsSaved = repo.hasCredentials()))
    val state = _state.asStateFlow()

    init { refreshDefault() }

    fun importPack(uri: Uri) = io("전송팩 가져오는 중…") {
        val p = repo.importPack(uri)
        _state.value = _state.value.copy(pack = p, message = "전송팩 ${p.manifest.packId} 가져오기 완료")
        refreshDefault()
    }

    fun setSource(source: SourceMode) {
        _state.value = _state.value.copy(source = source, selectedSymbol = "", dailyBars = emptyList(), minuteBars = emptyList())
        refreshDefault()
    }

    fun setDay(day: String) { _state.value = _state.value.copy(day = day.filter { it.isDigit() }.take(8)) }
    fun setMoverRule(high: Boolean) { _state.value = _state.value.copy(highRule = high); loadList() }
    fun setThreshold(v: Double) { _state.value = _state.value.copy(threshold = v); loadList() }

    fun loadList() = io("목록 불러오는 중…") {
        val s = _state.value
        val day = s.day
        if (day.length != 8) return@io
        if (s.source == SourceMode.RANK0198) {
            val rows = repo.rankRows(day)
            _state.value = _state.value.copy(rankRows = rows, moverRows = emptyList(), message = "0198 ${rows.size}종목")
        } else {
            val rows = repo.moverRows(day, s.highRule, s.threshold)
            _state.value = _state.value.copy(moverRows = rows, rankRows = emptyList(), message = "15% 목록 ${rows.size}종목")
        }
    }

    fun selectSymbol(symbol: String, name: String) = io("차트 준비 중…") {
        val day = _state.value.day
        _state.value = _state.value.copy(selectedSymbol = symbol, selectedName = name, dailyBars = emptyList(), minuteBars = emptyList())
        val daily = repo.dailyBars(symbol, day)
        val (minutes, src) = repo.minuteBars(symbol, day)
        _state.value = _state.value.copy(dailyBars = daily, minuteBars = minutes, minuteSource = src, message = "$name $src")
    }

    fun refreshLearning() = io("학습자료 불러오는 중…") { _state.value = _state.value.copy(learningZones = repo.learningZones()) }

    fun saveCredentials(appKey: String, secret: String) {
        repo.saveCredentials(appKey, secret)
        _state.value = _state.value.copy(credentialsSaved = repo.hasCredentials(), message = "API Key/Secret을 Android Keystore로 암호화 저장했습니다")
    }

    fun testCredentials() = io("키움 토큰 테스트 중…") {
        val ok = repo.testCredentials()
        _state.value = _state.value.copy(message = if (ok) "키움 토큰 발급 성공" else "키움 토큰 발급 실패")
    }

    private fun refreshDefault() = io("") {
        if (_state.value.pack == null) return@io
        val days = if (_state.value.source == SourceMode.RANK0198) repo.rankDays() else repo.moverDays()
        val day = _state.value.day.takeIf { it.length == 8 } ?: days.firstOrNull().orEmpty()
        _state.value = _state.value.copy(day = day)
        if (day.isNotBlank()) loadList()
        _state.value = _state.value.copy(learningZones = repo.learningZones())
    }

    private fun io(message: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (message.isNotBlank()) _state.value = _state.value.copy(busy = true, message = message)
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) { _state.value = _state.value.copy(message = e.message ?: e.javaClass.simpleName) }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
