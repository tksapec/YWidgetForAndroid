package com.example.yahoonewswidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.yahoonewswidget.data.NewsCategory
import com.example.yahoonewswidget.data.WidgetPreferences
import com.example.yahoonewswidget.data.WidgetSettings
import com.example.yahoonewswidget.widget.YahooNewsWidget
import com.example.yahoonewswidget.work.RefreshWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val preferences by lazy { WidgetPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            RefreshWorker.schedulePeriodic(
                this@MainActivity,
                preferences.currentSettings().updateIntervalMinutes,
            )
        }

        setContent {
            val settings by preferences.settingsFlow.collectAsStateWithLifecycle(
                initialValue = WidgetSettings(),
            )

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        settings = settings,
                        onCategorySelected = { category ->
                            lifecycleScope.launch {
                                preferences.updateCategory(category)
                                RefreshWorker.enqueueImmediate(this@MainActivity)
                            }
                        },
                        onDisplayCountSelected = { count ->
                            lifecycleScope.launch {
                                preferences.updateDisplayCount(count)
                                YahooNewsWidget().updateAll(this@MainActivity)
                            }
                        },
                        onIntervalSelected = { minutes ->
                            lifecycleScope.launch {
                                preferences.updateInterval(minutes)
                                RefreshWorker.schedulePeriodic(this@MainActivity, minutes)
                            }
                        },
                        onWeatherEnabledChanged = { enabled ->
                            lifecycleScope.launch {
                                preferences.updateWeatherEnabled(enabled)
                                if (enabled) {
                                    RefreshWorker.enqueueImmediate(this@MainActivity)
                                } else {
                                    YahooNewsWidget().updateAll(this@MainActivity)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: WidgetSettings,
    onCategorySelected: (NewsCategory) -> Unit,
    onDisplayCountSelected: (Int) -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onWeatherEnabledChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationGranted = granted
        if (granted) onWeatherEnabledChanged(true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Yahoo!\u30CB\u30E5\u30FC\u30B9 \u30A6\u30A3\u30B8\u30A7\u30C3\u30C8\u8A2D\u5B9A",
            style = MaterialTheme.typography.titleLarge,
        )

        SettingRow(label = "\u8868\u793A\u30AB\u30C6\u30B4\u30EA") {
            CategoryMenu(
                selected = settings.category,
                onSelected = onCategorySelected,
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

        SettingRow(label = "\u5929\u6C17\u8868\u793A") {
            Switch(
                checked = settings.weatherEnabled,
                onCheckedChange = onWeatherEnabledChanged,
                enabled = locationGranted,
            )
        }

        SettingRow(label = "\u4F4D\u7F6E\u60C5\u5831\u6A29\u9650") {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                Text(if (locationGranted) "\u8A31\u53EF\u6E08\u307F" else "\u8A31\u53EF\u3059\u308B")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "\u4F4D\u7F6E\u60C5\u5831\u306F\u304A\u304A\u3088\u305D\u306E\u73FE\u5728\u5730\u306E\u307F\u4F7F\u7528\u3057\u307E\u3059\u3002\u30A6\u30A3\u30B8\u30A7\u30C3\u30C8\u304B\u3089\u6A29\u9650\u8981\u6C42\u306F\u884C\u3044\u307E\u305B\u3093\u3002",
            style = MaterialTheme.typography.bodySmall,
        )
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
private fun CategoryMenu(
    selected: NewsCategory,
    onSelected: (NewsCategory) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) {
        Text(selected.label)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        NewsCategory.entries.forEach { category ->
            DropdownMenuItem(
                text = { Text(category.label) },
                onClick = {
                    expanded = false
                    onSelected(category)
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
        30L to "30\u5206",
        60L to "1\u6642\u9593",
        180L to "3\u6642\u9593",
        360L to "6\u6642\u9593",
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
