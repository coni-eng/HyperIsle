package com.coni.hyperisle.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.models.AnchorVisibilityMode
import com.coni.hyperisle.ui.components.IslandSettingsControl
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(
    onBack: () -> Unit,
    onNavSettingsClick: () -> Unit // New Callback
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }

    val globalConfig by preferences.globalConfigFlow.collectAsState(initial = IslandConfig(true, true, 5000L))
    val anchorMode by preferences.anchorModeFlow.collectAsState(initial = AnchorVisibilityMode.TRIGGERED_ONLY)
    var showAnchorDialog by remember { mutableStateOf(false) }

    if (showAnchorDialog) {
        AlertDialog(
            onDismissRequest = { showAnchorDialog = false },
            title = { Text(stringResource(R.string.anchor_mode_title)) },
            text = {
                Column {
                    AnchorVisibilityMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { preferences.setAnchorMode(mode) }
                                    showAnchorDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (mode == anchorMode),
                                onClick = null // Handled by Row
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = AnchorVisibilityMode.getDisplayName(mode),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = AnchorVisibilityMode.getDescription(mode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAnchorDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.global_settings)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(Modifier.padding(16.dp)) {
                    IslandSettingsControl(
                        config = globalConfig,
                        onUpdate = { newConfig ->
                            scope.launch { preferences.updateGlobalConfig(newConfig) }
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Anchor Mode Card
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                SettingsItem(
                    icon = Icons.Default.Anchor,
                    title = stringResource(R.string.anchor_mode_title),
                    subtitle = AnchorVisibilityMode.getDisplayName(anchorMode),
                    onClick = { showAnchorDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // NEW: Navigation Layout Card
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                SettingsItem(
                    icon = Icons.Default.Navigation,
                    title = stringResource(R.string.nav_layout_title),
                    subtitle = stringResource(R.string.nav_layout_desc),
                    onClick = onNavSettingsClick
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Show Time on Islands Toggle (v0.9.4)
            val showTimeOnIslands by preferences.showTimeOnIslandsFlow.collectAsState(initial = false)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                stringResource(R.string.show_time_on_islands_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.show_time_on_islands_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = showTimeOnIslands,
                        onCheckedChange = { checked ->
                            scope.launch { preferences.setShowTimeOnIslands(checked) }
                        }
                    )
                }
            }
        }
    }
}
