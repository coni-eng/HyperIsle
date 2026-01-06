package com.coni.hyperisle.ui.screens.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.MusicIslandMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicIslandSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }

    val currentMode by preferences.musicIslandModeFlow.collectAsState(initial = MusicIslandMode.SYSTEM_ONLY)
    val blockedApps by preferences.musicBlockAppsFlow.collectAsState(initial = emptySet())
    val warningShown by preferences.musicBlockWarningShownFlow.collectAsState(initial = false)

    var showWarningDialog by remember { mutableStateOf(false) }
    var showAppSelectionDialog by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<MusicIslandMode?>(null) }

    // Known music app packages
    val knownMusicApps = remember {
        listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "com.amazon.mp3",
            "com.soundcloud.android",
            "deezer.android.app",
            "com.pandora.android",
            "com.aspiro.tidal",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.sonyericsson.music",
            "com.samsung.android.app.music",
            "com.miui.player"
        )
    }

    // Get installed music apps
    val installedMusicApps = remember {
        val pm = context.packageManager
        knownMusicApps.mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                pkg to label
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.music_island_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // Mode Selection Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.music_island_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))

                        // SYSTEM_ONLY option
                        ModeOption(
                            title = stringResource(R.string.music_mode_system_only),
                            description = stringResource(R.string.music_mode_system_only_desc),
                            selected = currentMode == MusicIslandMode.SYSTEM_ONLY,
                            onClick = {
                                scope.launch {
                                    preferences.setMusicIslandMode(MusicIslandMode.SYSTEM_ONLY)
                                }
                            }
                        )

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        // BLOCK_SYSTEM option
                        ModeOption(
                            title = stringResource(R.string.music_mode_block_system),
                            description = stringResource(R.string.music_mode_block_system_desc),
                            selected = currentMode == MusicIslandMode.BLOCK_SYSTEM,
                            onClick = {
                                if (blockedApps.isEmpty()) {
                                    pendingMode = MusicIslandMode.BLOCK_SYSTEM
                                    showWarningDialog = true
                                } else {
                                    scope.launch {
                                        preferences.setMusicIslandMode(MusicIslandMode.BLOCK_SYSTEM)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Warning Card (always visible when BLOCK_SYSTEM is selected)
            if (currentMode == MusicIslandMode.BLOCK_SYSTEM) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.music_block_warning_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.music_block_warning_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // App Selection Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.music_select_apps),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.music_select_apps_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))

                            if (installedMusicApps.isEmpty()) {
                                Text(
                                    text = "No known music apps installed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                installedMusicApps.forEach { (pkg, label) ->
                                    val isSelected = blockedApps.contains(pkg)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch {
                                                    val newSet = if (isSelected) {
                                                        blockedApps - pkg
                                                    } else {
                                                        blockedApps + pkg
                                                    }
                                                    preferences.setMusicBlockApps(newSet)
                                                }
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                scope.launch {
                                                    val newSet = if (checked) {
                                                        blockedApps + pkg
                                                    } else {
                                                        blockedApps - pkg
                                                    }
                                                    preferences.setMusicBlockApps(newSet)
                                                }
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Info Card for SYSTEM_ONLY mode
            if (currentMode == MusicIslandMode.SYSTEM_ONLY) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.music_system_only_info),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Warning Dialog
    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.music_block_warning_title)) },
            text = { Text(stringResource(R.string.music_block_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWarningDialog = false
                        pendingMode?.let { mode ->
                            scope.launch {
                                preferences.setMusicIslandMode(mode)
                                preferences.setMusicBlockWarningShown(true)
                            }
                        }
                        pendingMode = null
                    }
                ) {
                    Text(stringResource(R.string.done))
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showWarningDialog = false
                    pendingMode = null
                }) {
                    Text(stringResource(R.string.back))
                }
            }
        )
    }
}

@Composable
private fun ModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
