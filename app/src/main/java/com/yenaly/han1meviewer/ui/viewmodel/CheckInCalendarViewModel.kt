package com.yenaly.han1meviewer.ui.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yenaly.han1meviewer.logic.dao.CheckInRecordDatabase
import com.yenaly.han1meviewer.logic.dao.HistoryDatabase
import com.yenaly.han1meviewer.logic.entity.CheckInRecordEntity
import com.yenaly.han1meviewer.logic.entity.WatchHistoryEntity
import com.yenaly.yenaly_libs.utils.application
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class CheckInCalendarViewModel : ViewModel() {

    // 当前月份
    private val _currentMonth = mutableStateOf(YearMonth.now())
    val currentMonth: State<YearMonth> = _currentMonth
    //记录数
    private val _records = mutableStateMapOf<LocalDate, Int>()
    val records: SnapshotStateMap<LocalDate, Int> = _records
    //每月计数
    private val _checkedDays = mutableIntStateOf(0)
    val checkedDays: State<Int> get() = _checkedDays
    //每月累计
    private val _monthTotal = mutableIntStateOf(0)
    val monthlyTotal: State<Int> get() = _monthTotal

    // 年记录（用于报表）
    private val _yearRecords = mutableStateMapOf<LocalDate, Int>()
    val yearRecords: SnapshotStateMap<LocalDate, Int> = _yearRecords

    // 月记录详情（用于成就）
    private val _monthRecords = mutableStateListOf<CheckInRecordEntity>()

    private val _monthlyStats = mutableStateOf(MonthlyStats())
    val monthlyStats: State<MonthlyStats> = _monthlyStats

    private val _yearStats = mutableStateOf(MonthlyStats())
    val yearStats: State<MonthlyStats> = _yearStats

    private val dao = CheckInRecordDatabase.getDatabase(application).checkInDao()

    init {
        loadMonthRecords(_currentMonth.value)
        updateCheckedDays()
        updateMonthlyTotalCheck()
    }

    fun previousMonth() {
        _currentMonth.value = _currentMonth.value.minusMonths(1)
        loadMonthRecords(_currentMonth.value)
        updateCheckedDays()
        updateMonthlyTotalCheck()
    }

    fun nextMonth() {
        _currentMonth.value = _currentMonth.value.plusMonths(1)
        loadMonthRecords(_currentMonth.value)
        updateCheckedDays()
        updateMonthlyTotalCheck()
    }

    fun addRecord(date: LocalDate, time: String, type: String, sideDishes: String, feeling: String) {
        viewModelScope.launch {
            val record = CheckInRecordEntity(
                date = date.toString(),
                time = time,
                type = type,
                sideDishes = sideDishes,
                feeling = feeling
            )
            dao.insert(record)
            reloadDateAndStats(date)
        }
    }

    fun deleteRecord(record: CheckInRecordEntity) {
        viewModelScope.launch {
            dao.delete(record)
            val date = LocalDate.parse(record.date, DateTimeFormatter.ISO_LOCAL_DATE)
            reloadDateAndStats(date)
        }
    }

    fun getRecordsByDate(date: LocalDate, onResult: (List<CheckInRecordEntity>) -> Unit) {
        viewModelScope.launch {
            val result = dao.getRecordsByDate(date.toString())
            onResult(result)
        }
    }


    fun getRecentWatchHistory(limit: Int, onResult: (List<WatchHistoryEntity>) -> Unit) {
        viewModelScope.launch {
            val result = HistoryDatabase.instance.watchHistory.getRecentWatches(limit)
            onResult(result)
        }
    }

    fun getCountByDate(date: LocalDate, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = dao.getCountByDate(date.toString())
            onResult(count)
        }
    }

    fun clearCheckIn(date: LocalDate) {
        viewModelScope.launch {
            val records = dao.getRecordsByDate(date.toString())
            records.forEach { dao.delete(it) }
            reloadDateAndStats(date)
        }
    }

    private fun reloadDateAndStats(date: LocalDate) {
        viewModelScope.launch {
            val count = dao.getCountByDate(date.toString())
            _records[date] = count
            val dates = dao.getMonthlyCheckedDates(
                currentMonth.value.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            )
            _checkedDays.intValue = dates.size
            val totalCheckIns = dao.getMonthlyCheckInTotal(
                currentMonth.value.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            )
            _monthTotal.intValue = totalCheckIns
            // Refresh month records and stats
            val month = currentMonth.value
            val allRecords = dao.getRecordsBetween(month.atDay(1).toString(), month.atEndOfMonth().toString())
            _monthRecords.clear()
            _monthRecords.addAll(allRecords)
            _monthlyStats.value = computeStats(allRecords)
        }
    }

    fun updateCheckedDays() {
        viewModelScope.launch {
            val dates = dao.getMonthlyCheckedDates(
                currentMonth.value.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            )
            _checkedDays.intValue = dates.size
        }
    }

    fun updateMonthlyTotalCheck() {
        viewModelScope.launch {
            val totalCheckIns = dao.getMonthlyCheckInTotal(
                currentMonth.value.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            )
            _monthTotal.intValue = totalCheckIns
        }
    }

    fun loadMonthRecords(month: YearMonth) {
        viewModelScope.launch {
            val start = month.atDay(1)
            val end = month.atEndOfMonth()
            val allRecords = dao.getRecordsBetween(start.toString(), end.toString())
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            _records.clear()
            _monthRecords.clear()
            _monthRecords.addAll(allRecords)
            val countMap = mutableMapOf<LocalDate, Int>()
            allRecords.forEach {
                val localDate = LocalDate.parse(it.date, formatter)
                countMap[localDate] = (countMap[localDate] ?: 0) + 1
            }
            _records.putAll(countMap)
            _monthlyStats.value = computeStats(allRecords)
        }
    }

    fun loadYearRecords(year: Int) {
        viewModelScope.launch {
            val allRecords = dao.getYearlyRecords(year.toString())
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            _yearRecords.clear()
            val countMap = mutableMapOf<LocalDate, Int>()
            allRecords.forEach {
                val localDate = LocalDate.parse(it.date, formatter)
                countMap[localDate] = (countMap[localDate] ?: 0) + 1
            }
            _yearRecords.putAll(countMap)
            _yearStats.value = computeStats(allRecords)
        }
    }

    companion object {
        fun computeStats(records: List<CheckInRecordEntity>): MonthlyStats {
            if (records.isEmpty()) return MonthlyStats()
            val sep = "\u001E"
            val typeCounts = records.groupingBy { it.type }.eachCount()
            val sideDishes = records
                .flatMap { it.sideDishes.split(",").filter { s -> s.isNotBlank() } }
                .map { it.substringBefore(sep) }
            val uniqueDishes = sideDishes.distinct().size
            val topDish = sideDishes.groupingBy { it }.eachCount().maxByOrNull { it.value }
            val morning = records.count { val h = it.time.substringBefore(":").toIntOrNull() ?: 0; h in 5..10 }
            val night = records.count { val h = it.time.substringBefore(":").toIntOrNull() ?: 0; h in 22..23 || h in 0..2 }
            val afternoon = records.count { val h = it.time.substringBefore(":").toIntOrNull() ?: 0; h in 12..16 }
            val totalFeelingChars = records.sumOf { it.feeling.length }
            val avgFeelingChars = if (records.isNotEmpty()) totalFeelingChars / records.size else 0
            val maxDailyTypes = records.groupBy { it.date }.values.maxOfOrNull { it.map { r -> r.type }.distinct().size } ?: 0
            val daysChecked = records.map { it.date }.distinct().size
            val bestStreak = run {
                val dates = records.map { it.date }.distinct().sorted().map { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }
                var streak = 0; var best = 0; var prev: LocalDate? = null
                for (d in dates) {
                    if (prev != null && d == prev.plusDays(1)) streak++ else streak = 1
                    if (streak > best) best = streak
                    prev = d
                }
                best
            }

            val dominantPeriod = when {
                night > morning && night > afternoon -> "22~02"
                morning > night && morning > afternoon -> "05~10"
                afternoon > morning && afternoon > night -> "12~16"
                else -> ""
            }

            var scholarDate = ""
            if (totalFeelingChars >= 100) {
                var sum = 0
                for (r in records.sortedBy { it.date + it.time }) {
                    sum += r.feeling.length
                    if (sum >= 100) {
                        scholarDate = r.date.substring(5).replace("-", "/")
                        break
                    }
                }
            }

            return MonthlyStats(
                totalCount = records.size,
                daysChecked = daysChecked,
                bestStreak = bestStreak,
                typeCounts = typeCounts,
                uniqueDishes = uniqueDishes,
                topDish = topDish?.key ?: "",
                topDishCount = topDish?.value ?: 0,
                morningCount = morning,
                nightCount = night,
                afternoonCount = afternoon,
                totalFeelingChars = totalFeelingChars,
                avgFeelingChars = avgFeelingChars,
                maxDailyTypes = maxDailyTypes,
                dominantPeriod = dominantPeriod,
                scholarDate = scholarDate
            )
        }
    }
}

data class MonthlyStats(
    val totalCount: Int = 0,
    val daysChecked: Int = 0,
    val bestStreak: Int = 0,
    val typeCounts: Map<String, Int> = emptyMap(),
    val uniqueDishes: Int = 0,
    val topDish: String = "",
    val topDishCount: Int = 0,
    val morningCount: Int = 0,
    val nightCount: Int = 0,
    val afternoonCount: Int = 0,
    val totalFeelingChars: Int = 0,
    val avgFeelingChars: Int = 0,
    val maxDailyTypes: Int = 0,
    val dominantPeriod: String = "",
    val scholarDate: String = ""
)
