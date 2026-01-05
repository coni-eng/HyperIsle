package com.d4viddf.hyperbridge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)

object SettingsKeys {
    // Migration Flag
    const val MIGRATION_COMPLETE = "migration_to_room_complete"

    // Core Keys
    const val SETUP_COMPLETE = "setup_complete"
    const val LAST_VERSION = "last_version_code"
    const val PRIORITY_EDU = "priority_edu_shown"
    const val ALLOWED_PACKAGES = "allowed_packages"
    const val PRIORITY_ORDER = "priority_app_order"

    // Global Configs
    const val GLOBAL_FLOAT = "global_float"
    const val GLOBAL_SHADE = "global_shade"
    const val GLOBAL_TIMEOUT = "global_timeout"
    const val GLOBAL_BLOCKED_TERMS = "global_blocked_terms"

    // Nav
    const val NAV_LEFT = "nav_left_content"
    const val NAV_RIGHT = "nav_right_content"

    // Music Island
    const val MUSIC_ISLAND_MODE = "music_island_mode"
    const val MUSIC_BLOCK_APPS = "music_block_apps"
    const val MUSIC_BLOCK_WARNING_SHOWN = "music_block_warning_shown"

    // System State Islands
    const val SYSTEM_STATE_ISLANDS_ENABLED = "system_state_islands_enabled"
    const val SYSTEM_STATE_INFO_SHOWN = "system_state_info_shown"

    // Smart Silence (Anti-Spam)
    const val SMART_SILENCE_ENABLED = "smart_silence_enabled"
    const val SMART_SILENCE_WINDOW_MS = "smart_silence_window_ms"

    // Focus Automation
    const val FOCUS_ENABLED = "focus_enabled"
    const val FOCUS_QUIET_START = "focus_quiet_start"
    const val FOCUS_QUIET_END = "focus_quiet_end"
    const val FOCUS_ALLOWED_TYPES = "focus_allowed_types"

    // Notification Summary
    const val SUMMARY_ENABLED = "summary_enabled"
    const val SUMMARY_HOUR = "summary_hour"
}