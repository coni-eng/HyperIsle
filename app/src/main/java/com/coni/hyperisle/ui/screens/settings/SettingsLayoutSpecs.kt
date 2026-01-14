package com.coni.hyperisle.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.coni.hyperisle.R
import com.coni.hyperisle.models.SettingsLayoutIds



data class SettingsEntrySpec(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)

@Composable
fun settingsQuickActionSpecs(): List<SettingsEntrySpec> = listOf(
    SettingsEntrySpec(
        id = SettingsLayoutIds.SETUP,
        icon = Icons.Default.SettingsSuggest,
        title = stringResource(R.string.system_setup),
        subtitle = stringResource(R.string.system_setup_subtitle)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.SMART,
        icon = Icons.Default.AutoAwesome,
        title = stringResource(R.string.smart_features_title),
        subtitle = stringResource(R.string.smart_features_desc)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.NOTIFICATION,
        icon = Icons.Default.NotificationsOff,
        title = stringResource(R.string.notification_management_title),
        subtitle = stringResource(R.string.notification_management_desc)
    )
)

@Composable
fun settingsConfigSpecs(): List<SettingsEntrySpec> = listOf(
    SettingsEntrySpec(
        id = SettingsLayoutIds.SETUP,
        icon = Icons.Default.SettingsSuggest,
        title = stringResource(R.string.system_setup),
        subtitle = stringResource(R.string.system_setup_subtitle)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.BEHAVIOR,
        icon = Icons.Default.Tune,
        title = stringResource(R.string.island_behavior),
        subtitle = stringResource(R.string.limit_strategy)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.GLOBAL,
        icon = Icons.Default.Palette,
        title = stringResource(R.string.global_settings),
        subtitle = stringResource(R.string.island_appearance)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.BLOCKLIST,
        icon = Icons.Default.Block,
        title = stringResource(R.string.blocked_terms),
        subtitle = stringResource(R.string.spoiler_subtitle)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.BACKUP,
        icon = Icons.Default.Save,
        title = stringResource(R.string.backup_restore_title),
        subtitle = stringResource(R.string.backup_section_title)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.MUSIC,
        icon = Icons.Default.MusicNote,
        title = stringResource(R.string.music_island_title),
        subtitle = stringResource(R.string.music_island_desc)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.SMART,
        icon = Icons.Default.AutoAwesome,
        title = stringResource(R.string.smart_features_title),
        subtitle = stringResource(R.string.smart_features_desc)
    ),
    SettingsEntrySpec(
        id = SettingsLayoutIds.NOTIFICATION,
        icon = Icons.Default.NotificationsOff,
        title = stringResource(R.string.notification_management_title),
        subtitle = stringResource(R.string.notification_management_desc)
    )
)
