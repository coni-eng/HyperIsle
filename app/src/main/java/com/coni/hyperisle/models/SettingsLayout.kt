package com.coni.hyperisle.models



object SettingsLayoutIds {
    const val SETUP = "setup"
    const val BEHAVIOR = "behavior"
    const val GLOBAL = "global"
    const val BLOCKLIST = "blocklist"
    const val BACKUP = "backup"
    const val MUSIC = "music"
    const val SMART = "smart"
    const val NOTIFICATION = "notification"
}

val DEFAULT_QUICK_ACTION_IDS = listOf(
    SettingsLayoutIds.SETUP,
    SettingsLayoutIds.SMART,
    SettingsLayoutIds.NOTIFICATION
)

val DEFAULT_CONFIG_IDS = listOf(
    SettingsLayoutIds.SETUP,
    SettingsLayoutIds.BEHAVIOR,
    SettingsLayoutIds.GLOBAL,
    SettingsLayoutIds.BLOCKLIST,
    SettingsLayoutIds.BACKUP,
    SettingsLayoutIds.MUSIC,
    SettingsLayoutIds.SMART,
    SettingsLayoutIds.NOTIFICATION
)
