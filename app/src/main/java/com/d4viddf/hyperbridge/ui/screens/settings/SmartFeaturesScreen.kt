package com.d4viddf.hyperbridge.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BedtimeOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.models.NotificationType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartFeaturesScreen(
    onBack: () -> Unit,
    onSummaryListClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }

    // Smart Silence
    val smartSilenceEnabled by preferences.smartSilenceEnabledFlow.collectAsState(initial = true)
    val smartSilenceWindowMs by preferences.smartSilenceWindowMsFlow.collectAsState(initial = 10000L)

    // Focus Mode
    val focusEnabled by preferences.focusEnabledFlow.collectAsState(initial = false)
    val focusQuietStart by preferences.focusQuietStartFlow.collectAsState(initial = "00:00")
    val focusQuietEnd by preferences.focusQuietEndFlow.collectAsState(initial = "08:00")
    val focusAllowedTypes by preferences.focusAllowedTypesFlow.collectAsState(initial = setOf("CALL", "TIMER"))

    // Summary
    val summaryEnabled by preferences.summaryEnabledFlow.collectAsState(initial = false)
    val summaryHour by preferences.summaryHourFlow.collectAsState(initial = 21)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.smart_features_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- SMART SILENCE ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.smart_silence_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.smart_silence_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = smartSilenceEnabled,
                            onCheckedChange = { scope.launch { preferences.setSmartSilenceEnabled(it) } }
                        )
                    }

                    if (smartSilenceEnabled) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.smart_silence_window, smartSilenceWindowMs / 1000),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = (smartSilenceWindowMs / 1000f),
                            onValueChange = { scope.launch { preferences.setSmartSilenceWindowMs((it * 1000).toLong()) } },
                            valueRange = 3f..30f,
                            steps = 26
                        )
                    }
                }
            }

            // --- FOCUS MODE ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.BedtimeOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    stringResource(R.string.focus_mode_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    stringResource(R.string.focus_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = focusEnabled,
                            onCheckedChange = { scope.launch { preferences.setFocusEnabled(it) } }
                        )
                    }

                    if (focusEnabled) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        // Quiet Hours
                        Text(
                            stringResource(R.string.focus_quiet_hours),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            OutlinedTextField(
                                value = focusQuietStart,
                                onValueChange = { scope.launch { preferences.setFocusQuietStart(it) } },
                                label = { Text(stringResource(R.string.focus_start)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = focusQuietEnd,
                                onValueChange = { scope.launch { preferences.setFocusQuietEnd(it) } },
                                label = { Text(stringResource(R.string.focus_end)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Allowed Types
                        Text(
                            stringResource(R.string.focus_allowed_types),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(8.dp))

                        val allTypes = listOf(
                            NotificationType.CALL to stringResource(R.string.type_call),
                            NotificationType.TIMER to stringResource(R.string.type_timer),
                            NotificationType.NAVIGATION to stringResource(R.string.type_nav)
                        )

                        allTypes.forEach { (type, label) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Checkbox(
                                    checked = focusAllowedTypes.contains(type.name),
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            val newSet = if (checked) {
                                                focusAllowedTypes + type.name
                                            } else {
                                                focusAllowedTypes - type.name
                                            }
                                            preferences.setFocusAllowedTypes(newSet)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // --- NOTIFICATION SUMMARY ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    stringResource(R.string.summary_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    stringResource(R.string.summary_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = summaryEnabled,
                            onCheckedChange = { scope.launch { preferences.setSummaryEnabled(it) } }
                        )
                    }

                    if (summaryEnabled) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.summary_time),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Text(
                                String.format("%02d:00", summaryHour),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = summaryHour.toFloat(),
                            onValueChange = { scope.launch { preferences.setSummaryHour(it.toInt()) } },
                            valueRange = 0f..23f,
                            steps = 22
                        )

                        Spacer(Modifier.height(8.dp))

                        FilledTonalButton(
                            onClick = onSummaryListClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.view_summary_history))
                        }
                    }
                }
            }
        }
    }
}
