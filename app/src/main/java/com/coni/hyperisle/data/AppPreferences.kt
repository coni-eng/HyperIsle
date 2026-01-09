package com.coni.hyperisle.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.coni.hyperisle.data.db.AppDatabase
import com.coni.hyperisle.data.db.AppSetting
import com.coni.hyperisle.data.db.SettingsKeys
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.models.IslandLimitMode
import com.coni.hyperisle.models.MusicIslandMode
import com.coni.hyperisle.models.NavContent
import com.coni.hyperisle.models.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// We keep this ONLY for the one-time migration logic
private val Context.legacyDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppPreferences(context: Context) {

    companion object {
        // v0.9.3: Hard cap for all learning counters to prevent runaway behavior
        private const val LEARNING_COUNTER_CAP = 100
        // v0.9.3: Decay percentage (10% reduction per day)
        private const val LEARNING_DECAY_PERCENT = 0.10f
        // v0.9.3: Minimum interval between decay runs (24 hours in ms)
        private const val DECAY_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }

    private val dao = AppDatabase.getDatabase(context).settingsDao()
    private val legacyDataStore = context.applicationContext.legacyDataStore

    init {
        // --- MIGRATION LOGIC ---
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if migration already happened
                val isMigrated = dao.getSetting(SettingsKeys.MIGRATION_COMPLETE) == "true"

                if (!isMigrated) {
                    val legacyPrefs = legacyDataStore.data.first().asMap()

                    if (legacyPrefs.isNotEmpty()) {
                        legacyPrefs.forEach { (key, value) ->
                            val strValue = when (value) {
                                is Set<*> -> value.joinToString(",") // Handle string sets
                                else -> value.toString() // Handle Booleans, Longs, Ints
                            }
                            dao.insert(AppSetting(key.name, strValue))
                        }
                        // Clear legacy file to save space and avoid confusion
                        legacyDataStore.edit { it.clear() }
                    }

                    // Mark as done so we don't run this again
                    dao.insert(AppSetting(SettingsKeys.MIGRATION_COMPLETE, "true"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- HELPERS FOR ROOM ---
    private fun String?.toBoolean(default: Boolean = false): Boolean = this?.toBooleanStrictOrNull() ?: default
    private fun String?.toInt(default: Int = 0): Int = this?.toIntOrNull() ?: default
    private fun String?.toLong(default: Long = 0L): Long = this?.toLongOrNull() ?: default

    private fun Set<String>.serialize(): String = this.joinToString(",")
    private fun String?.deserializeSet(): Set<String> = this?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    private fun String?.deserializeList(): List<String> = this?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    private suspend fun save(key: String, value: String) {
        dao.insert(AppSetting(key, value))
    }

    private suspend fun remove(key: String) {
        dao.delete(key)
    }

    // --- CORE ---
    val allowedPackagesFlow: Flow<Set<String>> = dao.getSettingFlow(SettingsKeys.ALLOWED_PACKAGES).map { it.deserializeSet() }

    val isSetupComplete: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.SETUP_COMPLETE).map { it.toBoolean(false) }

    val lastSeenVersion: Flow<Int> = dao.getSettingFlow(SettingsKeys.LAST_VERSION).map { it.toInt(0) }
    val isPriorityEduShown: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.PRIORITY_EDU).map { it.toBoolean(false) }

    suspend fun setSetupComplete(isComplete: Boolean) = save(SettingsKeys.SETUP_COMPLETE, isComplete.toString())
    suspend fun setLastSeenVersion(versionCode: Int) = save(SettingsKeys.LAST_VERSION, versionCode.toString())
    suspend fun setPriorityEduShown(shown: Boolean) = save(SettingsKeys.PRIORITY_EDU, shown.toString())

    suspend fun toggleApp(packageName: String, isEnabled: Boolean) {
        val currentString = dao.getSetting(SettingsKeys.ALLOWED_PACKAGES)       
        val currentSet = currentString.deserializeSet()
        val newSet = if (isEnabled) currentSet + packageName else currentSet - packageName
        save(SettingsKeys.ALLOWED_PACKAGES, newSet.serialize())
    }

    suspend fun addAllowedPackages(packageNames: Collection<String>) {
        if (packageNames.isEmpty()) return
        val currentString = dao.getSetting(SettingsKeys.ALLOWED_PACKAGES)
        val currentSet = currentString.deserializeSet()
        val newSet = currentSet + packageNames
        save(SettingsKeys.ALLOWED_PACKAGES, newSet.serialize())
    }

    // --- LIMITS ---
    val limitModeFlow: Flow<IslandLimitMode> = dao.getSettingFlow("limit_mode").map {
        try { IslandLimitMode.valueOf(it ?: IslandLimitMode.MOST_RECENT.name) } catch(e: Exception) { IslandLimitMode.MOST_RECENT }
    }
    val appPriorityListFlow: Flow<List<String>> = dao.getSettingFlow(SettingsKeys.PRIORITY_ORDER).map { it.deserializeList() }

    suspend fun setLimitMode(mode: IslandLimitMode) = save("limit_mode", mode.name)
    suspend fun setAppPriorityOrder(order: List<String>) = save(SettingsKeys.PRIORITY_ORDER, order.joinToString(","))

    // --- TYPE CONFIG ---
    fun getAppConfig(packageName: String): Flow<Set<String>> {
        val key = "config_${packageName}_types" // Mapped from old "config_pkg" logic
        // Check if migration might have saved it as just "config_pkg" (without _types)
        // The migration logic used key.name, so if old key was "config_com.whatsapp", it stays that way.
        // Wait, in DataStore implementation: stringSetPreferencesKey("config_$packageName")
        // So the key name IS "config_com.whatsapp".
        // But here for clarity, let's try to stick to that naming convention or migrate gracefully.
        // Since the migration copies key names exactly, we should reuse the exact old key name structure
        // which was: "config_$packageName"
        val legacyKey = "config_$packageName"
        return dao.getSettingFlow(legacyKey).map { str ->
            str?.deserializeSet() ?: NotificationType.entries.map { t -> t.name }.toSet()
        }
    }

    suspend fun updateAppConfig(packageName: String, type: NotificationType, isEnabled: Boolean) {
        val key = "config_$packageName"
        val currentStr = dao.getSetting(key)
        val currentSet = currentStr?.deserializeSet() ?: NotificationType.entries.map { it.name }.toSet()
        val newSet = if (isEnabled) currentSet + type.name else currentSet - type.name
        save(key, newSet.serialize())
    }

    // --- ISLAND CONFIG ---
    private fun sanitizeTimeout(raw: Long?): Long {
        val value = raw ?: 5L
        return if (value > 60) value / 1000 else value
    }

    val globalConfigFlow: Flow<IslandConfig> = combine(
        dao.getSettingFlow(SettingsKeys.GLOBAL_FLOAT),
        dao.getSettingFlow(SettingsKeys.GLOBAL_SHADE),
        dao.getSettingFlow(SettingsKeys.GLOBAL_TIMEOUT)
    ) { f, s, t ->
        IslandConfig(
            f.toBoolean(true),
            s.toBoolean(true),
            sanitizeTimeout(t?.toLongOrNull())
        )
    }

    suspend fun updateGlobalConfig(config: IslandConfig) {
        config.isFloat?.let { save(SettingsKeys.GLOBAL_FLOAT, it.toString()) }
        config.isShowShade?.let { save(SettingsKeys.GLOBAL_SHADE, it.toString()) }
        config.timeout?.let { save(SettingsKeys.GLOBAL_TIMEOUT, it.toString()) }
    }

    fun getAppIslandConfig(packageName: String): Flow<IslandConfig> {
        // Old keys were: config_{pkg}_float, etc.
        // Migration preserves these names.
        return combine(
            dao.getSettingFlow("config_${packageName}_float"),
            dao.getSettingFlow("config_${packageName}_shade"),
            dao.getSettingFlow("config_${packageName}_timeout")
        ) { f, s, t ->
            IslandConfig(
                f?.toBoolean(),
                s?.toBoolean(),
                if (t != null) sanitizeTimeout(t.toLong()) else null
            )
        }
    }

    suspend fun updateAppIslandConfig(packageName: String, config: IslandConfig) {
        val fKey = "config_${packageName}_float"
        val sKey = "config_${packageName}_shade"
        val tKey = "config_${packageName}_timeout"

        if (config.isFloat != null) save(fKey, config.isFloat.toString()) else remove(fKey)
        if (config.isShowShade != null) save(sKey, config.isShowShade.toString()) else remove(sKey)
        if (config.timeout != null) save(tKey, config.timeout.toString()) else remove(tKey)
    }

    // --- NAVIGATION & BLOCKED TERMS ---

    val globalBlockedTermsFlow: Flow<Set<String>> = dao.getSettingFlow(SettingsKeys.GLOBAL_BLOCKED_TERMS).map { it.deserializeSet() }

    suspend fun setGlobalBlockedTerms(terms: Set<String>) = save(SettingsKeys.GLOBAL_BLOCKED_TERMS, terms.serialize())

    fun getAppBlockedTerms(packageName: String): Flow<Set<String>> {
        return dao.getSettingFlow("config_${packageName}_blocked").map { it.deserializeSet() }
    }

    suspend fun setAppBlockedTerms(packageName: String, terms: Set<String>) {
        save("config_${packageName}_blocked", terms.serialize())
    }

    // Navigation
    val globalNavLayoutFlow: Flow<Pair<NavContent, NavContent>> = combine(
        dao.getSettingFlow(SettingsKeys.NAV_LEFT),
        dao.getSettingFlow(SettingsKeys.NAV_RIGHT)
    ) { l, r ->
        val left = try { NavContent.valueOf(l ?: NavContent.DISTANCE_ETA.name) } catch (e: Exception) { NavContent.DISTANCE_ETA }
        val right = try { NavContent.valueOf(r ?: NavContent.INSTRUCTION.name) } catch (e: Exception) { NavContent.INSTRUCTION }
        left to right
    }

    suspend fun setGlobalNavLayout(left: NavContent, right: NavContent) {
        save(SettingsKeys.NAV_LEFT, left.name)
        save(SettingsKeys.NAV_RIGHT, right.name)
    }

    fun getAppNavLayout(packageName: String): Flow<Pair<NavContent?, NavContent?>> {
        return combine(
            dao.getSettingFlow("config_${packageName}_nav_left"),
            dao.getSettingFlow("config_${packageName}_nav_right")
        ) { l, r ->
            val left = l?.let { try { NavContent.valueOf(it) } catch(e: Exception){null} }
            val right = r?.let { try { NavContent.valueOf(it) } catch(e: Exception){null} }
            left to right
        }
    }

    fun getEffectiveNavLayout(packageName: String): Flow<Pair<NavContent, NavContent>> {
        return combine(getAppNavLayout(packageName), globalNavLayoutFlow) { app, global ->
            (app.first ?: global.first) to (app.second ?: global.second)
        }
    }

    suspend fun updateAppNavLayout(packageName: String, left: NavContent?, right: NavContent?) {
        val lKey = "config_${packageName}_nav_left"
        val rKey = "config_${packageName}_nav_right"
        if (left != null) save(lKey, left.name) else remove(lKey)
        if (right != null) save(rKey, right.name) else remove(rKey)
    }

    // --- MUSIC ISLAND ---
    val musicIslandModeFlow: Flow<MusicIslandMode> = dao.getSettingFlow(SettingsKeys.MUSIC_ISLAND_MODE).map {
        try { MusicIslandMode.valueOf(it ?: MusicIslandMode.SYSTEM_ONLY.name) } catch (e: Exception) { MusicIslandMode.SYSTEM_ONLY }
    }

    val musicBlockAppsFlow: Flow<Set<String>> = dao.getSettingFlow(SettingsKeys.MUSIC_BLOCK_APPS).map { it.deserializeSet() }

    val musicBlockWarningShownFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.MUSIC_BLOCK_WARNING_SHOWN).map { it.toBoolean(false) }

    suspend fun setMusicIslandMode(mode: MusicIslandMode) = save(SettingsKeys.MUSIC_ISLAND_MODE, mode.name)

    suspend fun setMusicBlockApps(apps: Set<String>) = save(SettingsKeys.MUSIC_BLOCK_APPS, apps.serialize())

    suspend fun setMusicBlockWarningShown(shown: Boolean) = save(SettingsKeys.MUSIC_BLOCK_WARNING_SHOWN, shown.toString())

    // --- SYSTEM STATE ISLANDS ---
    val systemStateIslandsEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.SYSTEM_STATE_ISLANDS_ENABLED).map { it.toBoolean(false) }

    val systemStateInfoShownFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.SYSTEM_STATE_INFO_SHOWN).map { it.toBoolean(false) }

    suspend fun setSystemStateIslandsEnabled(enabled: Boolean) = save(SettingsKeys.SYSTEM_STATE_ISLANDS_ENABLED, enabled.toString())

    suspend fun setSystemStateInfoShown(shown: Boolean) = save(SettingsKeys.SYSTEM_STATE_INFO_SHOWN, shown.toString())

    // --- SMART SILENCE (Anti-Spam) ---
    val smartSilenceEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.SMART_SILENCE_ENABLED).map { it.toBoolean(true) }
    val smartSilenceWindowMsFlow: Flow<Long> = dao.getSettingFlow(SettingsKeys.SMART_SILENCE_WINDOW_MS).map { it.toLong(10000L) }

    suspend fun setSmartSilenceEnabled(enabled: Boolean) = save(SettingsKeys.SMART_SILENCE_ENABLED, enabled.toString())
    suspend fun setSmartSilenceWindowMs(windowMs: Long) = save(SettingsKeys.SMART_SILENCE_WINDOW_MS, windowMs.toString())

    // --- FOCUS AUTOMATION ---
    val focusEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.FOCUS_ENABLED).map { it.toBoolean(false) }
    val focusQuietStartFlow: Flow<String> = dao.getSettingFlow(SettingsKeys.FOCUS_QUIET_START).map { it ?: "00:00" }
    val focusQuietEndFlow: Flow<String> = dao.getSettingFlow(SettingsKeys.FOCUS_QUIET_END).map { it ?: "08:00" }
    val focusAllowedTypesFlow: Flow<Set<String>> = dao.getSettingFlow(SettingsKeys.FOCUS_ALLOWED_TYPES).map {
        it.deserializeSet().ifEmpty { setOf("CALL", "TIMER") }
    }

    suspend fun setFocusEnabled(enabled: Boolean) = save(SettingsKeys.FOCUS_ENABLED, enabled.toString())
    suspend fun setFocusQuietStart(time: String) = save(SettingsKeys.FOCUS_QUIET_START, time)
    suspend fun setFocusQuietEnd(time: String) = save(SettingsKeys.FOCUS_QUIET_END, time)
    suspend fun setFocusAllowedTypes(types: Set<String>) = save(SettingsKeys.FOCUS_ALLOWED_TYPES, types.serialize())

    // --- NOTIFICATION SUMMARY ---
    val summaryEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.SUMMARY_ENABLED).map { it.toBoolean(false) }
    val summaryHourFlow: Flow<Int> = dao.getSettingFlow(SettingsKeys.SUMMARY_HOUR).map { it.toInt(21) }

    suspend fun setSummaryEnabled(enabled: Boolean) = save(SettingsKeys.SUMMARY_ENABLED, enabled.toString())
    suspend fun setSummaryHour(hour: Int) = save(SettingsKeys.SUMMARY_HOUR, hour.toString())

    // --- HAPTICS ---
    val hapticsEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.HAPTICS_ENABLED).map { it.toBoolean(true) }

    suspend fun setHapticsEnabled(enabled: Boolean) = save(SettingsKeys.HAPTICS_ENABLED, enabled.toString())

    // --- DISMISS COOLDOWN ---
    val dismissCooldownSecondsFlow: Flow<Int> = dao.getSettingFlow(SettingsKeys.DISMISS_COOLDOWN_SECONDS).map { it.toInt(30) }

    suspend fun setDismissCooldownSeconds(seconds: Int) = save(SettingsKeys.DISMISS_COOLDOWN_SECONDS, seconds.toString())

    // --- PER-APP MUTE/BLOCK ---
    val perAppMutedFlow: Flow<Set<String>> = dao.getSettingFlow(SettingsKeys.PER_APP_MUTED).map { it.deserializeSet() }
    val perAppBlockedFlow: Flow<Set<String>> = dao.getSettingFlow(SettingsKeys.PER_APP_BLOCKED).map { it.deserializeSet() }

    suspend fun setPerAppMuted(apps: Set<String>) = save(SettingsKeys.PER_APP_MUTED, apps.serialize())
    suspend fun setPerAppBlocked(apps: Set<String>) = save(SettingsKeys.PER_APP_BLOCKED, apps.serialize())

    suspend fun muteApp(packageName: String) {
        val current = dao.getSetting(SettingsKeys.PER_APP_MUTED).deserializeSet()
        save(SettingsKeys.PER_APP_MUTED, (current + packageName).serialize())
    }

    suspend fun unmuteApp(packageName: String) {
        val current = dao.getSetting(SettingsKeys.PER_APP_MUTED).deserializeSet()
        save(SettingsKeys.PER_APP_MUTED, (current - packageName).serialize())
    }

    suspend fun blockAppIslands(packageName: String) {
        val current = dao.getSetting(SettingsKeys.PER_APP_BLOCKED).deserializeSet()
        save(SettingsKeys.PER_APP_BLOCKED, (current + packageName).serialize())
    }

    suspend fun unblockAppIslands(packageName: String) {
        val current = dao.getSetting(SettingsKeys.PER_APP_BLOCKED).deserializeSet()
        save(SettingsKeys.PER_APP_BLOCKED, (current - packageName).serialize())
    }

    fun isAppMuted(packageName: String): Flow<Boolean> = perAppMutedFlow.map { it.contains(packageName) }
    fun isAppBlocked(packageName: String): Flow<Boolean> = perAppBlockedFlow.map { it.contains(packageName) }

    // --- SYSTEM BANNERS (AirPods-style) ---
    val bannerBtConnectedEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.BANNER_BT_CONNECTED_ENABLED).map { it.toBoolean(false) }
    val bannerBatteryLowEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.BANNER_BATTERY_LOW_ENABLED).map { it.toBoolean(false) }
    val bannerCopiedEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.BANNER_COPIED_ENABLED).map { it.toBoolean(false) }

    suspend fun setBannerBtConnectedEnabled(enabled: Boolean) = save(SettingsKeys.BANNER_BT_CONNECTED_ENABLED, enabled.toString())
    suspend fun setBannerBatteryLowEnabled(enabled: Boolean) = save(SettingsKeys.BANNER_BATTERY_LOW_ENABLED, enabled.toString())
    suspend fun setBannerCopiedEnabled(enabled: Boolean) = save(SettingsKeys.BANNER_COPIED_ENABLED, enabled.toString())

    // --- SMART PRIORITY (v0.6.0) ---
    val smartPriorityEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.SMART_PRIORITY_ENABLED).map { it.toBoolean(true) }
    val smartPriorityAggressivenessFlow: Flow<Int> = dao.getSettingFlow(SettingsKeys.SMART_PRIORITY_AGGRESSIVENESS).map { it.toInt(1) }

    suspend fun setSmartPriorityEnabled(enabled: Boolean) = save(SettingsKeys.SMART_PRIORITY_ENABLED, enabled.toString())
    suspend fun setSmartPriorityAggressiveness(level: Int) = save(SettingsKeys.SMART_PRIORITY_AGGRESSIVENESS, level.toString())

    // --- PER-APP SMART PRIORITY PROFILE (v0.9.4) ---
    // Stored as dynamic keys: sp_profile_<packageName> -> NORMAL|LENIENT|STRICT
    // No schema changes - uses existing key/value storage
    
    /**
     * Gets the Smart Priority profile for a specific app.
     * Returns NORMAL if not set.
     */
    suspend fun getSmartPriorityProfile(packageName: String): com.coni.hyperisle.models.SmartPriorityProfile {
        val key = "sp_profile_$packageName"
        val value = dao.getSetting(key)
        return try {
            if (value != null) com.coni.hyperisle.models.SmartPriorityProfile.valueOf(value)
            else com.coni.hyperisle.models.SmartPriorityProfile.NORMAL
        } catch (e: Exception) {
            com.coni.hyperisle.models.SmartPriorityProfile.NORMAL
        }
    }

    /**
     * Gets the Smart Priority profile as a Flow for reactive UI.
     */
    fun getSmartPriorityProfileFlow(packageName: String): Flow<com.coni.hyperisle.models.SmartPriorityProfile> {
        val key = "sp_profile_$packageName"
        return dao.getSettingFlow(key).map { value ->
            try {
                if (value != null) com.coni.hyperisle.models.SmartPriorityProfile.valueOf(value)
                else com.coni.hyperisle.models.SmartPriorityProfile.NORMAL
            } catch (e: Exception) {
                com.coni.hyperisle.models.SmartPriorityProfile.NORMAL
            }
        }
    }

    /**
     * Sets the Smart Priority profile for a specific app.
     * Setting to NORMAL removes the key (default behavior).
     */
    suspend fun setSmartPriorityProfile(packageName: String, profile: com.coni.hyperisle.models.SmartPriorityProfile) {
        val key = "sp_profile_$packageName"
        if (profile == com.coni.hyperisle.models.SmartPriorityProfile.NORMAL) {
            remove(key)
        } else {
            save(key, profile.name)
        }
    }

    // Dynamic keys for dismiss counts and throttle
    suspend fun getPriorityDismissCount(packageName: String, typeName: String, dateKey: String): Int {
        val key = "priority_dismiss_${packageName}_${typeName}_$dateKey"
        return dao.getSetting(key).toInt(0)
    }

    suspend fun setPriorityDismissCount(packageName: String, typeName: String, dateKey: String, count: Int) {
        val key = "priority_dismiss_${packageName}_${typeName}_$dateKey"
        save(key, count.toString())
    }

    suspend fun getPriorityThrottleUntil(packageName: String, typeName: String): Long {
        val key = "priority_throttle_until_${packageName}_$typeName"
        return dao.getSetting(key).toLong(0L)
    }

    suspend fun setPriorityThrottleUntil(packageName: String, typeName: String, timestamp: Long) {
        val key = "priority_throttle_until_${packageName}_$typeName"
        save(key, timestamp.toString())
    }

    suspend fun clearPriorityThrottleUntil(packageName: String, typeName: String) {
        val key = "priority_throttle_until_${packageName}_$typeName"
        remove(key)
    }

    // --- LEARNING SIGNALS (v0.9.2) ---
    // Dynamic keys for learning signal counters (PII-safe: packageName + date only)
    suspend fun getLearningFastDismissCount(packageName: String, dateKey: String): Int {
        val key = "learning_fast_dismiss_${packageName}_$dateKey"
        return dao.getSetting(key).toInt(0)
    }

    suspend fun setLearningFastDismissCount(packageName: String, dateKey: String, count: Int) {
        val key = "learning_fast_dismiss_${packageName}_$dateKey"
        save(key, count.coerceIn(0, LEARNING_COUNTER_CAP).toString())
    }

    suspend fun getLearningTapOpenCount(packageName: String, dateKey: String): Int {
        val key = "learning_tap_open_${packageName}_$dateKey"
        return dao.getSetting(key).toInt(0)
    }

    suspend fun setLearningTapOpenCount(packageName: String, dateKey: String, count: Int) {
        val key = "learning_tap_open_${packageName}_$dateKey"
        save(key, count.coerceIn(0, LEARNING_COUNTER_CAP).toString())
    }

    suspend fun getLearningMuteBlockCount(packageName: String, dateKey: String): Int {
        val key = "learning_mute_block_${packageName}_$dateKey"
        return dao.getSetting(key).toInt(0)
    }

    suspend fun setLearningMuteBlockCount(packageName: String, dateKey: String, count: Int) {
        val key = "learning_mute_block_${packageName}_$dateKey"
        save(key, count.coerceIn(0, LEARNING_COUNTER_CAP).toString())
    }

    // --- LEARNING SIGNAL DECAY & CLEANUP (v0.9.3) ---

    /**
     * Returns the last time decay was applied (epoch ms), or 0 if never.
     */
    suspend fun getLearningLastDecayAt(): Long {
        return dao.getSetting(SettingsKeys.LEARNING_LAST_DECAY_AT).toLong(0L)
    }

    /**
     * Applies decay to all learning counters if at least 24 hours have passed since last decay.
     * Reduces each counter by 10% (floored). Also cleans up stale entries older than 24h.
     * 
     * Should be called on app start or periodically. Non-blocking when called from IO dispatcher.
     */
    suspend fun applyLearningDecayIfNeeded() {
        val now = System.currentTimeMillis()
        val lastDecay = getLearningLastDecayAt()
        
        if (now - lastDecay < DECAY_INTERVAL_MS) return
        
        // Get all learning signal keys
        val learningPrefixes = listOf("learning_fast_dismiss_", "learning_tap_open_", "learning_mute_block_")
        val priorityDismissPrefix = "priority_dismiss_"
        
        // Apply decay to learning counters
        for (prefix in learningPrefixes) {
            val entries = dao.getByPrefix(prefix)
            for (entry in entries) {
                val currentValue = entry.value.toIntOrNull() ?: 0
                if (currentValue > 0) {
                    val decayed = (currentValue * (1 - LEARNING_DECAY_PERCENT)).toInt()
                    if (decayed > 0) {
                        dao.insert(AppSetting(entry.key, decayed.toString()))
                    } else {
                        dao.delete(entry.key)
                    }
                }
            }
        }
        
        // Apply decay to priority dismiss counters
        val dismissEntries = dao.getByPrefix(priorityDismissPrefix)
        for (entry in dismissEntries) {
            val currentValue = entry.value.toIntOrNull() ?: 0
            if (currentValue > 0) {
                val decayed = (currentValue * (1 - LEARNING_DECAY_PERCENT)).toInt()
                if (decayed > 0) {
                    dao.insert(AppSetting(entry.key, decayed.toString()))
                } else {
                    dao.delete(entry.key)
                }
            }
        }
        
        // Clean up stale throttle entries (expired)
        val throttleEntries = dao.getByPrefix("priority_throttle_until_")
        for (entry in throttleEntries) {
            val expiryTime = entry.value.toLongOrNull() ?: 0L
            if (expiryTime < now) {
                dao.delete(entry.key)
            }
        }
        
        // Update last decay timestamp
        save(SettingsKeys.LEARNING_LAST_DECAY_AT, now.toString())
    }

    /**
     * Clears all Smart Priority learning state (counters, throttles, timestamps).
     * Used for debug reset functionality.
     */
    suspend fun clearAllLearningState() {
        // Clear all learning signal counters
        dao.deleteByPrefix("learning_fast_dismiss_")
        dao.deleteByPrefix("learning_tap_open_")
        dao.deleteByPrefix("learning_mute_block_")
        
        // Clear priority dismiss counters
        dao.deleteByPrefix("priority_dismiss_")
        
        // Clear throttle timestamps
        dao.deleteByPrefix("priority_throttle_until_")
        
        // Reset decay timestamp
        remove(SettingsKeys.LEARNING_LAST_DECAY_AT)
    }

    // --- CONTEXT-AWARE ISLANDS (v0.7.0) ---
    val contextAwareEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.CONTEXT_AWARE_ENABLED).map { it.toBoolean(false) }
    val contextScreenOffOnlyImportantFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.CONTEXT_SCREEN_OFF_ONLY_IMPORTANT).map { it.toBoolean(true) }
    val contextScreenOffImportantTypesFlow: Flow<Set<String>> = dao.getSettingFlow(SettingsKeys.CONTEXT_SCREEN_OFF_IMPORTANT_TYPES).map {
        it.deserializeSet().ifEmpty { setOf("CALL", "TIMER", "NAVIGATION") }
    }
    val contextChargingSuppressBatteryBannersFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.CONTEXT_CHARGING_SUPPRESS_BATTERY_BANNERS).map { it.toBoolean(true) }

    suspend fun setContextAwareEnabled(enabled: Boolean) = save(SettingsKeys.CONTEXT_AWARE_ENABLED, enabled.toString())
    suspend fun setContextScreenOffOnlyImportant(enabled: Boolean) = save(SettingsKeys.CONTEXT_SCREEN_OFF_ONLY_IMPORTANT, enabled.toString())
    suspend fun setContextScreenOffImportantTypes(types: Set<String>) = save(SettingsKeys.CONTEXT_SCREEN_OFF_IMPORTANT_TYPES, types.serialize())
    suspend fun setContextChargingSuppressBatteryBanners(enabled: Boolean) = save(SettingsKeys.CONTEXT_CHARGING_SUPPRESS_BATTERY_BANNERS, enabled.toString())

    // --- CONTEXT PRESETS (v0.9.0) ---
    val contextPresetFlow: Flow<com.coni.hyperisle.models.ContextPreset> = dao.getSettingFlow(SettingsKeys.CONTEXT_PRESET).map {
        try { com.coni.hyperisle.models.ContextPreset.valueOf(it ?: com.coni.hyperisle.models.ContextPreset.OFF.name) } catch (e: Exception) { com.coni.hyperisle.models.ContextPreset.OFF }
    }

    suspend fun setContextPreset(preset: com.coni.hyperisle.models.ContextPreset) = save(SettingsKeys.CONTEXT_PRESET, preset.name)

    // --- DEBUG DIAGNOSTICS (debug builds only) ---
    val actionDiagnosticsEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.ACTION_DIAGNOSTICS_ENABLED).map { it.toBoolean(false) }

    suspend fun setActionDiagnosticsEnabled(enabled: Boolean) {
        save(SettingsKeys.ACTION_DIAGNOSTICS_ENABLED, enabled.toString())
        com.coni.hyperisle.util.ActionDiagnostics.setEnabled(enabled)
    }

    /**
     * Initializes ActionDiagnostics enabled state from persisted value.
     * Should be called once during app startup (in debug builds only).
     */
    suspend fun initActionDiagnostics() {
        if (!com.coni.hyperisle.BuildConfig.DEBUG) return
        val enabled = dao.getSetting(SettingsKeys.ACTION_DIAGNOSTICS_ENABLED).toBoolean(false)
        com.coni.hyperisle.util.ActionDiagnostics.setEnabled(enabled)
    }

    // --- ACTION LONG-PRESS INFO (debug builds only) ---
    val actionLongPressInfoEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.ACTION_LONG_PRESS_INFO).map { it.toBoolean(false) }

    suspend fun setActionLongPressInfoEnabled(enabled: Boolean) {
        save(SettingsKeys.ACTION_LONG_PRESS_INFO, enabled.toString())
    }

    /**
     * Synchronously checks if action long-press info toast is enabled.
     * For use in non-suspend contexts (e.g., BroadcastReceiver).
     */
    suspend fun isActionLongPressInfoEnabled(): Boolean {
        if (!com.coni.hyperisle.BuildConfig.DEBUG) return false
        return dao.getSetting(SettingsKeys.ACTION_LONG_PRESS_INFO).toBoolean(false)
    }

    // --- PRIORITY DIAGNOSTICS (debug builds only) ---
    val priorityDiagnosticsEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.PRIORITY_DIAGNOSTICS_ENABLED).map { it.toBoolean(false) }

    suspend fun setPriorityDiagnosticsEnabled(enabled: Boolean) {
        save(SettingsKeys.PRIORITY_DIAGNOSTICS_ENABLED, enabled.toString())
        com.coni.hyperisle.util.PriorityDiagnostics.setEnabled(enabled)
    }

    /**
     * Initializes PriorityDiagnostics enabled state from persisted value.
     * Should be called once during app startup (in debug builds only).
     */
    suspend fun initPriorityDiagnostics() {
        if (!com.coni.hyperisle.BuildConfig.DEBUG) return
        val enabled = dao.getSetting(SettingsKeys.PRIORITY_DIAGNOSTICS_ENABLED).toBoolean(false)
        com.coni.hyperisle.util.PriorityDiagnostics.setEnabled(enabled)
    }

    // --- TIMELINE DIAGNOSTICS (debug builds only) ---
    val timelineDiagnosticsEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.TIMELINE_DIAGNOSTICS_ENABLED).map { it.toBoolean(false) }

    suspend fun setTimelineDiagnosticsEnabled(enabled: Boolean) {
        save(SettingsKeys.TIMELINE_DIAGNOSTICS_ENABLED, enabled.toString())
        com.coni.hyperisle.util.DebugTimeline.setEnabled(enabled)
    }

    /**
     * Initializes DebugTimeline enabled state from persisted value.
     * Should be called once during app startup (in debug builds only).
     */
    suspend fun initTimelineDiagnostics() {
        if (!com.coni.hyperisle.BuildConfig.DEBUG) return
        val enabled = dao.getSetting(SettingsKeys.TIMELINE_DIAGNOSTICS_ENABLED).toBoolean(false)
        com.coni.hyperisle.util.DebugTimeline.setEnabled(enabled)
    }

    // --- TIME VISIBILITY ON ISLANDS (v0.9.4) ---
    val showTimeOnIslandsFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.SHOW_TIME_ON_ISLANDS).map { it.toBoolean(false) }

    suspend fun setShowTimeOnIslands(enabled: Boolean) = save(SettingsKeys.SHOW_TIME_ON_ISLANDS, enabled.toString())

    // --- PER-APP SHADE CANCEL (v0.9.5) ---
    // Dynamic keys: shade_cancel_<packageName> -> true/false
    // Default: false (safe-by-default, no behavior change unless user enables)

    /**
     * Gets whether shade cancel is enabled for a specific app.
     * Returns false by default (safe-by-default).
     */
    suspend fun isShadeCancel(packageName: String): Boolean {
        val key = "shade_cancel_$packageName"
        return dao.getSetting(key).toBoolean(false)
    }

    /**
     * Gets shade cancel setting as a Flow for reactive UI.
     */
    fun isShadeCancelFlow(packageName: String): Flow<Boolean> {
        val key = "shade_cancel_$packageName"
        return dao.getSettingFlow(key).map { it.toBoolean(false) }
    }

    /**
     * Sets whether to cancel notifications from system shade for a specific app.
     * Setting to false removes the key (default behavior).
     */
    suspend fun setShadeCancel(packageName: String, enabled: Boolean) {
        val key = "shade_cancel_$packageName"
        if (!enabled) {
            remove(key)
        } else {
            save(key, enabled.toString())
        }
    }

    // --- PERMISSION BANNER SNOOZE (v0.9.6) ---
    val permissionBannerSnoozeUntilFlow: Flow<Long> = dao.getSettingFlow(SettingsKeys.PERMISSION_BANNER_SNOOZE_UNTIL).map { it.toLong(0L) }

    suspend fun getPermissionBannerSnoozeUntil(): Long {
        return dao.getSetting(SettingsKeys.PERMISSION_BANNER_SNOOZE_UNTIL).toLong(0L)
    }

    suspend fun setPermissionBannerSnoozeUntil(timestamp: Long) {
        save(SettingsKeys.PERMISSION_BANNER_SNOOZE_UNTIL, timestamp.toString())
    }

    suspend fun snoozePermissionBanner() {
        val snoozeUntil = System.currentTimeMillis() + (24 * 60 * 60 * 1000L) // 24 hours
        setPermissionBannerSnoozeUntil(snoozeUntil)
    }

    suspend fun isPermissionBannerSnoozed(): Boolean {
        val snoozeUntil = getPermissionBannerSnoozeUntil()
        return System.currentTimeMillis() < snoozeUntil
    }

    // --- CALLS-ONLY-ISLAND (v0.9.7) ---
    // Global boolean: hide ongoing call notifications from system shade
    val callsOnlyIslandEnabledFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.CALLS_ONLY_ISLAND_ENABLED).map { it.toBoolean(false) }
    val callsOnlyIslandConfirmedFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.CALLS_ONLY_ISLAND_CONFIRMED).map { it.toBoolean(false) }

    suspend fun isCallsOnlyIslandEnabled(): Boolean {
        return dao.getSetting(SettingsKeys.CALLS_ONLY_ISLAND_ENABLED).toBoolean(false)
    }

    suspend fun setCallsOnlyIslandEnabled(enabled: Boolean) {
        save(SettingsKeys.CALLS_ONLY_ISLAND_ENABLED, enabled.toString())
    }

    suspend fun isCallsOnlyIslandConfirmed(): Boolean {
        return dao.getSetting(SettingsKeys.CALLS_ONLY_ISLAND_CONFIRMED).toBoolean(false)
    }

    suspend fun setCallsOnlyIslandConfirmed(confirmed: Boolean) {
        save(SettingsKeys.CALLS_ONLY_ISLAND_CONFIRMED, confirmed.toString())
    }

    // --- MIUI BRIDGE ISLAND (experimental) ---
    val useMiuiBridgeIslandFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.PREF_USE_MIUI_BRIDGE_ISLAND).map { it.toBoolean(false) }

    suspend fun setUseMiuiBridgeIsland(enabled: Boolean) {
        save(SettingsKeys.PREF_USE_MIUI_BRIDGE_ISLAND, enabled.toString())
    }

    /**
     * Counts how many apps have shade cancel enabled.
     * Used for diagnostics header.
     */
    suspend fun getShadeCancelEnabledCount(): Int {
        val entries = dao.getByPrefix("shade_cancel_")
        return entries.count { !it.key.contains("_mode_") && it.value.toBoolean(false) }
    }

    // --- PER-APP SHADE CANCEL MODE (v0.9.8) ---
    // Dynamic keys: shade_cancel_mode_<packageName> -> SAFE/AGGRESSIVE
    // Default: SAFE (do NOT cancel foreground service / critical notifications)

    /**
     * Gets the shade cancel mode for a specific app.
     * Returns SAFE by default.
     */
    suspend fun getShadeCancelMode(packageName: String): com.coni.hyperisle.models.ShadeCancelMode {
        val key = "shade_cancel_mode_$packageName"
        val value = dao.getSetting(key)
        return try {
            if (value != null) com.coni.hyperisle.models.ShadeCancelMode.valueOf(value)
            else com.coni.hyperisle.models.ShadeCancelMode.SAFE
        } catch (e: Exception) {
            com.coni.hyperisle.models.ShadeCancelMode.SAFE
        }
    }

    /**
     * Gets shade cancel mode as a Flow for reactive UI.
     */
    fun getShadeCancelModeFlow(packageName: String): Flow<com.coni.hyperisle.models.ShadeCancelMode> {
        val key = "shade_cancel_mode_$packageName"
        return dao.getSettingFlow(key).map { value ->
            try {
                if (value != null) com.coni.hyperisle.models.ShadeCancelMode.valueOf(value)
                else com.coni.hyperisle.models.ShadeCancelMode.SAFE
            } catch (e: Exception) {
                com.coni.hyperisle.models.ShadeCancelMode.SAFE
            }
        }
    }

    /**
     * Sets the shade cancel mode for a specific app.
     * Setting to SAFE removes the key (default behavior).
     */
    suspend fun setShadeCancelMode(packageName: String, mode: com.coni.hyperisle.models.ShadeCancelMode) {
        val key = "shade_cancel_mode_$packageName"
        if (mode == com.coni.hyperisle.models.ShadeCancelMode.SAFE) {
            remove(key)
        } else {
            save(key, mode.name)
        }
    }

    /**
     * Clears all settings related to a specific app.
     * Used when user disables an app in HyperIsle.
     */
    suspend fun clearAppSettings(packageName: String) {
        // Remove from allowed packages
        val currentAllowed = dao.getSetting(SettingsKeys.ALLOWED_PACKAGES).deserializeSet()
        if (currentAllowed.contains(packageName)) {
            save(SettingsKeys.ALLOWED_PACKAGES, (currentAllowed - packageName).serialize())
        }
        
        // Remove per-app muted
        val currentMuted = dao.getSetting(SettingsKeys.PER_APP_MUTED).deserializeSet()
        if (currentMuted.contains(packageName)) {
            save(SettingsKeys.PER_APP_MUTED, (currentMuted - packageName).serialize())
        }
        
        // Remove per-app blocked
        val currentBlocked = dao.getSetting(SettingsKeys.PER_APP_BLOCKED).deserializeSet()
        if (currentBlocked.contains(packageName)) {
            save(SettingsKeys.PER_APP_BLOCKED, (currentBlocked - packageName).serialize())
        }
        
        // Remove dynamic keys for this app
        dao.deleteByPrefix("config_${packageName}_")
        dao.deleteByPrefix("shade_cancel_$packageName")
        dao.deleteByPrefix("shade_cancel_mode_$packageName")
        dao.deleteByPrefix("sp_profile_$packageName")
        dao.deleteByPrefix("learning_fast_dismiss_${packageName}_")
        dao.deleteByPrefix("learning_tap_open_${packageName}_")
        dao.deleteByPrefix("learning_mute_block_${packageName}_")
        dao.deleteByPrefix("priority_dismiss_${packageName}_")
        dao.deleteByPrefix("priority_throttle_until_${packageName}_")
    }

    /**
     * Checks if any app has "Only on Island" (shade cancel) enabled.
     * Used for warning banner display logic.
     */
    suspend fun hasAnyAppWithShadeCancel(): Boolean {
        val entries = dao.getByPrefix("shade_cancel_")
        return entries.any { !it.key.contains("_mode_") && it.value.toBoolean(false) }
    }

    // --- DEV ISLAND STYLE PREVIEW (debug builds only) ---
    val devIslandStylePreviewFlow: Flow<Boolean> = dao.getSettingFlow(SettingsKeys.DEV_ISLAND_STYLE_PREVIEW).map { it.toBoolean(false) }

    suspend fun setDevIslandStylePreview(enabled: Boolean) = save(SettingsKeys.DEV_ISLAND_STYLE_PREVIEW, enabled.toString())

    suspend fun isDevIslandStylePreviewEnabled(): Boolean {
        if (!com.coni.hyperisle.BuildConfig.DEBUG) return false
        return dao.getSetting(SettingsKeys.DEV_ISLAND_STYLE_PREVIEW).toBoolean(false)
    }
}
