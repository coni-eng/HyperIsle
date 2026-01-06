package com.coni.hyperisle.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BedtimeOff
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.util.ActionDiagnostics
import com.coni.hyperisle.util.PriorityDiagnostics
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.ContextPreset
import com.coni.hyperisle.models.NotificationType
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

    // Haptics
    val hapticsEnabled by preferences.hapticsEnabledFlow.collectAsState(initial = true)

    // Dismiss Cooldown
    val dismissCooldownSeconds by preferences.dismissCooldownSecondsFlow.collectAsState(initial = 30)

    // Smart Priority
    val smartPriorityEnabled by preferences.smartPriorityEnabledFlow.collectAsState(initial = true)
    val smartPriorityAggressiveness by preferences.smartPriorityAggressivenessFlow.collectAsState(initial = 1)

    // System Banners
    val bannerBtEnabled by preferences.bannerBtConnectedEnabledFlow.collectAsState(initial = false)
    val bannerBatteryEnabled by preferences.bannerBatteryLowEnabledFlow.collectAsState(initial = false)
    val bannerCopiedEnabled by preferences.bannerCopiedEnabledFlow.collectAsState(initial = false)

    // Context-Aware Islands (v0.7.0)
    val contextAwareEnabled by preferences.contextAwareEnabledFlow.collectAsState(initial = false)
    val contextScreenOffOnlyImportant by preferences.contextScreenOffOnlyImportantFlow.collectAsState(initial = true)
    val contextChargingSuppressBatteryBanners by preferences.contextChargingSuppressBatteryBannersFlow.collectAsState(initial = true)

    // Context Presets (v0.9.0)
    val contextPreset by preferences.contextPresetFlow.collectAsState(initial = ContextPreset.OFF)

    // Debug Diagnostics (debug builds only)
    val actionDiagnosticsEnabled by preferences.actionDiagnosticsEnabledFlow.collectAsState(initial = false)
    val actionLongPressInfoEnabled by preferences.actionLongPressInfoEnabledFlow.collectAsState(initial = false)
    val priorityDiagnosticsEnabled by preferences.priorityDiagnosticsEnabledFlow.collectAsState(initial = false)
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val diagnosticsCopiedMessage = stringResource(R.string.debug_diagnostics_copied)
    val priorityDiagnosticsCopiedMessage = stringResource(R.string.debug_priority_diagnostics_copied)

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- SMART PRIORITY ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    SettingsHeaderRow(
                        icon = Icons.Default.Speed,
                        title = stringResource(R.string.smart_priority_title),
                        description = stringResource(R.string.smart_priority_desc),
                        checked = smartPriorityEnabled,
                        onCheckedChange = { scope.launch { preferences.setSmartPriorityEnabled(it) } }
                    )

                    AnimatedVisibility(
                        visible = smartPriorityEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            CardDivider()
                            Spacer(Modifier.height(12.dp))

                            Text(
                                stringResource(R.string.smart_priority_aggressiveness),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.height(4.dp))

                            val aggressivenessLabels = listOf(
                                stringResource(R.string.aggressiveness_low),
                                stringResource(R.string.aggressiveness_medium),
                                stringResource(R.string.aggressiveness_high)
                            )
                            Text(
                                aggressivenessLabels.getOrElse(smartPriorityAggressiveness) { aggressivenessLabels[1] },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Slider(
                                value = smartPriorityAggressiveness.toFloat(),
                                onValueChange = { scope.launch { preferences.setSmartPriorityAggressiveness(it.toInt()) } },
                                valueRange = 0f..2f,
                                steps = 1
                            )
                            Text(
                                stringResource(R.string.smart_priority_info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // --- CONTEXT-AWARE ISLANDS (v0.7.0) ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    SettingsHeaderRow(
                        icon = Icons.Default.DarkMode,
                        title = stringResource(R.string.context_aware_title),
                        description = stringResource(R.string.context_aware_desc),
                        checked = contextAwareEnabled,
                        onCheckedChange = { scope.launch { preferences.setContextAwareEnabled(it) } }
                    )

                    AnimatedVisibility(
                        visible = contextAwareEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            CardDivider()
                            Spacer(Modifier.height(12.dp))

                            // Screen off: only important islands
                            SettingsToggleRow(
                                title = stringResource(R.string.context_screen_off_only_important),
                                subtitle = stringResource(R.string.context_important_types_label),
                                checked = contextScreenOffOnlyImportant,
                                onCheckedChange = { scope.launch { preferences.setContextScreenOffOnlyImportant(it) } }
                            )

                            Spacer(Modifier.height(8.dp))

                            // Charging: suppress battery banners
                            SettingsToggleRow(
                                title = stringResource(R.string.context_charging_suppress_battery_banners),
                                checked = contextChargingSuppressBatteryBanners,
                                onCheckedChange = { scope.launch { preferences.setContextChargingSuppressBatteryBanners(it) } }
                            )
                        }
                    }
                }
            }

            // --- CONTEXT PRESETS (v0.9.0) ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.context_preset_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.context_preset_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    val presetOptions = listOf(
                        ContextPreset.OFF to stringResource(R.string.preset_off),
                        ContextPreset.MEETING to stringResource(R.string.preset_meeting),
                        ContextPreset.DRIVING to stringResource(R.string.preset_driving),
                        ContextPreset.HEADPHONES to stringResource(R.string.preset_headphones)
                    )

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        presetOptions.forEachIndexed { index, (preset, label) ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = presetOptions.size),
                                onClick = { scope.launch { preferences.setContextPreset(preset) } },
                                selected = contextPreset == preset
                            ) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val presetDescription = when (contextPreset) {
                        ContextPreset.MEETING -> stringResource(R.string.preset_meeting_desc)
                        ContextPreset.DRIVING -> stringResource(R.string.preset_driving_desc)
                        ContextPreset.HEADPHONES -> stringResource(R.string.preset_headphones_desc)
                        ContextPreset.OFF -> null
                    }

                    if (presetDescription != null) {
                        Text(
                            presetDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (contextPreset != ContextPreset.OFF) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.preset_focus_override),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

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

                    AnimatedVisibility(
                        visible = smartSilenceEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
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

                    AnimatedVisibility(
                        visible = focusEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(16.dp))
                            CardDivider()
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

                    AnimatedVisibility(
                        visible = summaryEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            CardDivider()
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
                                    String.format(java.util.Locale.US, "%02d:00", summaryHour),
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

            // --- HAPTICS ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    SettingsHeaderRow(
                        icon = Icons.Default.Vibration,
                        title = stringResource(R.string.haptics_title),
                        description = stringResource(R.string.haptics_desc),
                        checked = hapticsEnabled,
                        onCheckedChange = { scope.launch { preferences.setHapticsEnabled(it) } }
                    )
                }
            }

            // --- DISMISS COOLDOWN ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.cooldown_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.cooldown_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.cooldown_seconds, dismissCooldownSeconds),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = dismissCooldownSeconds.toFloat(),
                        onValueChange = { scope.launch { preferences.setDismissCooldownSeconds(it.toInt()) } },
                        valueRange = 0f..120f,
                        steps = 23
                    )
                }
            }

            // --- SYSTEM BANNERS ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.banners_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.banners_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.banners_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    CardDivider()
                    Spacer(Modifier.height(12.dp))

                    // Bluetooth Connected
                    SettingsToggleRow(
                        title = stringResource(R.string.banner_bt_title),
                        subtitle = stringResource(R.string.banner_bt_desc),
                        checked = bannerBtEnabled,
                        onCheckedChange = { scope.launch { preferences.setBannerBtConnectedEnabled(it) } },
                        icon = Icons.Default.Bluetooth
                    )

                    Spacer(Modifier.height(8.dp))

                    // Battery Low
                    SettingsToggleRow(
                        title = stringResource(R.string.banner_battery_title),
                        subtitle = stringResource(R.string.banner_battery_desc),
                        checked = bannerBatteryEnabled,
                        onCheckedChange = { scope.launch { preferences.setBannerBatteryLowEnabled(it) } },
                        icon = Icons.Default.BatteryAlert
                    )

                    Spacer(Modifier.height(8.dp))

                    // Copied (placeholder)
                    SettingsToggleRow(
                        title = stringResource(R.string.banner_copied_title),
                        subtitle = stringResource(R.string.banner_copied_desc),
                        checked = bannerCopiedEnabled,
                        onCheckedChange = { scope.launch { preferences.setBannerCopiedEnabled(it) } },
                        enabled = false
                    )
                }
            }

            // --- DEBUG DIAGNOSTICS (debug builds only) ---
            if (BuildConfig.DEBUG) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.debug_section_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(12.dp))

                        SettingsToggleRow(
                            title = stringResource(R.string.debug_action_diagnostics_title),
                            subtitle = stringResource(R.string.debug_action_diagnostics_desc),
                            checked = actionDiagnosticsEnabled,
                            onCheckedChange = { scope.launch { preferences.setActionDiagnosticsEnabled(it) } }
                        )

                        Spacer(Modifier.height(8.dp))

                        SettingsToggleRow(
                            title = stringResource(R.string.debug_action_long_press_info_title),
                            subtitle = stringResource(R.string.debug_action_long_press_info_desc),
                            checked = actionLongPressInfoEnabled,
                            onCheckedChange = { scope.launch { preferences.setActionLongPressInfoEnabled(it) } }
                        )

                        Spacer(Modifier.height(12.dp))

                        FilledTonalButton(
                            onClick = {
                                val summary = ActionDiagnostics.summary()
                                clipboardManager.setText(AnnotatedString(summary))
                                scope.launch {
                                    snackbarHostState.showSnackbar(diagnosticsCopiedMessage)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.debug_copy_diagnostics))
                        }

                        Spacer(Modifier.height(16.dp))
                        CardDivider()
                        Spacer(Modifier.height(12.dp))

                        SettingsToggleRow(
                            title = stringResource(R.string.debug_priority_diagnostics_title),
                            subtitle = stringResource(R.string.debug_priority_diagnostics_desc),
                            checked = priorityDiagnosticsEnabled,
                            onCheckedChange = { scope.launch { preferences.setPriorityDiagnosticsEnabled(it) } }
                        )

                        Spacer(Modifier.height(12.dp))

                        FilledTonalButton(
                            onClick = {
                                val summary = PriorityDiagnostics.summary()
                                clipboardManager.setText(AnnotatedString(summary))
                                scope.launch {
                                    snackbarHostState.showSnackbar(priorityDiagnosticsCopiedMessage)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.debug_copy_priority_diagnostics))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )
}

@Composable
private fun SettingsHeaderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
