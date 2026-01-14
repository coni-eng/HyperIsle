package com.coni.hyperisle.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.debug.HiNotifEvent
import com.coni.hyperisle.debug.NotifOrigin
import com.coni.hyperisle.debug.NotificationCore
import com.coni.hyperisle.debug.RouteHint
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.util.OverlayPermissionHelper
import java.util.UUID



/**
 * Debug-only Notification Lab screen for testing notification pipeline.
 * Allows sending synthetic Telegram/WhatsApp notifications to test routing and rendering.
 * 
 * This screen should ONLY be accessible in debug builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationLabScreen(
    onBack: () -> Unit
) {
    // Guard: Only show in debug builds
    if (!BuildConfig.DEBUG) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // State for form inputs
    var selectedApp by remember { mutableStateOf(TestApp.TELEGRAM) }
    var customPackage by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Bekir") }
    var text by remember { mutableStateOf("selam") }
    var bigTextEnabled by remember { mutableStateOf(false) }
    var bigText by remember { mutableStateOf("Bu uzun bir mesajdır. Birden fazla satır içerir ve BigText stilinde gösterilir.") }
    var canReply by remember { mutableStateOf(true) }
    var overrideSelectedApps by remember { mutableStateOf(true) }
    var routeHint by remember { mutableStateOf(RouteHint.AUTO) }
    
    // Track current conversation for UPDATE functionality
    var currentConversationId by remember { mutableStateOf("debug_thread_1") }
    var currentMessageId by remember { mutableStateOf<String?>(null) }
    var messageCounter by remember { mutableIntStateOf(0) }
    
    // Result state
    var lastResult by remember { mutableStateOf<String?>(null) }
    
    // Check overlay permission
    val hasOverlayPermission = remember { OverlayPermissionHelper.hasOverlayPermission(context) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Notification Lab", fontWeight = FontWeight.Bold)
                        Text(
                            "Debug Only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Warning banner
            if (!hasOverlayPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            "Overlay permission not granted. Islands won't render.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            // App Selection
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Source App", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TestApp.entries.forEach { app ->
                            FilterChip(
                                selected = selectedApp == app,
                                onClick = { selectedApp = app },
                                label = { Text(app.label) },
                                leadingIcon = if (selectedApp == app) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                    }
                    
                    if (selectedApp == TestApp.CUSTOM) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customPackage,
                            onValueChange = { customPackage = it },
                            label = { Text("Package Name") },
                            placeholder = { Text("com.example.app") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
            
            // Message Content
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Message Content", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title (Sender)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Message Text") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Use BigText")
                        Switch(checked = bigTextEnabled, onCheckedChange = { bigTextEnabled = it })
                    }
                    
                    if (bigTextEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = bigText,
                            onValueChange = { bigText = it },
                            label = { Text("Big Text") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )
                    }
                }
            }
            
            // Options
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Options", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Can Reply", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Show reply action",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = canReply, onCheckedChange = { canReply = it })
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Override Selected Apps", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Bypass 'selected apps' filter",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = overrideSelectedApps, onCheckedChange = { overrideSelectedApps = it })
                    }
                }
            }
            
            // Route Hint
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Route Hint", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    RouteHint.entries.forEach { hint ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = routeHint == hint,
                                onClick = { routeHint = hint }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(hint.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    getRouteHintDescription(hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            // Action Buttons
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Actions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Send NEW message
                        Button(
                            onClick = {
                                messageCounter++
                                val newMessageId = UUID.randomUUID().toString()
                                currentMessageId = newMessageId
                                
                                val event = buildEvent(
                                    selectedApp = selectedApp,
                                    customPackage = customPackage,
                                    title = title,
                                    text = text,
                                    bigText = if (bigTextEnabled) bigText else null,
                                    canReply = canReply,
                                    overrideSelectedApps = overrideSelectedApps,
                                    routeHint = routeHint,
                                    conversationId = currentConversationId,
                                    messageId = newMessageId
                                )
                                
                                HiLog.d(HiLog.TAG_NOTIF, "EVT=DEBUG_LAB_NOTIF_SEND pkg=${event.sourcePackage} " +
                                    "title=${event.title} canReply=${event.canReply} routeHint=${event.routeHint}")
                                
                                val result = NotificationCore.ingest(context, event)
                                lastResult = "NEW: ${result.chosen} (suppressed: ${result.suppressedReason ?: "none"})"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Send NEW")
                        }
                        
                        // Send UPDATE message
                        Button(
                            onClick = {
                                val event = buildEvent(
                                    selectedApp = selectedApp,
                                    customPackage = customPackage,
                                    title = title,
                                    text = "$text (updated #$messageCounter)",
                                    bigText = if (bigTextEnabled) bigText else null,
                                    canReply = canReply,
                                    overrideSelectedApps = overrideSelectedApps,
                                    routeHint = routeHint,
                                    conversationId = currentConversationId,
                                    messageId = currentMessageId ?: UUID.randomUUID().toString()
                                )
                                
                                HiLog.d(HiLog.TAG_NOTIF, "EVT=DEBUG_LAB_NOTIF_UPDATE pkg=${event.sourcePackage} " +
                                    "msgId=${event.messageId.take(8)}")
                                
                                val result = NotificationCore.ingest(context, event)
                                lastResult = "UPDATE: ${result.chosen}"
                            },
                            modifier = Modifier.weight(1f),
                            enabled = currentMessageId != null
                        ) {
                            Icon(Icons.Default.Update, null, Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("UPDATE")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Send STACK (3 messages)
                        OutlinedButton(
                            onClick = {
                                repeat(3) { i ->
                                    messageCounter++
                                    val event = buildEvent(
                                        selectedApp = selectedApp,
                                        customPackage = customPackage,
                                        title = title,
                                        text = "$text #$messageCounter",
                                        bigText = null,
                                        canReply = canReply,
                                        overrideSelectedApps = overrideSelectedApps,
                                        routeHint = routeHint,
                                        conversationId = currentConversationId,
                                        messageId = UUID.randomUUID().toString()
                                    )
                                    NotificationCore.ingest(context, event)
                                }
                                lastResult = "STACK: Sent 3 messages"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Layers, null, Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("STACK 3")
                        }
                        
                        // Dismiss
                        OutlinedButton(
                            onClick = {
                                NotificationCore.dismissIsland()
                                lastResult = "DISMISS: All islands"
                                currentMessageId = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Dismiss")
                        }
                    }
                }
            }
            
            // Result display
            if (lastResult != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, null)
                        Text(lastResult!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            
            // Current state info
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Debug State", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("ConversationID: $currentConversationId", style = MaterialTheme.typography.bodySmall)
                    Text("MessageID: ${currentMessageId?.take(8) ?: "null"}", style = MaterialTheme.typography.bodySmall)
                    Text("Counter: $messageCounter", style = MaterialTheme.typography.bodySmall)
                    Text("Overlay Permission: $hasOverlayPermission", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Test app presets for quick selection.
 */
enum class TestApp(val packageName: String, val label: String) {
    TELEGRAM("org.telegram.messenger", "Telegram"),
    WHATSAPP("com.whatsapp", "WhatsApp"),
    CUSTOM("", "Custom")
}

/**
 * Build a HiNotifEvent from the form inputs.
 */
private fun buildEvent(
    selectedApp: TestApp,
    customPackage: String,
    title: String,
    text: String,
    bigText: String?,
    canReply: Boolean,
    overrideSelectedApps: Boolean,
    routeHint: RouteHint,
    conversationId: String,
    messageId: String
): HiNotifEvent {
    val packageName = if (selectedApp == TestApp.CUSTOM) customPackage else selectedApp.packageName
    val appLabel = if (selectedApp == TestApp.CUSTOM) {
        customPackage.substringAfterLast(".")
    } else {
        selectedApp.label
    }
    
    return HiNotifEvent(
        sourcePackage = packageName,
        appLabel = appLabel,
        title = title,
        text = text,
        bigText = bigText,
        whenMs = System.currentTimeMillis(),
        conversationId = conversationId,
        messageId = messageId,
        canReply = canReply,
        hasActions = canReply,
        importance = 4,
        category = "msg",
        isGroup = false,
        groupKey = null,
        routeHint = routeHint,
        origin = NotifOrigin.DEBUG_LAB,
        overrideSelectedAppsFilter = overrideSelectedApps
    )
}

/**
 * Get description text for route hint options.
 */
private fun getRouteHintDescription(hint: RouteHint): String {
    return when (hint) {
        RouteHint.AUTO -> "Normal routing decision"
        RouteHint.FORCE_APP_OVERLAY -> "Force APP_OVERLAY route"
        RouteHint.FORCE_SUPPRESS_MIUI_BRIDGE -> "Suppress MIUI bridge, use APP_OVERLAY"
        RouteHint.FORCE_NONE -> "Don't show, only log"
    }
}
