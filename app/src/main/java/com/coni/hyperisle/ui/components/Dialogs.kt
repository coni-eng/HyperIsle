package com.coni.hyperisle.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.R
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.models.NotificationType
import com.coni.hyperisle.models.ShadeCancelMode
import com.coni.hyperisle.ui.AppInfo
import com.coni.hyperisle.ui.AppListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppConfigBottomSheet(
    app: AppInfo,
    viewModel: AppListViewModel,
    onDismiss: () -> Unit,
    onNavConfigClick: () -> Unit
) {
    // Data Loading
    val typeConfig by viewModel.getAppConfig(app.packageName).collectAsState(initial = emptySet())
    val appIslandConfig by viewModel.getAppIslandConfig(app.packageName).collectAsState(initial = IslandConfig())
    val globalConfig by viewModel.globalConfigFlow.collectAsState(initial = IslandConfig(true, true, 5000L))
    val blockedTerms by viewModel.getAppBlockedTerms(app.packageName).collectAsState(initial = emptySet())

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val navEditDesc = stringResource(R.string.cd_nav_edit)
    val activeDesc = stringResource(R.string.cd_app_state_active)
    val inactiveDesc = stringResource(R.string.cd_app_state_inactive)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {

            // --- SCROLLABLE CONTENT ---
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                // HEADER
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Image(
                        bitmap = app.icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(app.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(24.dp))

                // --- CARD 1: NOTIFICATION TYPES (New Dropdown) ---
                // Count how many are active to show in subtitle
                val activeCount = NotificationType.entries.toTypedArray().count { typeConfig.contains(it.name) }
                val activeSubtitle = stringResource(R.string.active_notifications_subtitle, activeCount)

                ExpandableSettingCard(
                    title = stringResource(R.string.active_notifications_title), // "Notification Types"
                    icon = Icons.Default.Notifications,
                    subtitle = activeSubtitle
                ) {
                    NotificationTypesContent(
                        app = app,
                        typeConfig = typeConfig,
                        viewModel = viewModel,
                        onNavConfigClick = { onDismiss(); onNavConfigClick() },
                        navEditDesc = navEditDesc
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- CARD 2: APPEARANCE ---
                ExpandableSettingCard(
                    title = stringResource(R.string.island_appearance),
                    icon = Icons.Default.Palette
                ) {
                    AppearanceSettingsContent(
                        appConfig = appIslandConfig,
                        globalConfig = globalConfig,
                        onUpdate = { viewModel.updateAppIslandConfig(app.packageName, it) },
                        activeDesc = activeDesc,
                        inactiveDesc = inactiveDesc
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- CARD 3: FILTERS ---
                val blockedCount = blockedTerms.size
                val blockedBadge = if (blockedCount > 0) stringResource(R.string.blocked_terms_active_count, blockedCount) else null

                ExpandableSettingCard(
                    title = stringResource(R.string.blocked_terms),
                    icon = Icons.Default.Block,
                    subtitle = blockedBadge
                ) {
                    BlocklistEditor(
                        terms = blockedTerms,
                        onUpdate = { newSet -> viewModel.updateAppBlockedTerms(app.packageName, newSet) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- CARD 4: SHADE CANCEL (v0.9.5, enhanced v0.9.9) ---
                // Only show for messaging/social style apps (STANDARD type enabled)
                if (typeConfig.contains("STANDARD")) {
                    val shadeCancelEnabled by viewModel.isShadeCancelFlow(app.packageName).collectAsState(initial = false)
                    val shadeCancelMode by viewModel.getShadeCancelModeFlow(app.packageName).collectAsState(initial = ShadeCancelMode.SAFE)
                    
                    ShadeCancelCard(
                        enabled = shadeCancelEnabled,
                        onToggle = { viewModel.setShadeCancel(app.packageName, it) },
                        selectedMode = shadeCancelMode,
                        onModeChange = { viewModel.setShadeCancelMode(app.packageName, it) }
                    )
                }

                // Bottom Spacer for scrolling
                Spacer(modifier = Modifier.height(24.dp))
            }

            // --- FIXED FOOTER ---
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .height(50.dp)
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}

@Composable
fun NotificationTypesContent(
    app: AppInfo,
    typeConfig: Set<String>,
    viewModel: AppListViewModel,
    onNavConfigClick: () -> Unit,
    navEditDesc: String
) {
    Column {
        NotificationType.entries.forEach { type ->
            val isChecked = typeConfig.contains(type.name)
            val typeLabel = stringResource(type.labelRes)
            val switchDesc = if (isChecked) stringResource(R.string.cd_disable_type, typeLabel)
            else stringResource(R.string.cd_enable_type, typeLabel)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateAppConfig(app.packageName, type, !isChecked) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(typeLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }

                if (type == NotificationType.NAVIGATION) {
                    IconButton(
                        onClick = onNavConfigClick,
                        modifier = Modifier.semantics { contentDescription = navEditDesc }
                    ) {
                        Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Switch(
                    checked = isChecked,
                    onCheckedChange = { viewModel.updateAppConfig(app.packageName, type, it) },
                    modifier = Modifier.semantics { contentDescription = switchDesc }
                )
            }
        }
    }
}

@Composable
fun ExpandableSettingCard(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "rotation")
    val actionDesc = if (expanded) "Collapse $title" else "Expand $title"

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .semantics { contentDescription = actionDesc }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (subtitle.contains("active", true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    null,
                    modifier = Modifier.rotate(rotation)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
                    content()
                }
            }
        }
    }
}

@Composable
fun AppearanceSettingsContent(
    appConfig: IslandConfig,
    globalConfig: IslandConfig,
    onUpdate: (IslandConfig) -> Unit,
    activeDesc: String,
    inactiveDesc: String
) {
    val isUsingGlobal = appConfig.isFloat == null

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (isUsingGlobal) onUpdate(globalConfig)
                    else onUpdate(IslandConfig(null, null, null))
                }
                .padding(vertical = 8.dp)
                .semantics { stateDescription = if (isUsingGlobal) activeDesc else inactiveDesc },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isUsingGlobal, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.use_global_default), style = MaterialTheme.typography.bodyLarge)
        }

        if (!isUsingGlobal) {
            Spacer(Modifier.height(8.dp))
            IslandSettingsControl(
                config = appConfig,
                onUpdate = onUpdate
            )
        } else {
            Text(
                text = stringResource(R.string.appearance_use_defaults_desc),
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp)
            )
        }
    }
}

/**
 * v0.9.5: Shade Cancel Card
 * Per-app toggle to cancel notifications from system shade after creating island.
 * Only shown for messaging/social style apps (STANDARD type enabled).
 * 
 * v0.9.9: Enhanced with SAFE/AGGRESSIVE mode selector and info tooltip.
 */
@Composable
fun ShadeCancelCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    selectedMode: ShadeCancelMode = ShadeCancelMode.SAFE,
    onModeChange: (ShadeCancelMode) -> Unit = {}
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            // Main toggle row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(!enabled) }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.shade_cancel_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.shade_cancel_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle
                )
            }
            
            // Info tooltip link
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable { showInfoDialog = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.shade_cancel_info_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // Expanded content when enabled: SAFE/AGGRESSIVE mode selector
            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // SAFE mode option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeChange(ShadeCancelMode.SAFE) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedMode == ShadeCancelMode.SAFE,
                            onClick = { onModeChange(ShadeCancelMode.SAFE) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.shade_cancel_mode_safe),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.shade_cancel_mode_desc_safe),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                    
                    // AGGRESSIVE mode option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeChange(ShadeCancelMode.AGGRESSIVE) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedMode == ShadeCancelMode.AGGRESSIVE,
                            onClick = { onModeChange(ShadeCancelMode.AGGRESSIVE) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.shade_cancel_mode_aggressive),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.shade_cancel_mode_desc_aggressive),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Info dialog
    if (showInfoDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.shade_cancel_info_title)) },
            text = { Text(stringResource(R.string.shade_cancel_info_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }
}