package com.coni.hyperisle.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coni.hyperisle.R
import com.coni.hyperisle.models.NotificationStatus
import com.coni.hyperisle.ui.AppListViewModel
import com.coni.hyperisle.util.HiLog



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationManagementAppsScreen(
    onBack: () -> Unit,
    viewModel: AppListViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // v1.0.2: Show ALL active (bridged) apps in this screen for shade cancel management
    val activeApps by viewModel.activeAppsState.collectAsState()

    // v1.0.0: Track which app user navigated to settings for (self-reported status)
    var pendingConfirmationApp by remember { mutableStateOf<com.coni.hyperisle.ui.AppInfo?>(null) }
    var showConfirmationDialog by remember { mutableStateOf(false) }

    // Track when user returns from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingConfirmationApp != null) {
                showConfirmationDialog = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Confirmation dialog
    if (showConfirmationDialog && pendingConfirmationApp != null) {
        val app = pendingConfirmationApp!!
        AlertDialog(
            onDismissRequest = {
                showConfirmationDialog = false
                pendingConfirmationApp = null
            },
            title = { Text(stringResource(R.string.shade_cancel_confirm_dialog_title)) },
            text = { Text(stringResource(R.string.shade_cancel_confirm_dialog_message, app.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setNotificationStatus(app.packageName, NotificationStatus.DISABLED)
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=SHADE_CANCEL_STATUS_SET status=DISABLED pkg=${app.packageName}")
                    showConfirmationDialog = false
                    pendingConfirmationApp = null
                }) {
                    Text(stringResource(R.string.shade_cancel_confirm_yes))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.setNotificationStatus(app.packageName, NotificationStatus.ENABLED)
                        HiLog.d(HiLog.TAG_ISLAND, "EVT=SHADE_CANCEL_STATUS_SET status=ENABLED pkg=${app.packageName}")
                        showConfirmationDialog = false
                        pendingConfirmationApp = null
                    }) {
                        Text(stringResource(R.string.shade_cancel_confirm_no))
                    }
                    TextButton(onClick = {
                        showConfirmationDialog = false
                        pendingConfirmationApp = null
                    }) {
                        Text(stringResource(R.string.shade_cancel_confirm_skip))
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notification_management_apps_screen_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (activeApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.shade_cancel_empty_list),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.shade_cancel_empty_list_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // Info card explaining why this setting is needed
                item {
                    ShadeCancelInfoCard()
                }

                // List of shade cancel enabled apps
                items(activeApps, key = { it.packageName }) { appInfo ->
                    ShadeCancelAppItem(
                        appInfo = appInfo,
                        viewModel = viewModel,
                        onTap = {
                            // v1.0.0: Track app and open notification settings
                            pendingConfirmationApp = appInfo
                            HiLog.d(HiLog.TAG_ISLAND, "EVT=SHADE_CANCEL_SETTINGS_TAP pkg=${appInfo.packageName}")
                            openAppNotificationSettings(context, appInfo.packageName)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Info card explaining why users need to disable notifications for apps in this list.
 * Rationale: MIUI/HyperOS often forces system notifications to show, causing duplicate islands.
 */
@Composable
private fun ShadeCancelInfoCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shade_cancel_screen_info_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        stringResource(R.string.shade_cancel_screen_info_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/**
 * App item that shows notification status and opens system notification settings on tap.
 * 
 * v1.0.0: Uses self-reported status since Android doesn't provide API to check other apps.
 * User confirms status after returning from system notification settings.
 */
@Composable
private fun ShadeCancelAppItem(
    appInfo: com.coni.hyperisle.ui.AppInfo,
    viewModel: AppListViewModel,
    onTap: () -> Unit
) {
    val notificationStatus by viewModel.getNotificationStatusFlow(appInfo.packageName)
        .collectAsState(initial = NotificationStatus.UNKNOWN)

    // Status indicator styling based on self-reported status
    val (statusIcon, statusText, statusColor) = when (notificationStatus) {
        NotificationStatus.DISABLED -> Triple(
            Icons.Default.Check,
            stringResource(R.string.shade_cancel_status_disabled),
            MaterialTheme.colorScheme.primary
        )
        NotificationStatus.ENABLED -> Triple(
            Icons.Default.Close,
            stringResource(R.string.shade_cancel_status_enabled),
            MaterialTheme.colorScheme.error
        )
        NotificationStatus.UNKNOWN -> Triple(
            Icons.Default.QuestionMark,
            stringResource(R.string.shade_cancel_status_unknown),
            MaterialTheme.colorScheme.outline
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable(onClick = onTap)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                bitmap = appInfo.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    appInfo.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                // Self-reported status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
            }

            // Chevron to indicate tappable
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.shade_cancel_tap_to_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = 180f }
            )
        }
    }
}

/**
 * Opens the system notification settings for a specific app.
 * Uses ACTION_APP_NOTIFICATION_SETTINGS as primary intent with fallback to app details page.
 */
private fun openAppNotificationSettings(context: Context, packageName: String) {
    try {
        // Primary: Direct notification settings (API 26+)
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        HiLog.w(HiLog.TAG_ISLAND, "EVT=SHADE_CANCEL_SETTINGS_FALLBACK pkg=$packageName reason=${e.message}")
        try {
            // Fallback: App details page
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        } catch (e2: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "EVT=SHADE_CANCEL_SETTINGS_FAIL pkg=$packageName reason=${e2.message}")
        }
    }
}
