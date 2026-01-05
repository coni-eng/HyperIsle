package com.d4viddf.hyperbridge.ui.screens.settings

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
            // --- SMART PRIORITY ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    stringResource(R.string.smart_priority_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    stringResource(R.string.smart_priority_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = smartPriorityEnabled,
                            onCheckedChange = { scope.launch { preferences.setSmartPriorityEnabled(it) } }
                        )
                    }

                    AnimatedVisibility(
                        visible = smartPriorityEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.DarkMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    stringResource(R.string.context_aware_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    stringResource(R.string.context_aware_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = contextAwareEnabled,
                            onCheckedChange = { scope.launch { preferences.setContextAwareEnabled(it) } }
                        )
                    }

                    AnimatedVisibility(
                        visible = contextAwareEnabled,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // Screen off: only important islands
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.context_screen_off_only_important),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        stringResource(R.string.context_important_types_label),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = contextScreenOffOnlyImportant,
                                    onCheckedChange = { scope.launch { preferences.setContextScreenOffOnlyImportant(it) } }
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // Charging: suppress battery banners
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.context_charging_suppress_battery_banners),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = contextChargingSuppressBatteryBanners,
                                    onCheckedChange = { scope.launch { preferences.setContextChargingSuppressBatteryBanners(it) } }
                                )
                            }
                        }
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

            // --- HAPTICS ---
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Vibration,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.haptics_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.haptics_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
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
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Bluetooth Connected
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    stringResource(R.string.banner_bt_title),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.banner_bt_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = bannerBtEnabled,
                            onCheckedChange = { scope.launch { preferences.setBannerBtConnectedEnabled(it) } }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Battery Low
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.BatteryAlert,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    stringResource(R.string.banner_battery_title),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    stringResource(R.string.banner_battery_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = bannerBatteryEnabled,
                            onCheckedChange = { scope.launch { preferences.setBannerBatteryLowEnabled(it) } }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Copied (placeholder)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.banner_copied_title),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(R.string.banner_copied_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bannerCopiedEnabled,
                            onCheckedChange = { scope.launch { preferences.setBannerCopiedEnabled(it) } },
                            enabled = false // Not implemented yet
                        )
                    }
                }
            }
        }
    }
}
