package com.coni.hyperisle.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.ui.components.CollapsedPillIsland
import com.coni.hyperisle.ui.components.IslandAction
import com.coni.hyperisle.ui.components.ModernCallIsland
import com.coni.hyperisle.ui.components.ModernIslandColors
import com.coni.hyperisle.ui.components.ModernPillIsland
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dev-only screen for previewing and testing the new iOS-like island styles.
 * Only accessible in debug builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IslandStylePreviewScreen(onBack: () -> Unit) {
    if (!BuildConfig.DEBUG) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Preview states
    var notificationExpanded by remember { mutableStateOf(false) }
    var callExpanded by remember { mutableStateOf(true) }
    var showCollapsed by remember { mutableStateOf(false) }
    var autoAnimate by remember { mutableStateOf(false) }

    // Auto-animation effect
    LaunchedEffect(autoAnimate) {
        if (autoAnimate) {
            while (autoAnimate) {
                delay(2000)
                notificationExpanded = !notificationExpanded
                delay(2000)
                callExpanded = !callExpanded
                delay(1000)
                showCollapsed = !showCollapsed
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Island Style Preview") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Controls Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Preview Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-animate")
                        Switch(
                            checked = autoAnimate,
                            onCheckedChange = { autoAnimate = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { notificationExpanded = !notificationExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (notificationExpanded) "Collapse Notif" else "Expand Notif")
                        }
                        OutlinedButton(
                            onClick = { callExpanded = !callExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (callExpanded) "Collapse Call" else "Expand Call")
                        }
                    }
                }
            }

            HorizontalDivider()

            // Section: Modern Pill Island
            Text(
                text = "ModernPillIsland",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Dark preview background (simulates status bar area)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF000000))
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                ModernPillIsland(
                    title = "Michel",
                    subtitle = "Hello World!",
                    appIconPlaceholder = Icons.AutoMirrored.Filled.Message,
                    timeLabel = "now",
                    actions = listOf(
                        IslandAction(
                            icon = Icons.Default.Settings,
                            contentDescription = "Settings",
                            onClick = { }
                        ),
                        IslandAction(
                            icon = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            backgroundColor = ModernIslandColors.rejectRed,
                            onClick = { }
                        )
                    ),
                    isExpanded = notificationExpanded,
                    onExpandToggle = { notificationExpanded = !notificationExpanded }
                )
            }

            // Collapsed state preview
            Text(
                text = "Collapsed State",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF000000))
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                CollapsedPillIsland(
                    appIconPlaceholder = Icons.AutoMirrored.Filled.Message,
                    indicatorColor = ModernIslandColors.acceptGreen,
                    onClick = { showCollapsed = !showCollapsed }
                )
            }

            HorizontalDivider()

            // Section: Modern Call Island
            Text(
                text = "ModernCallIsland",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF000000))
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                ModernCallIsland(
                    callerDisplay = "Aga Orlova",
                    callDurationText = "01:23",
                    isExpanded = callExpanded,
                    onAccept = { },
                    onReject = { },
                    onExpandToggle = { callExpanded = !callExpanded }
                )
            }

            // Compact call variant
            Text(
                text = "Compact Call (Collapsed)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF000000))
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                ModernCallIsland(
                    callerDisplay = "John Doe",
                    callDurationText = "Ringing",
                    isExpanded = false,
                    onAccept = { },
                    onReject = { },
                    onExpandToggle = null
                )
            }

            HorizontalDivider()

            // Section: Variations
            Text(
                text = "Notification Variations",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // No actions variant
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF000000))
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                ModernPillIsland(
                    title = "Calendar",
                    subtitle = "Meeting in 15 minutes",
                    appIconPlaceholder = Icons.Default.Notifications,
                    timeLabel = "2m",
                    actions = emptyList(),
                    isExpanded = false,
                    onExpandToggle = null
                )
            }

            // Single action variant
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF000000))
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                ModernPillIsland(
                    title = "Download Complete",
                    subtitle = "file.zip - 24.5 MB",
                    appIconPlaceholder = Icons.Default.Notifications,
                    actions = listOf(
                        IslandAction(
                            icon = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            onClick = { }
                        )
                    ),
                    isExpanded = true,
                    onExpandToggle = null
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
