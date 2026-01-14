package com.coni.hyperisle.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.service.NotificationReaderService
import com.coni.hyperisle.util.HiLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch



/**
 * Diagnostics utility for NotificationListenerService issues.
 * 
 * Helps diagnose why WhatsApp/Telegram/SMS notifications may not reach the listener:
 * - Lifecycle state tracking
 * - Battery optimization detection
 * - MIUI/OEM permission detection
 * - Programmatic rebind mechanism
 * - Heartbeat monitoring (debug only)
 * 
 * CRITICAL: WhatsApp/Telegram notifications not arriving is usually caused by:
 * 1. Service disconnected by system (OEM battery optimization)
 * 2. Missing from enabled_notification_listeners
 * 3. MIUI autostart/background restrictions
 * 4. App killed by system and not restarted
 */
object NotificationListenerDiagnostics {

    private const val TAG = "NLDiagnostics"

    // Lifecycle state
    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _lastConnectedTime = MutableStateFlow(0L)
    val lastConnectedTime: StateFlow<Long> = _lastConnectedTime.asStateFlow()

    private val _lastDisconnectedTime = MutableStateFlow(0L)
    val lastDisconnectedTime: StateFlow<Long> = _lastDisconnectedTime.asStateFlow()

    private val _activeNotificationCount = MutableStateFlow(-1)
    val activeNotificationCount: StateFlow<Int> = _activeNotificationCount.asStateFlow()

    private val _lastHeartbeatTime = MutableStateFlow(0L)
    val lastHeartbeatTime: StateFlow<Long> = _lastHeartbeatTime.asStateFlow()

    // Heartbeat job
    private var heartbeatJob: Job? = null
    private val heartbeatScope = CoroutineScope(Dispatchers.IO + Job())
    private val isHeartbeatRunning = AtomicBoolean(false)

    // Rebind state
    private val _isRebinding = MutableStateFlow(false)
    val isRebinding: StateFlow<Boolean> = _isRebinding.asStateFlow()

    /**
     * Data class containing all diagnostic information.
     */
    data class DiagnosticsSnapshot(
        val listenerConnected: Boolean,
        val isEnabledInSettings: Boolean,
        val isListedInEnabledListeners: Boolean,
        val isBatteryOptimized: Boolean,
        val deviceBrand: String,
        val isMiui: Boolean,
        val miuiVersion: String?,
        val hasAutostartPermission: Boolean?,
        val lastConnectedTime: Long,
        val lastDisconnectedTime: Long,
        val activeNotificationCount: Int,
        val lastHeartbeatTime: Long,
        val oemHints: List<String>
    )

    // --- Lifecycle Callbacks (called from NotificationReaderService) ---

    fun onServiceCreated() {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=SERVICE_CREATED ts=${System.currentTimeMillis()}")
        }
    }

    fun onListenerConnected(activeCount: Int) {
        val now = System.currentTimeMillis()
        _listenerConnected.value = true
        _lastConnectedTime.value = now
        _activeNotificationCount.value = activeCount
        
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=LISTENER_CONNECTED ts=$now activeNotifications=$activeCount")
        }
        
        // Start heartbeat when connected
        startHeartbeat()
    }

    fun onListenerDisconnected() {
        val now = System.currentTimeMillis()
        _listenerConnected.value = false
        _lastDisconnectedTime.value = now
        _activeNotificationCount.value = -1
        
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=LISTENER_DISCONNECTED ts=$now")
        }
        
        // Stop heartbeat when disconnected
        stopHeartbeat()
    }

    fun onServiceDestroyed() {
        _listenerConnected.value = false
        _activeNotificationCount.value = -1
        
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=SERVICE_DESTROYED ts=${System.currentTimeMillis()}")
        }
        
        stopHeartbeat()
    }

    // --- Heartbeat (Debug Only) ---

    fun updateHeartbeat(activeCount: Int) {
        if (!BuildConfig.DEBUG) return
        
        val now = System.currentTimeMillis()
        _lastHeartbeatTime.value = now
        _activeNotificationCount.value = activeCount
        
        HiLog.d(HiLog.TAG_ISLAND, "EVT=HEARTBEAT ts=$now activeNotifications=$activeCount connected=${_listenerConnected.value}")
    }

    private fun startHeartbeat() {
        if (!BuildConfig.DEBUG) return
        if (isHeartbeatRunning.getAndSet(true)) return
        
        heartbeatJob?.cancel()
        heartbeatJob = heartbeatScope.launch {
            while (isActive) {
                delay(30_000L) // Every 30 seconds
                if (_listenerConnected.value) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=HEARTBEAT_TICK ts=${System.currentTimeMillis()} connected=true activeNotifications=${_activeNotificationCount.value}")
                } else {
                    HiLog.w(HiLog.TAG_ISLAND, "EVT=HEARTBEAT_TICK ts=${System.currentTimeMillis()} connected=false WARN=LISTENER_NOT_CONNECTED")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        isHeartbeatRunning.set(false)
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // --- Diagnostics Queries ---

    /**
     * Check if HyperIsle is listed in enabled_notification_listeners.
     */
    fun isListedInEnabledListeners(context: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            
            val componentName = ComponentName(context, NotificationReaderService::class.java).flattenToString()
            flat.contains(componentName)
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Failed to check enabled_notification_listeners: ${e.message}")
            false
        }
    }

    /**
     * Check if battery optimization is enabled for HyperIsle.
     * Returns true if battery optimization is ON (bad for notification delivery).
     */
    fun isBatteryOptimized(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                !pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Failed to check battery optimization: ${e.message}")
            true
        }
    }

    /**
     * Check if this is a MIUI/HyperOS device.
     */
    fun isMiuiDevice(): Boolean {
        return DeviceUtils.isXiaomi
    }

    /**
     * Get MIUI/HyperOS version if available.
     */
    fun getMiuiVersion(): String? {
        return if (DeviceUtils.isXiaomi) {
            DeviceUtils.getHyperOSVersion()
        } else null
    }

    /**
     * Get OEM-specific permission hints for notification delivery issues.
     */
    fun getOemPermissionHints(context: Context): List<String> {
        val hints = mutableListOf<String>()
        
        if (DeviceUtils.isXiaomi) {
            hints.add("MIUI: Enable 'Autostart' in Security app → Manage apps → HyperIsle")
            hints.add("MIUI: Disable battery saver for HyperIsle in Settings → Battery")
            hints.add("MIUI: Enable 'Show on Lock screen' and 'Floating notifications' in app settings")
            if (DeviceUtils.isCNRom) {
                hints.add("MIUI CN: Check 'Notification shade' permission in Security → Permissions")
            }
        }
        
        when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> {
                hints.add("Samsung: Disable 'Put app to sleep' in Device care → Battery")
                hints.add("Samsung: Add HyperIsle to 'Never sleeping apps'")
            }
            "huawei", "honor" -> {
                hints.add("Huawei: Enable 'App launch' → Manual manage → Allow background activity")
                hints.add("Huawei: Disable 'Power-intensive app monitor' for HyperIsle")
            }
            "oppo", "realme", "oneplus" -> {
                hints.add("OPPO/Realme: Disable 'Auto-optimize' for HyperIsle in Battery settings")
                hints.add("OPPO/Realme: Enable 'Allow background activity' in App info")
            }
            "vivo" -> {
                hints.add("Vivo: Enable 'Allow background running' in i Manager → App manager")
            }
        }
        
        if (isBatteryOptimized(context)) {
            hints.add("⚠️ Battery optimization is ON - disable it for reliable notifications")
        }
        
        return hints
    }

    /**
     * Get a complete diagnostics snapshot.
     */
    fun getSnapshot(context: Context): DiagnosticsSnapshot {
        return DiagnosticsSnapshot(
            listenerConnected = _listenerConnected.value,
            isEnabledInSettings = isListedInEnabledListeners(context),
            isListedInEnabledListeners = isListedInEnabledListeners(context),
            isBatteryOptimized = isBatteryOptimized(context),
            deviceBrand = Build.MANUFACTURER,
            isMiui = isMiuiDevice(),
            miuiVersion = getMiuiVersion(),
            hasAutostartPermission = null, // Cannot reliably check on all devices
            lastConnectedTime = _lastConnectedTime.value,
            lastDisconnectedTime = _lastDisconnectedTime.value,
            activeNotificationCount = _activeNotificationCount.value,
            lastHeartbeatTime = _lastHeartbeatTime.value,
            oemHints = getOemPermissionHints(context)
        )
    }

    // --- Rebind Mechanism ---

    /**
     * Attempt to rebind the NotificationListenerService by toggling the component.
     * 
     * WARNING: This is a debug-only feature. Use with caution.
     * The user will need to re-enable notification access after this.
     * 
     * @return true if rebind was initiated successfully
     */
    fun attemptRebind(context: Context): Boolean {
        if (!BuildConfig.DEBUG) {
            HiLog.w(HiLog.TAG_ISLAND, "Rebind is only available in debug builds")
            return false
        }
        
        if (_isRebinding.value) {
            HiLog.w(HiLog.TAG_ISLAND, "Rebind already in progress")
            return false
        }
        
        _isRebinding.value = true
        
        return try {
            val componentName = ComponentName(context, NotificationReaderService::class.java)
            val pm = context.packageManager
            
            HiLog.d(HiLog.TAG_ISLAND, "EVT=REBIND_START disabling component")
            
            // Disable the component
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            // Wait briefly
            Thread.sleep(500)
            
            // Re-enable the component
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            
            HiLog.d(HiLog.TAG_ISLAND, "EVT=REBIND_COMPLETE re-enabled component")
            
            // Note: User may need to re-grant notification access permission
            _isRebinding.value = false
            true
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "EVT=REBIND_FAILED error=${e.message}")
            _isRebinding.value = false
            false
        }
    }

    /**
     * Request user to re-enable notification listener in system settings.
     * This is safer than programmatic rebind.
     */
    fun getNotificationListenerSettingsIntent(): android.content.Intent {
        return android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    /**
     * Get intent to battery optimization settings.
     */
    fun getBatteryOptimizationSettingsIntent(context: Context): android.content.Intent {
        return android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
