package com.coni.hyperisle.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.DEFAULT_CONFIG_IDS
import com.coni.hyperisle.models.DEFAULT_QUICK_ACTION_IDS
import com.coni.hyperisle.util.HiLog
import kotlinx.coroutines.launch



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCustomizationScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    val quickActionOrder by preferences.settingsQuickActionsOrderFlow.collectAsState(initial = DEFAULT_QUICK_ACTION_IDS)
    val configOrder by preferences.settingsConfigOrderFlow.collectAsState(initial = DEFAULT_CONFIG_IDS)

    val quickSpecs = settingsQuickActionSpecs()
    val quickSpecMap = quickSpecs.associateBy { it.id }
    val activeQuickActions = quickActionOrder.filter { quickSpecMap.containsKey(it) }
    val inactiveQuickActions = quickSpecs.filter { it.id !in activeQuickActions }
    val quickDisplay = activeQuickActions.mapNotNull { quickSpecMap[it] } + inactiveQuickActions

    val configSpecs = settingsConfigSpecs()
    val configSpecMap = configSpecs.associateBy { it.id }
    val configDisplay = configOrder.mapNotNull { configSpecMap[it] }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_customize_screen_title)) },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_customize_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.settings_customize_screen_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SettingsCustomizationSection(
                title = stringResource(R.string.settings_customize_quick_actions_title),
                description = stringResource(R.string.settings_customize_quick_actions_desc)
            ) {
                quickDisplay.forEachIndexed { index, item ->
                    val isEnabled = activeQuickActions.contains(item.id)
                    val currentIndex = activeQuickActions.indexOf(item.id)
                    val canMoveUp = isEnabled && currentIndex > 0
                    val canMoveDown = isEnabled && currentIndex >= 0 && currentIndex < activeQuickActions.lastIndex

                    SettingsReorderRow(
                        icon = item.icon,
                        title = item.title,
                        subtitle = item.subtitle,
                        enabled = isEnabled,
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown,
                        onMoveUp = {
                            val newOrder = moveItem(activeQuickActions, currentIndex, currentIndex - 1)
                            scope.launch {
                                preferences.setSettingsQuickActionsOrder(newOrder)
                                HiLog.d(HiLog.TAG_PREF, "SETTINGS_LAYOUT_MOVE", mapOf("section" to "quick_actions", "id" to item.id, "direction" to "up"))
                            }
                        },
                        onMoveDown = {
                            val newOrder = moveItem(activeQuickActions, currentIndex, currentIndex + 1)
                            scope.launch {
                                preferences.setSettingsQuickActionsOrder(newOrder)
                                HiLog.d(HiLog.TAG_PREF, "SETTINGS_LAYOUT_MOVE", mapOf("section" to "quick_actions", "id" to item.id, "direction" to "down"))
                            }
                        },
                        trailing = {
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { checked ->
                                    val newOrder = if (checked) activeQuickActions + item.id else activeQuickActions - item.id
                                    scope.launch {
                                        preferences.setSettingsQuickActionsOrder(newOrder)
                                        HiLog.d(HiLog.TAG_PREF, "SETTINGS_LAYOUT_TOGGLE", mapOf("section" to "quick_actions", "id" to item.id, "enabled" to checked))
                                    }
                                }
                            )
                        }
                    )
                    if (index < quickDisplay.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))
                    }
                }
            }

            SettingsCustomizationSection(
                title = stringResource(R.string.settings_customize_config_title),
                description = stringResource(R.string.settings_customize_config_desc)
            ) {
                configDisplay.forEachIndexed { index, item ->
                    val canMoveUp = index > 0
                    val canMoveDown = index < configDisplay.lastIndex
                    SettingsReorderRow(
                        icon = item.icon,
                        title = item.title,
                        subtitle = item.subtitle,
                        enabled = true,
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown,
                        onMoveUp = {
                            val newOrder = moveItem(configOrder, index, index - 1)
                            scope.launch {
                                preferences.setSettingsConfigOrder(newOrder)
                                HiLog.d(HiLog.TAG_PREF, "SETTINGS_LAYOUT_MOVE", mapOf("section" to "config", "id" to item.id, "direction" to "up"))
                            }
                        },
                        onMoveDown = {
                            val newOrder = moveItem(configOrder, index, index + 1)
                            scope.launch {
                                preferences.setSettingsConfigOrder(newOrder)
                                HiLog.d(HiLog.TAG_PREF, "SETTINGS_LAYOUT_MOVE", mapOf("section" to "config", "id" to item.id, "direction" to "down"))
                            }
                        },
                        trailing = null
                    )
                    if (index < configDisplay.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCustomizationSection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsReorderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    trailing: (@Composable () -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.14f else 0.06f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalIconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.cd_move_up))
            }
            FilledTonalIconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_move_down))
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(6.dp))
                trailing()
            }
        }
    }
}

private fun moveItem(list: List<String>, fromIndex: Int, toIndex: Int): List<String> {
    if (fromIndex !in list.indices || toIndex !in list.indices) return list
    val mutable = list.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable.toList()
}
