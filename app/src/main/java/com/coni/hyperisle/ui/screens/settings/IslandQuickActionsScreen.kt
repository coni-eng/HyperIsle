package com.coni.hyperisle.ui.screens.settings

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.SmartPriorityProfile
import com.coni.hyperisle.util.IslandCooldownManager
import com.coni.hyperisle.util.PriorityEngine
import kotlinx.coroutines.launch

/**
 * Quick Actions screen for island long-press behavior.
 * Allows users to mute or block islands from a specific app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IslandQuickActionsScreen(
    packageName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }

    // App info
    val appName = remember(packageName) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    val appIcon: Drawable? = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    // State
    val isMuted by preferences.isAppMuted(packageName).collectAsState(initial = false)
    val isBlocked by preferences.isAppBlocked(packageName).collectAsState(initial = false)
    var isThrottled by remember { mutableStateOf(false) }
    val smartPriorityProfile by preferences.getSmartPriorityProfileFlow(packageName)
        .collectAsState(initial = SmartPriorityProfile.NORMAL)

    // Check throttle status on launch
    LaunchedEffect(packageName) {
        isThrottled = PriorityEngine.isAppThrottled(preferences, packageName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quick_actions_title)) },
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
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Header
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    appIcon?.let { icon ->
                        Image(
                            bitmap = icon.toBitmap(96, 96).asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_app_icon, appName),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Column {
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Mute Option
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.VolumeOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                stringResource(R.string.quick_actions_mute_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.quick_actions_mute_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isMuted,
                        onCheckedChange = { checked ->
                            scope.launch {
                                if (checked) {
                                    preferences.muteApp(packageName)
                                    // v0.9.2: Record mute as strong negative learning signal
                                    PriorityEngine.recordMuteBlock(preferences, packageName)
                                } else {
                                    preferences.unmuteApp(packageName)
                                    // Clear cooldowns when unmuting
                                    IslandCooldownManager.clearAllCooldownsForPackage(packageName)
                                }
                            }
                        }
                    )
                }
            }

            // Block Option
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                stringResource(R.string.quick_actions_block_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.quick_actions_block_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isBlocked,
                        onCheckedChange = { checked ->
                            scope.launch {
                                if (checked) {
                                    preferences.blockAppIslands(packageName)
                                    // v0.9.2: Record block as strong negative learning signal
                                    PriorityEngine.recordMuteBlock(preferences, packageName)
                                } else {
                                    preferences.unblockAppIslands(packageName)
                                }
                            }
                        }
                    )
                }
            }

            // Auto-throttle Option
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Column {
                            Text(
                                stringResource(R.string.quick_actions_throttle_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.quick_actions_throttle_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isThrottled,
                        onCheckedChange = { checked ->
                            scope.launch {
                                if (checked) {
                                    PriorityEngine.manualThrottle(preferences, packageName)
                                } else {
                                    PriorityEngine.clearThrottle(preferences, packageName)
                                }
                                isThrottled = checked
                            }
                        }
                    )
                }
            }

            // Smart Priority Profile Selector
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Column {
                            Text(
                                stringResource(R.string.smart_priority_profile_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.smart_priority_profile_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Profile selector chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SmartPriorityProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = smartPriorityProfile == profile,
                                onClick = {
                                    scope.launch {
                                        preferences.setSmartPriorityProfile(packageName, profile)
                                    }
                                },
                                label = {
                                    Text(
                                        when (profile) {
                                            SmartPriorityProfile.NORMAL -> stringResource(R.string.profile_normal)
                                            SmartPriorityProfile.LENIENT -> stringResource(R.string.profile_lenient)
                                            SmartPriorityProfile.STRICT -> stringResource(R.string.profile_strict)
                                        }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Info text
            Text(
                text = stringResource(R.string.quick_actions_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
