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
import com.tksapec.ywidget.work.RefreshWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val preferences by lazy { WidgetPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                                    safeUpdateAll(this@MainActivity)
                                } else {
                                    enqueueImmediateRefresh()
                                }
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
                    )
                }
            }
        }
    }

    private suspend fun enqueueImmediateRefresh() {
        try {
            preferences.updateRefreshQueued(true)
            safeUpdateAll(this)
            RefreshWorker.enqueueImmediate(this)
        } catch (_: Throwable) {
            preferences.clearRefreshState()
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
    onFixedLocationSaved: (String) -> Unit,
    onLauncherAppSlotsChanged: (List<LauncherAppSlot>) -> Unit,
    onRefreshStateReset: () -> Unit,
) {
    val context = LocalContext.current
    val launcherAppOptions = remember(context) { loadLauncherAppOptions(context) }
    var fixedLocationInput by remember(settings.fixedLocationQuery) {
        mutableStateOf(settings.fixedLocationQuery)
    }
    var locationGranted by remember {
        mutableStateOf(context.hasLocationPermission())
    }
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
            text = "YWidget \u8A2D\u5B9A",
            style = MaterialTheme.typography.titleLarge,
        )

        SettingBlock(label = "\u8868\u793A\u30AB\u30C6\u30B4\u30EA") {
            CategorySelector(
                selected = settings.selectedCategories,
                onChanged = onCategoriesChanged,
            )
        }

        SettingRow(label = "\u8868\u793A\u30B9\u30BF\u30A4\u30EB") {
            DisplayStyleMenu(
                selected = settings.displayStyle,
                onSelected = onDisplayStyleSelected,
            )
        }

        SettingRow(label = "\u8868\u793A\u4EF6\u6570") {
            CountMenu(
                selected = settings.displayCount,
                onSelected = onDisplayCountSelected,
            )
        }

        SettingRow(label = "\u66F4\u65B0\u9593\u9694") {
            IntervalMenu(
                selected = settings.updateIntervalMinutes,
                onSelected = onIntervalSelected,
            )
        }

        SettingBlock(label = "\u30E9\u30F3\u30C1\u30E3\u30FC\u30DC\u30BF\u30F3") {
            LauncherAppSelector(
                selectedSlots = settings.launcherAppSlots,
                availableApps = launcherAppOptions,
                onChanged = onLauncherAppSlotsChanged,
            )
        }

        SettingBlock(label = "\u5929\u6C17\u5730\u57DF") {
            WeatherLocationSelector(
                selected = settings.weatherLocationMode,
                locationGranted = locationGranted,
                onSelected = onWeatherLocationModeSelected,
            )
        }

        SettingRow(label = "\u4F4D\u7F6E\u60C5\u5831\u6A29\u9650") {
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
                Text(if (locationGranted) "\u8A31\u53EF\u6E08\u307F" else "\u8A31\u53EF\u3059\u308B")
            }
        }

        SettingBlock(label = "\u56FA\u5B9A\u5730\u57DF") {
            OutlinedTextField(
                value = fixedLocationInput,
                onValueChange = { fixedLocationInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("\u4F8B: \u6771\u4EAC\u90FD\u65B0\u5BBF\u533A") },
            )
            Button(
                onClick = { onFixedLocationSaved(fixedLocationInput) },
                enabled = fixedLocationInput.isNotBlank(),
            ) {
                Text("\u4FDD\u5B58\u3057\u3066\u66F4\u65B0")
            }
            settings.locationLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = "\u8868\u793A\u4E2D: $label",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            settings.lastWeatherError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = "\u5929\u6C17\u66F4\u65B0\u30A8\u30E9\u30FC: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "\u66F4\u65B0\u9593\u9694\u306E10\u5206\u306F\u8A2D\u5B9A\u3068\u3057\u3066\u4FDD\u5B58\u3057\u307E\u3059\u304C\u3001Android\u306E\u5236\u7D04\u306B\u3088\u308A\u5B9A\u671F\u5B9F\u884C\u306F15\u5206\u4EE5\u4E0A\u3067\u767B\u9332\u3055\u308C\u307E\u3059\u3002",
            style = MaterialTheme.typography.bodySmall,
        )

        SettingRow(label = "\u66F4\u65B0\u72B6\u614B") {
            Button(onClick = onRefreshStateReset) {
                Text("\u66F4\u65B0\u72B6\u614B\u3092\u30EA\u30BB\u30C3\u30C8")
            }
        }
    }
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
            text = "\u8D77\u52D5\u53EF\u80FD\u306A\u30A2\u30D7\u30EA\u304C\u898B\u3064\u304B\u308A\u307E\u305B\u3093",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val installedPackageNames = availableApps.map { it.packageName }.toSet()
    repeat(3) { slotIndex ->
        val selected = selectedSlots.firstOrNull { it.slotIndex == slotIndex }?.app
        val selectedInstalled = selected == null || selected.packageName in installedPackageNames
        SettingRow(label = "\u30B9\u30ED\u30C3\u30C8${slotIndex + 1}") {
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
                    Text("\u89E3\u9664")
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
                selected == null -> "\u672A\u767B\u9332"
                selectedInstalled -> selected.displayName
                else -> "\u672A\u30A4\u30F3\u30B9\u30C8\u30FC\u30EB: ${selected.displayName}"
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
        Text("${selected}\u4EF6")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        (3..8).forEach { count ->
            DropdownMenuItem(
                text = { Text("${count}\u4EF6") },
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
        10L to "10\u5206",
        15L to "15\u5206",
        30L to "30\u5206",
        60L to "1\u6642\u9593",
    )
    val selectedLabel = intervals.firstOrNull { it.first == selected }?.second ?: "1\u6642\u9593"
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
