package com.coni.hyperisle.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.coni.hyperisle.R
import com.coni.hyperisle.util.CallManager
import com.coni.hyperisle.util.DeviceUtils
import com.coni.hyperisle.util.isNotificationServiceEnabled
import com.coni.hyperisle.util.isOverlayPermissionGranted
import com.coni.hyperisle.util.isPostNotificationsEnabled
import com.coni.hyperisle.util.openAppNotificationSettings
import com.coni.hyperisle.util.openAutostartSettings
import com.coni.hyperisle.util.openBatterySettings
import com.coni.hyperisle.util.openNotificationListenerSettings
import com.coni.hyperisle.util.openOverlaySettings



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupHealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // SCROLL BEHAVIOR: Connects the scrollable content to the AppBar
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // --- STATE ---
    // REQUIRED permissions
    var isListenerGranted by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var isOverlayGranted by remember { mutableStateOf(isOverlayPermissionGranted(context)) }
    // RECOMMENDED permissions
    var isPostGranted by remember { mutableStateOf(isPostNotificationsEnabled(context)) }
    var isBatteryOptimized by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var isPhoneCallsGranted by remember { mutableStateOf(CallManager.hasCallPermission(context)) }

    // --- LIFECYCLE ---
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isListenerGranted = isNotificationServiceEnabled(context)
                isOverlayGranted = isOverlayPermissionGranted(context)
                isPostGranted = isPostNotificationsEnabled(context)
                isBatteryOptimized = isIgnoringBatteryOptimizations(context)
                isPhoneCallsGranted = CallManager.hasCallPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- DEVICE CHECKS ---
    val isXiaomi = DeviceUtils.isXiaomi
    val isCompatibleOS = DeviceUtils.isCompatibleOS()
    val osVersionString = DeviceUtils.getHyperOSVersion()
    val isCN = DeviceUtils.isCNRom
    val deviceModel = android.os.Build.MODEL

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), // FIX: Connects scrolling
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.system_setup), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            // --- INFO HEADER ---
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.app_health_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.recents_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- 1. SYSTEM COMPATIBILITY ---
            HealthSectionTitle(stringResource(R.string.setup_health_title))
            HealthGroupCard {
                // Device
                StatusRow(
                    title = android.os.Build.MANUFACTURER.uppercase(java.util.Locale.getDefault()),
                    subtitle = deviceModel,
                    isSuccess = isXiaomi,
                    icon = Icons.Default.Smartphone
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))

                // OS
                StatusRow(
                    title = stringResource(R.string.system_version),
                    subtitle = osVersionString,
                    isSuccess = isCompatibleOS,
                    icon = if (isCompatibleOS) Icons.Default.CheckCircle else Icons.Default.Warning
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            // --- 2. REQUIRED PERMISSIONS ---
            HealthSectionTitle(stringResource(R.string.perm_required_section))
            Text(
                text = stringResource(R.string.perm_required_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
            )
            HealthGroupCard {
                // Notification Listener - REQUIRED
                HealthItem(
                    title = stringResource(R.string.notif_access),
                    subtitle = stringResource(R.string.notif_access_desc),
                    icon = Icons.Default.NotificationsActive,
                    isGranted = isListenerGranted,
                    onClick = { openNotificationListenerSettings(context) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))

                // Overlay - REQUIRED
                HealthItem(
                    title = stringResource(R.string.perm_overlay_title),
                    subtitle = stringResource(R.string.perm_overlay_desc),
                    icon = Icons.Default.Visibility,
                    isGranted = isOverlayGranted,
                    onClick = { openOverlaySettings(context) }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            // --- 3. RECOMMENDED PERMISSIONS ---
            HealthSectionTitle(stringResource(R.string.perm_recommended_section))
            Text(
                text = stringResource(R.string.perm_recommended_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
            )
            HealthGroupCard {
                // POST_NOTIFICATIONS - RECOMMENDED
                HealthItem(
                    title = stringResource(R.string.show_island),
                    subtitle = stringResource(R.string.perm_display_desc),
                    icon = Icons.Default.NotificationsActive,
                    isGranted = isPostGranted,
                    onClick = { openAppNotificationSettings(context) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))

                // Battery Optimization - RECOMMENDED
                HealthItem(
                    title = stringResource(R.string.battery_unrestricted),
                    subtitle = stringResource(R.string.battery_desc),
                    icon = Icons.Default.BatteryAlert,
                    isGranted = isBatteryOptimized,
                    onClick = { openBatterySettings(context) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))

                // Phone Calls - RECOMMENDED (for ending calls from island)
                HealthItem(
                    title = stringResource(R.string.perm_phone_calls_title),
                    subtitle = stringResource(R.string.perm_phone_calls_desc),
                    icon = Icons.Default.Phone,
                    isGranted = isPhoneCallsGranted,
                    onClick = { openPhoneCallsPermissionSettings(context) }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            // --- 4. DEVICE OPTIMIZATION ---
            HealthSectionTitle(stringResource(R.string.device_optimization))
            HealthGroupCard {
                // Autostart
                HealthItem(
                    title = stringResource(R.string.xiaomi_autostart),
                    subtitle = stringResource(R.string.autostart_manual_check),
                    icon = Icons.Default.RestartAlt,
                    isGranted = false,
                    forceAction = true,
                    onClick = { openAutostartSettings(context) }
                )
            }

            // --- 4. WARNINGS ---
            if (isCN && isXiaomi) {
                Spacer(Modifier.height(32.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.warning_cn_rom_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.warning_cn_rom_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

// --- EXPRESSIVE COMPONENTS ---

@Composable
fun HealthSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
    )
}

@Composable
fun HealthGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
fun StatusRow(
    title: String,
    subtitle: String,
    isSuccess: Boolean,
    icon: ImageVector
) {
    // Determines background color (subtle green/red)
    val stateColor = if (isSuccess) Color(0xFF34C759) else MaterialTheme.colorScheme.error

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon Circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(stateColor.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // FIX: Main Icon is NOT tinted (uses standard onSurface color)
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Status Indicator (Right side) - This keeps the Tint
        Icon(
            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = stateColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun HealthItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isGranted: Boolean,
    forceAction: Boolean = false,
    onClick: () -> Unit
) {
    val statusColor = if (isGranted) Color(0xFF34C759) else MaterialTheme.colorScheme.error
    val isActionable = !isGranted || forceAction

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isActionable) { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon Circle
        Box(
            modifier = Modifier
                .size(40.dp)
                // Background relies on status
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // FIX: Main Icon uses standard color, not tinted red/green
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val desc = if (isGranted && !forceAction) stringResource(R.string.status_active) else subtitle
            // Text color can stay status-dependent or neutral. Neutral looks cleaner for text.
            val descColor = if (isGranted && !forceAction) statusColor else MaterialTheme.colorScheme.onSurfaceVariant

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = descColor
            )
        }

        // Right Action Icon
        if (isGranted && !forceAction) {
            Icon(Icons.Default.CheckCircle, null, tint = statusColor, modifier = Modifier.size(24.dp))
        } else {
            Icon(
                imageVector = if (forceAction) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.Error,
                contentDescription = null,
                tint = if (forceAction) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else statusColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Opens the app's permission settings page where user can grant ANSWER_PHONE_CALLS permission.
 */
fun openPhoneCallsPermissionSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to general settings
        try {
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        } catch (_: Exception) { }
    }
}
