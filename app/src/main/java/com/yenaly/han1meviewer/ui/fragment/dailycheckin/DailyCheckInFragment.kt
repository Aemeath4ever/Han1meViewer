package com.yenaly.han1meviewer.ui.fragment.dailycheckin

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.han1meviewer.ui.viewmodel.CheckInCalendarViewModel
import com.yenaly.han1meviewer.ui.widget.CheckInWidgetProvider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class DailyCheckInFragment : Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true).apply {
            duration = 500L
        }
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false).apply {
            duration = 500L
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HanimeTheme {
                    val activity = requireActivity()
                    var showReport by rememberSaveable { mutableStateOf(false) }
                    var isReportFullscreen by rememberSaveable { mutableStateOf(false) }

                    LaunchedEffect(isReportFullscreen) {
                        activity.requestedOrientation = if (isReportFullscreen)
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                        val window = activity.window
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (isReportFullscreen) {
                                window.insetsController?.apply {
                                    hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                }
                            } else {
                                window.insetsController?.apply {
                                    show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                                }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            window.decorView.systemUiVisibility = if (isReportFullscreen) {
                                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
                            } else {
                                View.SYSTEM_UI_FLAG_VISIBLE
                            }
                        }
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            val window = activity.window
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                window.insetsController?.apply {
                                    show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                            }
                        }
                    }

                    val scrollBehavior =
                        TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
                    Scaffold(
                        modifier = Modifier
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        topBar = {
                            CenterAlignedTopAppBar(
                                colors = topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                title = {
                                    Text(
                                        stringResource(R.string.has_cum),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                },
                                navigationIcon = {
                                    IconButton(
                                        onClick = {
                                            lifecycleScope.launch { findNavController().navigateUp() }
                                        }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Localized description"
                                        )
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { showReport = true }) {
                                        Icon(
                                            imageVector = Icons.Filled.DateRange,
                                            contentDescription = stringResource(R.string.checkin_report)
                                        )
                                    }
                                    IconButton(onClick = {
                                        val activity = requireActivity()
                                        val mgr = AppWidgetManager.getInstance(activity)
                                        Toast.makeText(activity, "部分rom不支持引导式添加，请手动添加小部件", Toast.LENGTH_SHORT).show()
                                        if (mgr.isRequestPinAppWidgetSupported) {
                                            mgr.requestPinAppWidget(
                                                ComponentName(activity, CheckInWidgetProvider::class.java),
                                                null,
                                                null
                                            )
                                        } else {
                                            Toast.makeText(activity, R.string.widget_not_supported, Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = stringResource(R.string.add_widget)
                                        )
                                    }
                                },
                                scrollBehavior = scrollBehavior,
                            )
                        },
                    ) { innerPadding ->
                        CalendarCheckInScreen(
                            innerPadding,
                            showReport = showReport,
                            onDismissReport = { showReport = false; isReportFullscreen = false },
                            isReportFullscreen = isReportFullscreen,
                            onToggleReportFullscreen = { isReportFullscreen = !isReportFullscreen }
                        )
                    }
                }

            }
        }
    }
}

// ═══════════════════════════════════════════
//  Contribution Report (GitHub-style calendar heatmap)
// ═══════════════════════════════════════════

private val contributionColors = listOf(
    Color.Transparent,
    Color(0xFF9BE9A8),
    Color(0xFF40C463),
    Color(0xFF30A14E),
    Color(0xFF216E39),
)

private fun getContributionLevel(count: Int): Int = when {
    count <= 0 -> 0
    count == 1 -> 1
    count == 2 -> 2
    count in 3..4 -> 3
    else -> 4
}

private fun buildYearWeeks(year: Int): List<List<LocalDate?>> {
    val start = LocalDate.of(year, 1, 1)
    val end = LocalDate.of(year, 12, 31)
    val weeks = mutableListOf<MutableList<LocalDate?>>()
    var currentWeek = MutableList<LocalDate?>(7) { null }
    var dayIndex = start.dayOfWeek.value - 1
    var date = start
    while (!date.isAfter(end)) {
        currentWeek[dayIndex] = date
        dayIndex++
        if (dayIndex == 7) {
            weeks.add(currentWeek)
            currentWeek = MutableList(7) { null }
            dayIndex = 0
        }
        date = date.plusDays(1)
    }
    if (currentWeek.any { it != null }) {
        weeks.add(currentWeek)
    }
    return weeks
}

private fun buildMonthLabels(year: Int, weeks: List<List<LocalDate?>>, monthFormat: String): List<Pair<String, Int>> {
    val labels = mutableListOf<Pair<String, Int>>()
    for (month in 1..12) {
        val firstDay = LocalDate.of(year, month, 1)
        val weekIdx = weeks.indexOfFirst { week -> firstDay in week }
        if (weekIdx >= 0) {
            labels.add(monthFormat.format(month) to weekIdx)
        }
    }
    return labels
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContributionReportDialog(
    viewModel: CheckInCalendarViewModel,
    onDismiss: () -> Unit,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {}
) {
    val today = LocalDate.now()
    var selectedYear by remember { mutableIntStateOf(today.year) }
    var viewMode by remember { mutableStateOf("year") }
    var selectedMonth by remember { mutableIntStateOf(today.monthValue) }
    val yearRecords = viewModel.yearRecords

    LaunchedEffect(selectedYear) {
        viewModel.loadYearRecords(selectedYear)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.checkin_report)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "close"
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewMode = "year" }) {
                            Text(
                                stringResource(R.string.report_year),
                                fontWeight = if (viewMode == "year") FontWeight.Bold else FontWeight.Normal,
                                color = if (viewMode == "year") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(onClick = { viewMode = "month" }) {
                            Text(
                                stringResource(R.string.report_month),
                                fontWeight = if (viewMode == "month") FontWeight.Bold else FontWeight.Normal,
                                color = if (viewMode == "month") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = onToggleFullscreen) {
                            Image(
                                painter = painterResource(R.drawable.baseline_screen_rotation_24),
                                contentDescription = if (isFullscreen)
                                    stringResource(R.string.report_portrait)
                                else
                                    stringResource(R.string.report_landscape),
                                modifier = Modifier.size(24.dp),
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                if (viewMode == "year") {
                    YearContributionView(
                        year = selectedYear,
                        records = yearRecords,
                        today = today,
                        onYearChange = { selectedYear = it }
                    )
                } else {
                    MonthContributionView(
                        year = selectedYear,
                        month = selectedMonth,
                        records = yearRecords,
                        today = today,
                        onYearChange = { selectedYear = it },
                        onMonthChange = { selectedMonth = it }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Stats summary ──
                val filteredRecords = if (viewMode == "year") {
                    yearRecords.filterKeys { it.year == selectedYear }
                } else {
                    yearRecords.filterKeys {
                        it.year == selectedYear && it.monthValue == selectedMonth
                    }
                }
                val totalCount = filteredRecords.values.sum()
                val totalDays = filteredRecords.count { it.value > 0 }
                val maxDay = filteredRecords.maxByOrNull { it.value }?.value ?: 0

                if (totalDays > 0) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(
                                icon = Icons.Filled.DateRange,
                                label = stringResource(R.string.report_total),
                                value = totalCount.toString()
                            )
                            HorizontalDivider(
                                modifier = Modifier
                                    .height(48.dp)
                                    .width(1.dp)
                            )
                            StatItem(
                                icon = Icons.Filled.Star,
                                label = stringResource(R.string.report_days),
                                value = totalDays.toString()
                            )
                            HorizontalDivider(
                                modifier = Modifier
                                    .height(48.dp)
                                    .width(1.dp)
                            )
                            StatItem(
                                icon = Icons.Filled.Favorite,
                                label = stringResource(R.string.report_max_day),
                                value = maxDay.toString()
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.report_no_data),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                ContributionLegend()

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun YearContributionView(
    year: Int,
    records: Map<LocalDate, Int>,
    today: LocalDate,
    onYearChange: (Int) -> Unit
) {
    val weeks = remember(year) { buildYearWeeks(year) }
    val monthFormat = stringResource(R.string.report_month_format)
    val monthLabels = remember(year) { buildMonthLabels(year, weeks, monthFormat) }
    val dayLabels = listOf(
        stringResource(R.string.mon), stringResource(R.string.tue),
        stringResource(R.string.wed), stringResource(R.string.thu),
        stringResource(R.string.fri), stringResource(R.string.sat),
        stringResource(R.string.sun)
    )
    val cellSize = 14.dp
    val cellPadding = 1.dp
    val columnWidth = cellSize + cellPadding * 2
    val labelColWidth = 24.dp
    val scrollState = rememberScrollState()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onYearChange(year - 1) }) {
                Icon(painterResource(R.drawable.previous_double_arrow_24), "previous year")
            }
            Text(
                text = stringResource(R.string.report_year_format, year),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { onYearChange(year + 1) },
                enabled = year < today.year
            ) {
                Icon(painterResource(R.drawable.next_double_arrow_24), "next year")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            // Month label row
            Row(
                modifier = Modifier.height(20.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Spacer(modifier = Modifier.width(labelColWidth))
                Box(modifier = Modifier.width(columnWidth * weeks.size)) {
                    monthLabels.forEach { (label, weekIdx) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .offset(x = columnWidth * weekIdx)
                                .width(columnWidth * 4)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Grid rows
            for (dayIdx in 0..6) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dayLabels[dayIdx],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(labelColWidth),
                        textAlign = TextAlign.Center
                    )
                    weeks.forEach { week ->
                        val date = week.getOrNull(dayIdx)
                        val count = date?.let { records[it] } ?: 0
                        val level = getContributionLevel(count)
                        val isToday = date == today
                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .padding(cellPadding)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (count > 0) contributionColors[level]
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                )
                                .then(
                                    if (isToday) Modifier.border(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(2.dp)
                                    ) else Modifier
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthContributionView(
    year: Int,
    month: Int,
    records: Map<LocalDate, Int>,
    today: LocalDate,
    onYearChange: (Int) -> Unit,
    onMonthChange: (Int) -> Unit
) {
    val yearMonth = YearMonth.of(year, month)
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOfWeek = yearMonth.atDay(1).dayOfWeek.value
    val dayLabels = listOf(
        stringResource(R.string.mon), stringResource(R.string.tue),
        stringResource(R.string.wed), stringResource(R.string.thu),
        stringResource(R.string.fri), stringResource(R.string.sat),
        stringResource(R.string.sun)
    )

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (month == 1) {
                    onYearChange(year - 1)
                    onMonthChange(12)
                } else {
                    onMonthChange(month - 1)
                }
            }) {
                Icon(painterResource(R.drawable.previous_double_arrow_24), "previous month")
            }
            Text(
                text = stringResource(R.string.report_year_month_format, year, month),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = {
                    val now = YearMonth.now()
                    val current = YearMonth.of(year, month)
                    if (current.isBefore(now)) {
                        if (month == 12) {
                            onYearChange(year + 1)
                            onMonthChange(1)
                        } else {
                            onMonthChange(month + 1)
                        }
                    }
                },
                enabled = YearMonth.of(year, month).isBefore(YearMonth.now())
            ) {
                Icon(painterResource(R.drawable.next_double_arrow_24), "next month")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Day headers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            dayLabels.forEach { day ->
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        // Month grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(((firstDayOfWeek - 1 + daysInMonth + 6) / 7 * 52).dp)
        ) {
            items(firstDayOfWeek - 1) {
                Spacer(modifier = Modifier.size(48.dp))
            }
            items(daysInMonth) { day ->
                val date = yearMonth.atDay(day + 1)
                val count = records[date] ?: 0
                val level = getContributionLevel(count)
                val isToday = date == today
                val cellBg = if (count > 0) contributionColors[level]
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cellBg)
                        .then(
                            if (isToday) Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp)
                            ) else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = (day + 1).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (count > 0)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        if (count > 0) {
                            Text(
                                text = "x$count",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributionLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.report_legend_less),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        contributionColors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .padding(1.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (color == Color.Transparent)
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        else color
                    )
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.report_legend_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarCheckInScreen(
    paddingValues: PaddingValues,
    viewModel: CheckInCalendarViewModel = viewModel(),
    showReport: Boolean = false,
    onDismissReport: () -> Unit = {},
    isReportFullscreen: Boolean = false,
    onToggleReportFullscreen: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentMonth by viewModel.currentMonth
    val records = viewModel.records
    val checkedDays by viewModel.checkedDays
    val monthlyTotal by viewModel.monthlyTotal
    val today = LocalDate.now()
    val todayCount = records[today] ?: 0

    // ── Dialog states for funny interactions ──
    var forgotDialogDate by remember { mutableStateOf<LocalDate?>(null) }
    var suckBackDialogDate by remember { mutableStateOf<LocalDate?>(null) }
    var calendarDialogDate by remember { mutableStateOf<LocalDate?>(null) }
    var showEasterEgg by remember { mutableStateOf("") }
    var eggVisible by remember { mutableStateOf(false) }

    // Compute streaks from records (recalculates on every recomposition since records is SnapshotStateMap)
    val (_, bestStreakThisMonth) = computeStreaks(records, currentMonth)

    val animatedCheckedDays by animateIntAsState(targetValue = checkedDays, label = "days")
    val animatedMonthlyTotal by animateIntAsState(targetValue = monthlyTotal, label = "total")
    val animatedBestStreak by animateIntAsState(targetValue = bestStreakThisMonth, label = "streak")

    val anchorMonth = remember { YearMonth.now() }
    val initialPage = Int.MAX_VALUE / 2
    val pagerState = rememberPagerState(initialPage = initialPage) { Int.MAX_VALUE }

    LaunchedEffect(currentMonth) {
        val monthsDiff = ChronoUnit.MONTHS.between(anchorMonth, currentMonth).toInt()
        val targetPage = initialPage + monthsDiff
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                val pageMonth = anchorMonth.plusMonths((page - initialPage).toLong())
                if (pageMonth != currentMonth) {
                    if (pageMonth.isAfter(currentMonth)) viewModel.nextMonth()
                    else viewModel.previousMonth()
                }
            }
    }

    // Easter egg auto-dismiss
    LaunchedEffect(showEasterEgg) {
        if (showEasterEgg.isNotEmpty()) {
            eggVisible = true
            kotlinx.coroutines.delay(1500)
            eggVisible = false
        }
    }

    val handleDateClick: (LocalDate) -> Unit = { date ->
        when {
            date.isAfter(today) -> {
                calendarDialogDate = date
            }
            date.isBefore(today) && (records[date] ?: 0) == 0 -> {
                forgotDialogDate = date
            }
            else -> {
                viewModel.incrementCheckIn(date)
                // Random easter eggs
                val count = (records[date] ?: 0) + 1
                when {
                    count == 6 -> showEasterEgg = context.getString(R.string.egg_six)
                    count == 9 -> showEasterEgg = context.getString(R.string.egg_nine)
                    count % 10 == 0 -> showEasterEgg = context.getString(R.string.egg_round, count)
                }
            }
        }
    }

    val handleDateLongClick: (LocalDate) -> Unit = { date ->
        val count = records[date] ?: 0
        if (count > 0 && date.isBefore(today)) {
            suckBackDialogDate = date
        } else if (count > 0) {
            viewModel.clearCheckIn(date)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Today's check-in card ──
        TodayCheckInCard(
            today = today,
            count = todayCount,
            onCheckIn = { viewModel.incrementCheckIn(today) },
            onClear = { viewModel.clearCheckIn(today) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Stats card ──
        StatsCard(
            checkedDays = animatedCheckedDays,
            monthlyTotal = animatedMonthlyTotal,
            bestStreak = animatedBestStreak
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Month navigation header ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.checkin_calendar),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.previousMonth() }) {
                    Icon(painterResource(R.drawable.previous_double_arrow_24), "previous")
                }
                Text(
                    text = currentMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { viewModel.nextMonth() }) {
                    Icon(painterResource(R.drawable.next_double_arrow_24), "next")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Calendar grid ──
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1,
            key = { page -> page }
        ) { page ->
            val monthForPage = anchorMonth.plusMonths((page - initialPage).toLong())
            CalendarGrid(
                yearMonth = monthForPage,
                records = records,
                today = today,
                onDateClick = handleDateClick,
                onDateLongClick = handleDateLongClick
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Easter egg toast ──
        AnimatedVisibility(
            visible = eggVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = showEasterEgg,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Achievement / milestone ──
        AchievementSection(
            checkedDays = checkedDays,
            monthlyTotal = monthlyTotal,
            bestStreak = bestStreakThisMonth
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Tips ──
        Text(
            text = stringResource(R.string.checkin_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    // ── "Forgot to check in?" dialog for past unfilled dates ──
    forgotDialogDate?.let { date ->
        AlertDialog(
            onDismissRequest = { forgotDialogDate = null },
            title = {
                Text(
                    text = stringResource(R.string.forgot_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.forgot_message,
                        date.format(DateTimeFormatter.ofPattern("MM月dd日"))
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.incrementCheckIn(date)
                    forgotDialogDate = null
                }) {
                    Text(stringResource(R.string.forgot_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { forgotDialogDate = null }) {
                    Text(stringResource(R.string.forgot_dismiss))
                }
            }
        )
    }

    // ── "Schedule fap?" dialog for future dates ──
    calendarDialogDate?.let { date ->
        AlertDialog(
            onDismissRequest = { calendarDialogDate = null },
            title = {
                Text(
                    text = stringResource(R.string.calendar_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.calendar_dialog_message,
                        date.format(DateTimeFormatter.ofPattern("MM月dd日"))
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    createCalendarEvent(context, date)
                    calendarDialogDate = null
                }) {
                    Text(stringResource(R.string.calendar_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { calendarDialogDate = null }) {
                    Text(stringResource(R.string.calendar_dialog_dismiss))
                }
            }
        )
    }

    // ── "Suck it back?" dialog when clearing past dates ──
    suckBackDialogDate?.let { date ->
        val count = records[date] ?: 0
        AlertDialog(
            onDismissRequest = { suckBackDialogDate = null },
            title = {
                Text(
                    text = stringResource(R.string.suck_back_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.suck_back_message,
                        date.format(DateTimeFormatter.ofPattern("MM月dd日")), count
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCheckIn(date)
                    suckBackDialogDate = null
                    Toast.makeText(context, R.string.suck_back_done, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.suck_back_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { suckBackDialogDate = null }) {
                    Text(stringResource(R.string.suck_back_dismiss))
                }
            }
        )
    }

    // ── Contribution Report ──
    if (showReport) {
        ContributionReportDialog(
            viewModel = viewModel,
            onDismiss = onDismissReport,
            isFullscreen = isReportFullscreen,
            onToggleFullscreen = onToggleReportFullscreen
        )
    }
}

private fun createCalendarEvent(context: android.content.Context, date: LocalDate) {
    val intent = Intent(Intent.ACTION_INSERT).apply {
        setDataAndType(CalendarContract.Events.CONTENT_URI,"vnd.android.cursor.dir/event")
        putExtra(CalendarContract.Events.TITLE, context.getString(R.string.calendar_title, date.monthValue, date.dayOfMonth))
        putExtra(CalendarContract.Events.DESCRIPTION, context.getString(R.string.calendar_desc))
        putExtra(CalendarContract.Events.EVENT_LOCATION, context.getString(R.string.calendar_location))
        putExtra(
            CalendarContract.EXTRA_EVENT_BEGIN_TIME,
            date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        putExtra(
            CalendarContract.EXTRA_EVENT_END_TIME,
            date.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        putExtra(CalendarContract.Events.ALL_DAY, true)
        putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
    }
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_calendar_app, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun TodayCheckInCard(
    today: LocalDate,
    count: Int,
    onCheckIn: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (count > 0)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = today.format(DateTimeFormatter.ofPattern("MM月dd日 EEEE")),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (count > 0) "${stringResource(R.string.today_checked)} $count ${stringResource(R.string.times)}"
                    else stringResource(R.string.not_checked_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onCheckIn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.checkin))
                }
                if (count > 0) {
                    TextButton(onClick = onClear) {
                        Text(
                            stringResource(R.string.clear_checkin),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    checkedDays: Int,
    monthlyTotal: Int,
    bestStreak: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(
                icon = Icons.Filled.Star,
                label = stringResource(R.string.this_month_checkin),
                value = stringResource(R.string.days, checkedDays)
            )
            HorizontalDivider(
                modifier = Modifier
                    .height(48.dp)
                    .width(1.dp)
            )
            StatItem(
                icon = Icons.Filled.Favorite,
                label = stringResource(R.string.has_cum_days),
                value = stringResource(R.string.counts, monthlyTotal)
            )
            HorizontalDivider(
                modifier = Modifier
                    .height(48.dp)
                    .width(1.dp)
            )
            StatItem(
                icon = Icons.Filled.Favorite,
                label = stringResource(R.string.best_streak),
                value = "${bestStreak}${stringResource(R.string.day_unit)}"
            )
        }
    }
}

@Composable
private fun RowScope.StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AchievementSection(
    checkedDays: Int,
    monthlyTotal: Int,
    bestStreak: Int
) {
    AnimatedVisibility(
        visible = checkedDays > 0,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val (emoji, title, subtitle) = when {
                    monthlyTotal >= 200 -> Triple(
                        "\uD83E\uDD34", stringResource(R.string.legend_title),
                        stringResource(R.string.egg_god, monthlyTotal)
                    )
                    monthlyTotal >= 100 -> Triple(
                        "\uD83D\uDC51", stringResource(R.string.champion_title),
                        stringResource(R.string.achievement_desc_top, monthlyTotal)
                    )
                    monthlyTotal >= 69 -> Triple(
                        "\uD83D\uDE0F", stringResource(R.string.nice_title),
                        stringResource(R.string.egg_nice, monthlyTotal)
                    )
                    monthlyTotal >= 50 -> Triple(
                        "\uD83C\uDFC6", stringResource(R.string.champion_title),
                        stringResource(R.string.achievement_desc_top, monthlyTotal)
                    )
                    checkedDays >= 25 -> Triple(
                        "\uD83D\uDD25", stringResource(R.string.on_fire_title),
                        stringResource(R.string.achievement_desc_days, checkedDays)
                    )
                    checkedDays >= 15 -> Triple(
                        "\uD83D\uDE80", stringResource(R.string.great_title),
                        stringResource(R.string.achievement_desc_days, checkedDays)
                    )
                    bestStreak >= 7 -> Triple(
                        "\u2B50", stringResource(R.string.week_streak_title),
                        stringResource(R.string.achievement_desc_streak, bestStreak)
                    )
                    bestStreak >= 3 -> Triple(
                        "\uD83D\uDCAA", stringResource(R.string.streak_title),
                        stringResource(R.string.egg_streak, bestStreak)
                    )
                    else -> Triple(
                        "\uD83D\uDC4D", stringResource(R.string.keep_going_title),
                        stringResource(R.string.achievement_desc_keep)
                    )
                }
                Text(text = emoji, fontSize = 36.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                // Bonus egg: 11/11 Singles Day
                val today = LocalDate.now()
                if (today.monthValue == 11 && today.dayOfMonth == 11 && checkedDays == 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.egg_singles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * Compute current consecutive streak and best streak within the given month
 */
private fun computeStreaks(
    records: Map<LocalDate, Int>,
    month: YearMonth
): Pair<Int, Int> {
    val today = LocalDate.now()
    // Current streak: consecutive days ending today with count > 0
    var currentStreak = 0
    var cursor = today
    while ((records[cursor] ?: 0) > 0) {
        currentStreak++
        cursor = cursor.minusDays(1)
    }

    // Best streak within the displayed month
    var bestStreak = 0
    var streak = 0
    for (day in 1..month.lengthOfMonth()) {
        val date = month.atDay(day)
        if ((records[date] ?: 0) > 0) {
            streak++
            if (streak > bestStreak) bestStreak = streak
        } else {
            streak = 0
        }
    }
    return currentStreak to bestStreak
}

@Composable
fun CalendarGrid(
    yearMonth: YearMonth,
    records: Map<LocalDate, Int>,
    today: LocalDate,
    onDateClick: (LocalDate) -> Unit,
    onDateLongClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstDayOfMonth = yearMonth.atDay(1)
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        userScrollEnabled = false,
        modifier = modifier.fillMaxWidth()
    ) {
        item(span = { GridItemSpan(7) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                listOf(
                    stringResource(R.string.mon), stringResource(R.string.tue),
                    stringResource(R.string.wed), stringResource(R.string.thu),
                    stringResource(R.string.fri), stringResource(R.string.sat),
                    stringResource(R.string.sun)
                ).forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }

        items(firstDayOfWeek - 1) {
            Spacer(modifier = Modifier.size(48.dp))
        }

        items(daysInMonth) { day ->
            val date = yearMonth.atDay(day + 1)
            val count = records[date] ?: 0
            val isToday = date == today
            val transition = updateTransition(targetState = count > 0, label = "check")

            val bgColor by transition.animateColor(label = "bg") { checked ->
                when {
                    checked -> MaterialTheme.colorScheme.primaryContainer
                    isToday -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    else -> Color.Transparent
                }
            }
            val borderColor by transition.animateColor(label = "border") { checked ->
                when {
                    isToday && checked -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.tertiary
                    checked -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                }
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .border(
                        width = if (isToday) 2.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .combinedClickable(
                        onClick = { onDateClick(date) },
                        onLongClick = { onDateLongClick(date) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = (day + 1).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isToday -> MaterialTheme.colorScheme.onTertiaryContainer
                            count > 0 -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (count > 0) {
                        Text(
                            text = "x$count",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}