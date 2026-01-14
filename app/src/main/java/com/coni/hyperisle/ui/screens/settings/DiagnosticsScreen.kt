package com.coni.hyperisle.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.debug.IslandRuntimeDump
import com.coni.hyperisle.debug.LegacyPathTelemetry
import com.coni.hyperisle.models.AnchorVisibilityMode
import com.coni.hyperisle.overlay.anchor.CutoutHelper
import com.coni.hyperisle.util.DiagnosticsManager
import com.coni.hyperisle.util.NotificationListenerDiagnostics
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onIslandStylePreviewClick: (() -> Unit)? = null,
    onNotificationLabClick: (() -> Unit)? = null
) {
    if (!BuildConfig.DEBUG) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var isSessionActive by remember { mutableStateOf(DiagnosticsManager.isSessionActive()) }
    var sessionId by remember { mutableStateOf(DiagnosticsManager.currentSessionId) }
    var logFileSize by remember { mutableLongStateOf(DiagnosticsManager.getLogFileSize()) }
    var logLineCount by remember { mutableIntStateOf(DiagnosticsManager.getLogLineCount()) }
    var recentLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    
    val appPreferences = remember { AppPreferences(context) }
    val anchorModeEnabled by appPreferences.anchorModeEnabledFlow.collectAsState(initial = false)
    val anchorVisibilityMode by appPreferences.anchorVisibilityModeFlow.collectAsState(initial = AnchorVisibilityMode.TRIGGERED_ONLY)
    val coroutineScope = rememberCoroutineScope()

    // Refresh stats periodically when session is active
    LaunchedEffect(isSessionActive) {
        while (isSessionActive) {
            delay(2000)
            logFileSize = DiagnosticsManager.getLogFileSize()
            logLineCount = DiagnosticsManager.getLogLineCount()
            recentLogs = DiagnosticsManager.readRecentLogs(50)
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        recentLogs = DiagnosticsManager.readRecentLogs(50)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Session Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSessionActive) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSessionActive)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outline
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSessionActive) "Session Active" else "No Active Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    if (sessionId != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ID: $sessionId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isSessionActive) {
                            Button(
                                onClick = {
                                    DiagnosticsManager.stopSession()
                                    isSessionActive = false
                                    sessionId = null
                                    recentLogs = DiagnosticsManager.readRecentLogs(50)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Stop Session")
                            }
                        } else {
                            Button(
                                onClick = {
                                    val newSessionId = DiagnosticsManager.startSession()
                                    isSessionActive = DiagnosticsManager.isSessionActive()
                                    sessionId = newSessionId
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Session")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Log Statistics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(label = "File Size", value = formatFileSize(logFileSize))
                        StatItem(label = "Entries", value = logLineCount.toString())
                        StatItem(label = "Max Size", value = "5 MB")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        val zipFile = DiagnosticsManager.exportToZip(context)
                        if (zipFile != null) {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    zipFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "HyperIsle Diagnostics")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Export Diagnostics"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export")
                }

                OutlinedButton(
                    onClick = {
                        DiagnosticsManager.clearLogs()
                        logFileSize = 0L
                        logLineCount = 0
                        recentLogs = emptyList()
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Logs")
                }
            }

            // Island Style Preview Button
            if (onIslandStylePreviewClick != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onIslandStylePreviewClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Island Style Preview")
                }
            }
            
            // Notification Lab Button
            if (onNotificationLabClick != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onNotificationLabClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text("🧪 Notification Lab")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Island Runtime Dump Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Island Runtime Dump",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "MIUI-style state machine history (Add/Remove: ${IslandRuntimeDump.getAddRemoveCount()}, State: ${IslandRuntimeDump.getStateCount()}, Overlay: ${IslandRuntimeDump.getOverlayCount()})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val dump = IslandRuntimeDump.dumpToString()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Island Runtime Dump", dump))
                                Toast.makeText(context, "Plain dump copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy Plain", fontSize = 12.sp)
                        }
                        
                        FilledTonalButton(
                            onClick = {
                                val dump = IslandRuntimeDump.dumpToJson()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Island Runtime Dump JSON", dump))
                                Toast.makeText(context, "JSON dump copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy JSON", fontSize = 12.sp)
                        }
                        
                        OutlinedButton(
                            onClick = {
                                IslandRuntimeDump.clear()
                                Toast.makeText(context, "Runtime dump cleared", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Notification Listener Diagnostics Section
            ListenerDiagnosticsCard()

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Anchor Mode Section (Debug)
            AnchorModeCard(
                isEnabled = anchorModeEnabled,
                visibilityMode = anchorVisibilityMode,
                onToggle = { enabled ->
                    coroutineScope.launch {
                        appPreferences.setAnchorModeEnabled(enabled)
                        Toast.makeText(
                            context,
                            if (enabled) "Anchor mode enabled - restart app to apply" else "Anchor mode disabled - restart app to apply",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onVisibilityModeChange = { mode ->
                    coroutineScope.launch {
                        appPreferences.setAnchorVisibilityMode(mode)
                        Toast.makeText(
                            context,
                            "Visibility: ${AnchorVisibilityMode.getDisplayName(mode)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Legacy Path Telemetry Section (Debug)
            LegacyHitsCard(anchorModeEnabled = anchorModeEnabled)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Recent Logs
            Text(
                text = "Recent Logs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (recentLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No logs yet. Start a session to begin logging.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val listState = rememberLazyListState()
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    items(recentLogs.reversed()) { logLine ->
                        LogEntryItem(logLine)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnchorModeCard(
    isEnabled: Boolean,
    visibilityMode: AnchorVisibilityMode,
    onToggle: (Boolean) -> Unit,
    onVisibilityModeChange: (AnchorVisibilityMode) -> Unit
) {
    val context = LocalContext.current
    val cutoutInfo = remember { CutoutHelper.getCutoutInfoOrDefault(context) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Anchor Mode (Experimental)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Camera cutout island always visible",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Cutout info
            Text(
                text = "Cutout: ${cutoutInfo.width}x${cutoutInfo.height} @ centerX=${cutoutInfo.centerX}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (isEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                
                // Visibility Mode Selection
                Text(
                    text = "Ada Görünürlük Modu",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                // Radio button options for visibility mode
                AnchorVisibilityMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = visibilityMode == mode,
                            onClick = { onVisibilityModeChange(mode) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = AnchorVisibilityMode.getDisplayName(mode),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (visibilityMode == mode) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = AnchorVisibilityMode.getDescription(mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Anchor mode enabled",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenerDiagnosticsCard() {
    val context = LocalContext.current
    val listenerConnected by NotificationListenerDiagnostics.listenerConnected.collectAsState()
    val lastConnectedTime by NotificationListenerDiagnostics.lastConnectedTime.collectAsState()
    val lastDisconnectedTime by NotificationListenerDiagnostics.lastDisconnectedTime.collectAsState()
    val activeNotificationCount by NotificationListenerDiagnostics.activeNotificationCount.collectAsState()
    val lastHeartbeatTime by NotificationListenerDiagnostics.lastHeartbeatTime.collectAsState()
    val isRebinding by NotificationListenerDiagnostics.isRebinding.collectAsState()
    
    var isListedInSettings by remember { mutableStateOf(false) }
    var isBatteryOptimized by remember { mutableStateOf(false) }
    var oemHints by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // Refresh diagnostics periodically
    LaunchedEffect(Unit) {
        while (true) {
            isListedInSettings = NotificationListenerDiagnostics.isListedInEnabledListeners(context)
            isBatteryOptimized = NotificationListenerDiagnostics.isBatteryOptimized(context)
            oemHints = NotificationListenerDiagnostics.getOemPermissionHints(context)
            delay(5000)
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (listenerConnected && isListedInSettings && !isBatteryOptimized)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Notification Listener Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "WhatsApp/Telegram notifications require healthy listener",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // Status indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusIndicator(
                    label = "Connected",
                    isOk = listenerConnected,
                    value = if (listenerConnected) "YES" else "NO"
                )
                StatusIndicator(
                    label = "In Settings",
                    isOk = isListedInSettings,
                    value = if (isListedInSettings) "YES" else "NO"
                )
                StatusIndicator(
                    label = "Battery Opt",
                    isOk = !isBatteryOptimized,
                    value = if (isBatteryOptimized) "ON ⚠️" else "OFF ✓"
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Device info
            Text(
                text = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (NotificationListenerDiagnostics.isMiuiDevice()) {
                Text(
                    text = "OS: ${NotificationListenerDiagnostics.getMiuiVersion() ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (activeNotificationCount >= 0) {
                Text(
                    text = "Active Notifications: $activeNotificationCount",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (lastHeartbeatTime > 0) {
                val secondsAgo = (System.currentTimeMillis() - lastHeartbeatTime) / 1000
                Text(
                    text = "Last Heartbeat: ${secondsAgo}s ago",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // OEM hints (show first 2)
            if (oemHints.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "OEM Hints:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                oemHints.take(2).forEach { hint ->
                    Text(
                        text = "• $hint",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        val intent = NotificationListenerDiagnostics.getNotificationListenerSettingsIntent()
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Listener Settings", fontSize = 11.sp)
                }
                
                FilledTonalButton(
                    onClick = {
                        val intent = NotificationListenerDiagnostics.getBatteryOptimizationSettingsIntent(context)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Battery Settings", fontSize = 11.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Rebind button (debug only, use with caution)
            OutlinedButton(
                onClick = {
                    val success = NotificationListenerDiagnostics.attemptRebind(context)
                    Toast.makeText(
                        context,
                        if (success) "Rebind initiated - re-enable in settings" else "Rebind failed",
                        Toast.LENGTH_LONG
                    ).show()
                },
                enabled = !isRebinding,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = if (isRebinding) "Rebinding..." else "Force Rebind (Re-enable Required)",
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(label: String, isOk: Boolean, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isOk)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogEntryItem(jsonLine: String) {
    val parsed = remember(jsonLine) {
        try {
            val json = JSONObject(jsonLine)
            LogEntry(
                time = json.optString("time", "").substringAfter("T").substringBefore("+"),
                tag = json.optString("tag", ""),
                level = json.optString("level", "D"),
                event = json.optString("event", ""),
                session = json.optString("session", "")
            )
        } catch (e: Exception) {
            LogEntry(time = "", tag = "", level = "?", event = jsonLine, session = "")
        }
    }

    val levelColor = when (parsed.level) {
        "E" -> MaterialTheme.colorScheme.error
        "W" -> MaterialTheme.colorScheme.tertiary
        "I" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = parsed.time,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = parsed.level,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = parsed.tag.removePrefix("HI_"),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 10.sp,
            modifier = Modifier.width(48.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = parsed.event,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private data class LogEntry(
    val time: String,
    val tag: String,
    val level: String,
    val event: String,
    val session: String
)

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

@Composable
private fun LegacyHitsCard(anchorModeEnabled: Boolean) {
    var totalHits by remember { mutableIntStateOf(0) }
    var totalBypassed by remember { mutableIntStateOf(0) }
    var notifHits by remember { mutableIntStateOf(0) }
    var notifBypassed by remember { mutableIntStateOf(0) }
    var callHits by remember { mutableIntStateOf(0) }
    var navHits by remember { mutableIntStateOf(0) }
    var recentHits by remember { mutableStateOf<List<LegacyPathTelemetry.LegacyHit>>(emptyList()) }
    
    // Refresh stats periodically
    LaunchedEffect(Unit) {
        while (true) {
            totalHits = LegacyPathTelemetry.getTotalHitCount()
            totalBypassed = LegacyPathTelemetry.getTotalBypassCount()
            notifHits = LegacyPathTelemetry.getHitCount(LegacyPathTelemetry.Feature.NOTIF)
            notifBypassed = LegacyPathTelemetry.getBypassCount(LegacyPathTelemetry.Feature.NOTIF)
            callHits = LegacyPathTelemetry.getHitCount(LegacyPathTelemetry.Feature.CALL)
            navHits = LegacyPathTelemetry.getHitCount(LegacyPathTelemetry.Feature.NAV)
            recentHits = LegacyPathTelemetry.getRecentHits().take(10)
            delay(2000)
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (totalHits > 0 && anchorModeEnabled)
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Legacy Mini Path Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tracks collapsed/mini path hits during Anchor migration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { LegacyPathTelemetry.clear() }
                ) {
                    Text("Clear", fontSize = 11.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = totalHits.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (totalHits > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "Total Hits",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = totalBypassed.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (totalBypassed > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "Bypassed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$notifHits/$notifBypassed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "NOTIF",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = callHits.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "CALL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Recent hits (last 5)
            if (recentHits.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Recent Hits:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                recentHits.take(5).forEach { hit ->
                    Text(
                        text = "${hit.feature} | ${hit.branch} | ${if (hit.bypassed) "BYPASSED→${hit.redirectedTo}" else "OBSERVED"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (hit.bypassed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
