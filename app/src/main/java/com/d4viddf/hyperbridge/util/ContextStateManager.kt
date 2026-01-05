package com.d4viddf.hyperbridge.util

import android.content.Context
import android.os.PowerManager
import com.d4viddf.hyperbridge.data.db.AppDatabase
import com.d4viddf.hyperbridge.data.db.AppSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Context State Manager for v0.7.0 Context-Aware Islands.
 * 
 * Tracks screen on/off and charging state from broadcast receivers.
 * Persists last-known values to Room for recovery after process death.
 * No polling - only updates when broadcasts are received.
 * 
 * Provides a fallback using PowerManager.isInteractive when state is stale.
 */
object ContextStateManager {

    private const val KEY_SCREEN_ON = "ctx_screen_on"
    private const val KEY_CHARGING = "ctx_charging"
    private const val KEY_LAST_UPDATED_MS = "ctx_last_updated_ms"

    // In-memory state
    @Volatile
    private var isScreenOn: Boolean = true

    @Volatile
    private var isCharging: Boolean = false

    @Volatile
    private var lastUpdatedMs: Long = 0L

    // Debounce tracking (1 second)
    private const val DEBOUNCE_MS = 1000L

    @Volatile
    private var lastScreenUpdateMs: Long = 0L

    @Volatile
    private var lastChargingUpdateMs: Long = 0L

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Initialize from persisted state. Call once at app/service startup.
     */
    fun initialize(context: Context) {
        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(context).settingsDao()
                isScreenOn = dao.getSetting(KEY_SCREEN_ON)?.toBooleanStrictOrNull() ?: true
                isCharging = dao.getSetting(KEY_CHARGING)?.toBooleanStrictOrNull() ?: false
                lastUpdatedMs = dao.getSetting(KEY_LAST_UPDATED_MS)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                // Use defaults on error
            }
        }
    }

    /**
     * Update screen state from broadcast receiver.
     * Debounces repeated same-state updates within 1 second.
     */
    fun setScreenOn(context: Context, screenOn: Boolean) {
        val now = System.currentTimeMillis()

        // Debounce: if same state within 1s, ignore
        if (screenOn == isScreenOn && (now - lastScreenUpdateMs) < DEBOUNCE_MS) {
            return
        }

        isScreenOn = screenOn
        lastScreenUpdateMs = now
        lastUpdatedMs = now

        persistState(context)
    }

    /**
     * Update charging state from broadcast receiver.
     * Debounces repeated same-state updates within 1 second.
     */
    fun setCharging(context: Context, charging: Boolean) {
        val now = System.currentTimeMillis()

        // Debounce: if same state within 1s, ignore
        if (charging == isCharging && (now - lastChargingUpdateMs) < DEBOUNCE_MS) {
            return
        }

        isCharging = charging
        lastChargingUpdateMs = now
        lastUpdatedMs = now

        persistState(context)
    }

    /**
     * Get effective screen state.
     * If broadcast state is stale (>5 min), use PowerManager.isInteractive as fallback.
     */
    fun getEffectiveScreenOn(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val staleThresholdMs = 5 * 60 * 1000L // 5 minutes

        // If state is fresh, use cached value
        if (lastUpdatedMs > 0 && (now - lastUpdatedMs) < staleThresholdMs) {
            return isScreenOn
        }

        // Fallback: use PowerManager.isInteractive (single check, not polling)
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isInteractive ?: isScreenOn
        } catch (e: Exception) {
            isScreenOn
        }
    }

    /**
     * Get charging state (no fallback needed, broadcast state is reliable).
     */
    fun getCharging(): Boolean = isCharging

    /**
     * Get last update timestamp.
     */
    fun getLastUpdatedMs(): Long = lastUpdatedMs

    private fun persistState(context: Context) {
        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(context).settingsDao()
                dao.insert(AppSetting(KEY_SCREEN_ON, isScreenOn.toString()))
                dao.insert(AppSetting(KEY_CHARGING, isCharging.toString()))
                dao.insert(AppSetting(KEY_LAST_UPDATED_MS, lastUpdatedMs.toString()))
            } catch (e: Exception) {
                // Ignore persistence errors
            }
        }
    }
}
