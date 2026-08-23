package com.tksapec.ywidget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tksapec.ywidget.data.DisplayStyle
import com.tksapec.ywidget.data.LauncherAppSlot
import com.tksapec.ywidget.data.LauncherAppShortcut
import com.tksapec.ywidget.data.NewsCategory
import com.tksapec.ywidget.data.WeatherLocationMode
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.WidgetSettings
import com.tksapec.ywidget.widget.YWidgetReceiver
import com.tksapec.ywidget.widget.safeUpdateAll
import com.tksapec.ywidget.work.RainAlertWorker
import com.tksapec.ywidget.work.RefreshWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val preferences by lazy { WidgetPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasPlacedWidgets()) {
            lifecycleScope.launch {
                RefreshWorker.schedulePeriodicFromSettings(this@MainActivity)
                RainAlertWorker.scheduleFromSettings(this@MainActivity)
            }
        }

        setContent {
            val settings by preferences.settingsFlow.collectAsStateWithLifecycle(
                initialValue = WidgetSettings(),
            )

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        settings = settings,
                        onCategoriesChanged = { categories ->
                            lifecycleScope.launch {
                                preferences.updateSelectedCategories(categories)
                                enqueueImmediateRefresh()
                            }
                        },
                        onDisplayCountSelected = { count ->
                            lifecycleScope.launch {
                                preferences.updateDisplayCount(count)
                                safeUpdateAll(this@MainActivity)
                            }
                        },
                        onDisplayStyleSelected = { style ->
                            lifecycleScope.launch {
                                preferences.updateDisplayStyle(style)
                                safeUpdateAll(this@MainActivity)
                            }
                        },
                        onIntervalSelected = { minutes ->
                            lifecycleScope.launch {
                                preferences.updateInterval(minutes)
                                if (this@MainActivity.hasPlacedWidgets()) {
                                    RefreshWorker.schedulePeriodicFromSettings(this@MainActivity)
                                }
                            }
                        },
                        onWeatherLocationModeSelected = { mode ->
                            lifecycleScope.launch {
                                preferences.updateWeatherLocationMode(mode)
                                if (mode == WeatherLocationMode.Disabled) {
                                    RainAlertWorker.scheduleFromSettings(this@MainActivity)
                                    safeUpdateAll(this@MainActivity)
                                } else {
                                    enqueueImmediateRefresh()
                                }
                            }
                        },
                        onRainAlertEnabledChanged = { enabled ->
                            lifecycleScope.launch {
                                preferences.updateRainAlertEnabled(enabled)
                                if (enabled) {
                                    RainAlertWorker.enqueueImmediateIfConfigured(this@MainActivity)
                                } else {
                                    RainAlertWorker.cancelAndAwait(this@MainActivity)
                                }
                                safeUpdateAll(this@MainActivity)
                            }
                        },
                        onFixedLocationSaved = { query ->
                            lifecycleScope.launch {
                                preferences.updateFixedLocationQuery(query)
                                preferences.updateWeatherLocationMode(WeatherLocationMode.Fixed)
                                enqueueImmediateRefresh()
                            }
                        },
                        onLauncherAppSlotsChanged = { slots ->
                            lifecycleScope.launch {
                                preferences.updateLauncherAppSlots(slots)
                                safeUpdateAll(this@MainActivity)
                            }
                        },
                        onRefreshStateReset = {
                            lifecycleScope.launch {
                                preferences.clearRefreshState()
                                safeUpdateAll(this@MainActivity)
                            }
                        },
                        onRefreshNow = {
                            lifecycleScope.launch { enqueueImmediateRefresh() }
                        },
                    )
                }
            }
        }
    }

    private suspend fun enqueueImmediateRefresh() {
        try {
            RefreshWorker.enqueueImmediateByUser(this)
            RainAlertWorker.enqueueImmediateIfConfigured(this)
            safeUpdateAll(this)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            safeUpdateAll(this)
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: WidgetSettings,
    onCategoriesChanged: (Set<NewsCategory>) -> Unit,
    onDisplayCountSelected: (Int) -> Unit,
    onDisplayStyleSelected: (DisplayStyle) -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onWeatherLocationModeSelected: (WeatherLocationMode) -> Unit,
    onRainAlertEnabledChanged: (Boolean) -> Unit,
    onFixedLocationSaved: (String) -> Unit,
    onLauncherAppSlotsChanged: (List<LauncherAppSlot>) -> Unit,
    onRefreshStateReset: () -> Unit,
    onRefreshNow: () -> Unit,
) {
    val context = LocalContext.current
    val launcherAppOptions = remember(context) { loadLauncherAppOptions(context) }
    var fixedLocationInput by remember(settings.fixedLocationQuery) {
        mutableStateOf(settings.fixedLocationQuery)
    }
    var locationGranted by remember {
        mutableStateOf(context.hasLocationPermission())
    }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            context.hasLocationPermission()
        if (locationGranted && settings.weatherLocationMode == WeatherLocationMode.Current) {
            onWeatherLocationModeSelected(WeatherLocationMode.Current)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "YWidget 設定",
            style = MaterialTheme.typography.titleLarge,
        )

        SettingBlock(label = "表示カテゴリ") {
            CategorySelector(
                selected = settings.selectedCategories,
                onChanged = onCategoriesChanged,
            )
        }

        SettingRow(label = "表示スタイル") {
            DisplayStyleMenu(
                selected = settings.displayStyle,
                onSelected = onDisplayStyleSelected,
            )
        }

        SettingRow(label = "表示件数") {
            CountMenu(
                selected = settings.displayCount,
                onSelected = onDisplayCountSelected,
            )
        }

        SettingRow(label = "更新間隔") {
            IntervalMenu(
                selected = settings.updateIntervalMinutes,
                onSelected = onIntervalSelected,
            )
        }

        SettingBlock(label = "ランチャーボタン") {
            LauncherAppSelector(
                selectedSlots = settings.launcherAppSlots,
                availableApps = launcherAppOptions,
                onChanged = onLauncherAppSlotsChanged,
            )
        }

        SettingBlock(label = "天気地域") {
            WeatherLocationSelector(
                selected = settings.weatherLocationMode,
                locationGranted = locationGranted,
                onSelected = onWeatherLocationModeSelected,
            )
            if (settings.weatherLocationMode == WeatherLocationMode.Current) {
                Text(
                    text = "現在地は端末状態により取得できない場合があります。安定運用には固定地域をおすすめします。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SettingBlock(label = "Yahoo!雨予報") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = settings.rainAlertEnabled,
                    onCheckedChange = onRainAlertEnabledChanged,
                    enabled = settings.weatherLocationMode != WeatherLocationMode.Disabled,
                )
                Text(if (settings.rainAlertEnabled) "有効" else "無効")
            }
            Text(
                text = if (settings.weatherLocationMode == WeatherLocationMode.Disabled) {
                    "現在地または固定地域を選択すると有効にできます。"
                } else {
                    "有効にすると、約15分間隔で選択地域と周辺8地点の座標をYahoo!気象情報APIへ送信して雨予報を確認します。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingRow(label = "位置情報権限") {
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
            ) {
                Text(if (locationGranted) "許可済み" else "許可する")
            }
        }

        SettingBlock(label = "固定地域") {
            OutlinedTextField(
                value = fixedLocationInput,
                onValueChange = { fixedLocationInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("例: 東京都新宿区") },
            )
            Button(
                onClick = { onFixedLocationSaved(fixedLocationInput) },
                enabled = fixedLocationInput.isNotBlank(),
            ) {
                Text("保存して更新")
            }
            settings.locationLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = "表示中: $label",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            settings.lastWeatherError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = "天気更新エラー: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            settings.lastRainAlertError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = "雨予報エラー: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "更新間隔の10分は設定として保存しますが、Androidの制約により定期実行は15分以上で登録されます。雨予報は有効時のみ別の約15分周期で確認します。",
            style = MaterialTheme.typography.bodySmall,
        )

        SettingBlock(label = "更新操作") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefreshNow) {
                    Text("今すぐ再取得")
                }
                OutlinedButton(onClick = onRefreshStateReset) {
                    Text("状態をリセット")
                }
            }
        }

        SettingBlock(label = "更新診断") {
            OutlinedButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                Text(if (diagnosticsExpanded) "診断を閉じる" else "診断を表示")
            }
            if (diagnosticsExpanded) {
                RefreshDiagnostics(settings)
            }
        }
    }
}

@Composable
private fun RefreshDiagnostics(settings: WidgetSettings) {
    val rows = listOf(
        "最終ニュース取得" to formatDiagnosticTime(settings.newsUpdatedAtMillis),
        "最終天気取得" to formatDiagnosticTime(settings.weatherUpdatedAtMillis),
        "雨予報有効" to settings.rainAlertEnabled.toString(),
        "最終雨予報取得" to formatDiagnosticTime(settings.rainAlertUpdatedAtMillis),
        "雨予報状態" to "${settings.rainAlertLevel.name}, rainAt=${formatDiagnosticTime(settings.rainAlertRainAtMillis ?: 0L)}, nearby=${settings.rainAlertNearbyOnly}",
        "雨予報エラー" to settings.lastRainAlertError.orEmpty().ifBlank { "なし" },
        "最終更新開始" to formatDiagnosticTime(settings.lastRefreshStartedAtMillis),
        "最終更新終了" to formatDiagnosticTime(settings.lastRefreshFinishedAtMillis),
        "更新世代" to settings.refreshGeneration.toString(),
        "現在状態" to "queued=${settings.refreshQueued}, news=${settings.newsRefreshing}, weather=${settings.weatherRefreshing}",
        "最終結果" to (settings.lastRefreshResult?.label ?: "未実行"),
        "結果メッセージ" to settings.lastRefreshMessage.orEmpty().ifBlank { "なし" },
        "ニュースエラー" to settings.lastNewsError.orEmpty().ifBlank { "なし" },
        "天気エラー" to settings.lastWeatherError.orEmpty().ifBlank { "なし" },
        "最終現在地" to formatDiagnosticTime(settings.lastCurrentLocationAtMillis),
        "最終ウィジェット反映" to formatDiagnosticTime(settings.lastWidgetUpdatedAtMillis),
        "ウィジェット反映エラー" to settings.lastWidgetUpdateError.orEmpty().ifBlank { "なし" },
    )
    rows.forEach { (label, value) ->
        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatDiagnosticTime(value: Long): String {
    if (value <= 0L) return "未記録"
    return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
}

@Composable
private fun SettingBlock(
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

@Composable
private fun SettingRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

@Composable
private fun CategorySelector(
    selected: Set<NewsCategory>,
    onChanged: (Set<NewsCategory>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NewsCategory.entries.forEach { category ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = category in selected,
                    onCheckedChange = { checked ->
                        val next = if (checked) {
                            selected + category
                        } else {
                            selected - category
                        }
                        if (next.isNotEmpty()) onChanged(next)
                    },
                )
                Text(category.label)
            }
        }
    }
}

@Composable
private fun LauncherAppSelector(
    selectedSlots: List<LauncherAppSlot>,
    availableApps: List<LauncherAppShortcut>,
    onChanged: (List<LauncherAppSlot>) -> Unit,
) {
    if (availableApps.isEmpty() && selectedSlots.none { it.app != null }) {
        Text(
            text = "起動可能なアプリが見つかりません",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val installedPackageNames = availableApps.map { it.packageName }.toSet()
    repeat(3) { slotIndex ->
        val selected = selectedSlots.firstOrNull { it.slotIndex == slotIndex }?.app
        val selectedInstalled = selected == null || selected.packageName in installedPackageNames
        SettingRow(label = "スロット${slotIndex + 1}") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LauncherAppMenu(
                    slotIndex = slotIndex,
                    selected = selected,
                    selectedInstalled = selectedInstalled,
                    selectedSlots = selectedSlots,
                    availableApps = availableApps,
                    onSelected = { app ->
                        onChanged(updateLauncherAppSlot(selectedSlots, slotIndex, app))
                    },
                )
                Button(
                    onClick = { onChanged(updateLauncherAppSlot(selectedSlots, slotIndex, null)) },
                    enabled = selected != null,
                ) {
                    Text("解除")
                }
            }
        }
    }
}

@Composable
private fun LauncherAppMenu(
    slotIndex: Int,
    selected: LauncherAppShortcut?,
    selectedInstalled: Boolean,
    selectedSlots: List<LauncherAppSlot>,
    availableApps: List<LauncherAppShortcut>,
    onSelected: (LauncherAppShortcut) -> Unit,
) {
    val usedByOtherSlots = selectedSlots
        .filter { it.slotIndex != slotIndex }
        .mapNotNull { it.app?.packageName }
        .toSet()
    val selectableApps = availableApps.filter { it.packageName !in usedByOtherSlots }
    var expanded by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { expanded = true }) {
        Text(
            text = when {
                selected == null -> "未登録"
                selectedInstalled -> selected.displayName
                else -> "未インストール: ${selected.displayName}"
            },
            color = if (selectedInstalled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        selectableApps.forEach { app ->
            DropdownMenuItem(
                text = { Text(app.displayName) },
                onClick = {
                    expanded = false
                    onSelected(app)
                },
            )
        }
    }
}

private fun updateLauncherAppSlot(
    selectedSlots: List<LauncherAppSlot>,
    slotIndex: Int,
    app: LauncherAppShortcut?,
): List<LauncherAppSlot> {
    val usedPackages = mutableSetOf<String>()
    return (0..2).map { index ->
        val nextApp = if (index == slotIndex) {
            app
        } else {
            selectedSlots.firstOrNull { it.slotIndex == index }?.app
        }?.takeIf {
            it.displayName.isNotBlank() &&
                it.packageName.isNotBlank() &&
                usedPackages.add(it.packageName)
        }
        LauncherAppSlot(slotIndex = index, app = nextApp)
    }
}

@Composable
private fun WeatherLocationSelector(
    selected: WeatherLocationMode,
    locationGranted: Boolean,
    onSelected: (WeatherLocationMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        WeatherLocationMode.entries.forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    enabled = mode != WeatherLocationMode.Current || locationGranted,
                )
                Text(mode.label)
            }
        }
    }
}

private fun loadLauncherAppOptions(context: Context): List<LauncherAppShortcut> {
    val packageManager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    return packageManager.queryIntentActivities(intent, 0)
        .mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            val displayName = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            if (displayName.isBlank()) return@mapNotNull null
            LauncherAppShortcut(displayName = displayName, packageName = packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.displayName.lowercase() }
}

private fun Context.hasPlacedWidgets(): Boolean {
    val appWidgetManager = AppWidgetManager.getInstance(this)
    val componentName = ComponentName(this, YWidgetReceiver::class.java)
    return appWidgetManager.getAppWidgetIds(componentName).isNotEmpty()
}

private fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun DisplayStyleMenu(
    selected: DisplayStyle,
    onSelected: (DisplayStyle) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) {
        Text(selected.label)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DisplayStyle.entries.forEach { style ->
            DropdownMenuItem(
                text = { Text(style.label) },
                onClick = {
                    expanded = false
                    onSelected(style)
                },
            )
        }
    }
}

@Composable
private fun CountMenu(
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) {
        Text("${selected}件")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        (3..8).forEach { count ->
            DropdownMenuItem(
                text = { Text("${count}件") },
                onClick = {
                    expanded = false
                    onSelected(count)
                },
            )
        }
    }
}

@Composable
private fun IntervalMenu(
    selected: Long,
    onSelected: (Long) -> Unit,
) {
    val intervals = listOf(
        10L to "10分",
        15L to "15分",
        30L to "30分",
        60L to "1時間",
    )
    val selectedLabel = intervals.firstOrNull { it.first == selected }?.second ?: "1時間"
    var expanded by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { expanded = true }) {
        Text(selectedLabel)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        intervals.forEach { (minutes, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    expanded = false
                    onSelected(minutes)
                },
            )
        }
    }
}
