package com.coni.hyperisle.ui.screens.onboarding

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.coni.hyperisle.R
import com.coni.hyperisle.models.AnchorVisibilityMode
import com.coni.hyperisle.models.PermissionRegistry
import com.coni.hyperisle.util.DeviceUtils
import com.coni.hyperisle.util.toBitmap
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onAnchorModeSelected: (AnchorVisibilityMode) -> Unit = {}
) {
    // 7 Pages: Welcome, Explanation, Privacy, Compatibility, Permissions, Anchor, Features
    val pagerState = rememberPagerState(pageCount = { 7 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Handle Hardware Back Button
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    // --- State ---
    // Track permission updates to refresh UI
    var permissionsRefreshKey by remember { mutableStateOf(0) }
    
    // Check permissions
    // We use a derived state or just recompose when key changes
    val hasRequiredPermissions = remember(permissionsRefreshKey) {
        !PermissionRegistry.hasAnyMissingRequired(context)
    }

    // --- Compatibility Logic ---
    val isXiaomi = remember { DeviceUtils.isXiaomi }
    val isCompatibleOS = remember { DeviceUtils.isCompatibleOS() }
    val canProceedCompat = isXiaomi && isCompatibleOS

    // --- Lifecycle Observer ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Show bottom bar on all pages except first (optional choice, current implementation hides it on page 0?)
            // Actually original code showed it if pagerState.currentPage > 0
            if (pagerState.currentPage > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Progress Indicator
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(6) { iteration -> // 6 dots for 7 pages? Original had logic pagerState.currentPage - 1
                            // Original logic: "currentPage - 1 == iteration".
                            // If we have 7 pages (indices 0..6).
                            // If we start showing nav at index 1.
                            // Index 1 -> dot 0. Index 6 -> dot 5.
                            // So we need 6 dots.
                            val active = (pagerState.currentPage - 1) == iteration
                            val width = if (active) 32.dp else 10.dp
                            val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            Box(
                                modifier = Modifier
                                    .height(10.dp)
                                    .width(width)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${pagerState.currentPage} / 6",
                            style = MaterialTheme.typography.labelSmall,        
                            color = MaterialTheme.colorScheme.onSurfaceVariant  
                        )
                    }

                    // Blocking Logic
                    val canProceed = when (pagerState.currentPage) {
                        3 -> canProceedCompat // Compatibility
                        4 -> hasRequiredPermissions // Unified Permissions
                        else -> true
                    }
                    val isLastPage = pagerState.currentPage == 6

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.back))
                        }

                        Button(
                            onClick = {
                                if (isLastPage) onFinish()
                                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            },
                            enabled = canProceed,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(
                                text = stringResource(if (isLastPage) R.string.finish else R.string.onboarding_continue),
                                style = MaterialTheme.typography.labelLarge,        
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            userScrollEnabled = false
        ) { page ->
            when (page) {
                0 -> WelcomePage(
                    stepIndex = 1,
                    stepCount = 7,
                    onStartClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                )
                1 -> ExplanationPage(stepIndex = 2, stepCount = 7)
                2 -> PrivacyPage(stepIndex = 3, stepCount = 7)
                3 -> CompatibilityPage(stepIndex = 4, stepCount = 7)
                4 -> UnifiedPermissionsPage(
                    stepIndex = 5,
                    stepCount = 7,
                    onPermissionsUpdated = { permissionsRefreshKey++ }
                )
                5 -> AnchorSelectionPage(
                    stepIndex = 6,
                    stepCount = 7,
                    onModeSelected = onAnchorModeSelected
                )
                6 -> FeatureOverviewPage(
                    stepIndex = 7, 
                    stepCount = 7
                )
            }
        }
    }
}

// ==========================================
//              PAGE COMPOSABLES
// ==========================================

@Composable
fun WelcomePage(stepIndex: Int, stepCount: Int, onStartClick: () -> Unit) {
    val context = LocalContext.current
    val appIconBitmap = remember(context) {
        try { context.packageManager.getApplicationIcon(context.packageName).toBitmap().asImageBitmap() } catch (e: Exception) { null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        StepChip(stepIndex = stepIndex, stepCount = stepCount)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (appIconBitmap != null) {
                Image(
                    bitmap = appIconBitmap,
                    contentDescription = stringResource(R.string.logo_desc),
                    modifier = Modifier.size(140.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = stringResource(R.string.logo_desc),
                    modifier = Modifier.size(140.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 26.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            IslandPreviewCard()

            Spacer(modifier = Modifier.height(16.dp))

            FeatureChips()
        }
        Button(
            onClick = onStartClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(
                stringResource(R.string.get_started),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// Shared Layout
@Composable
fun OnboardingPageLayout(
    stepIndex: Int,
    stepCount: Int,
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    compactHeader: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val topSpacer = if (compactHeader) 8.dp else 16.dp
    val iconOuterSize = if (compactHeader) 120.dp else 140.dp
    val iconInnerSize = if (compactHeader) 92.dp else 104.dp
    val titleSpacer = if (compactHeader) 18.dp else 24.dp
    val contentSpacer = if (compactHeader) 24.dp else 32.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(topSpacer))
        StepChip(stepIndex = stepIndex, stepCount = stepCount)
        Spacer(modifier = Modifier.height(titleSpacer))

        val haloBrush = Brush.radialGradient(
            colors = listOf(iconColor.copy(alpha = 0.22f), Color.Transparent)
        )
        Box(
            modifier = Modifier
                .size(iconOuterSize)
                .background(haloBrush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(iconInnerSize)
                    .background(iconColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = iconColor
                )
            }
        }

        Spacer(modifier = Modifier.height(titleSpacer))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 26.sp
        )

        Spacer(modifier = Modifier.height(contentSpacer))

        // Content Area
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun ExplanationPage(stepIndex: Int, stepCount: Int) {
    OnboardingPageLayout(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.how_it_works),
        description = stringResource(R.string.how_it_works_desc),
        icon = Icons.Default.Architecture,
        iconColor = MaterialTheme.colorScheme.tertiary
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Construction, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    stringResource(R.string.beta_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
fun PrivacyPage(stepIndex: Int, stepCount: Int) {
    OnboardingPageLayout(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.privacy_title),
        description = stringResource(R.string.privacy_desc),
        icon = Icons.Default.Security,
        iconColor = MaterialTheme.colorScheme.primary
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.WifiOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.privacy_card_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.privacy_card_desc), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun CompatibilityPage(stepIndex: Int, stepCount: Int) {
    val isXiaomi = DeviceUtils.isXiaomi
    val isCompatibleOS = DeviceUtils.isCompatibleOS()
    val isCN = DeviceUtils.isCNRom
    val osVersion = DeviceUtils.getHyperOSVersion()
    val deviceName = DeviceUtils.getDeviceMarketName()

    val (icon, color, titleRes, descRes) = when {
        !isXiaomi -> Quad(Icons.Default.Cancel, MaterialTheme.colorScheme.error, R.string.unsupported_device, R.string.req_xiaomi)
        !isCompatibleOS -> Quad(Icons.Default.Cancel, MaterialTheme.colorScheme.error, R.string.unsupported_device, R.string.req_hyperos)
        else -> Quad(Icons.Default.CheckCircle, Color(0xFF34C759), R.string.device_compatible, R.string.compatible_msg)
    }

    OnboardingPageLayout(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(titleRes),
        description = stringResource(descRes),
        icon = icon,
        iconColor = color
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Smartphone, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(text = Build.MANUFACTURER.uppercase(java.util.Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(text = deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(text = stringResource(R.string.system_version), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(text = osVersion, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (isCN && isXiaomi) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.warning_cn_rom_title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Helper
data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun StepChip(stepIndex: Int, stepCount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = "$stepIndex/$stepCount",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IslandPreviewCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.status_now_playing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.music_island_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.status_now_playing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureChips() {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FeatureChip(
            label = stringResource(R.string.smart_priority_summary_title),
            icon = Icons.Default.Bolt,
            tint = MaterialTheme.colorScheme.primary
        )
        FeatureChip(
            label = stringResource(R.string.summary_title),
            icon = Icons.Default.NotificationsActive,
            tint = MaterialTheme.colorScheme.secondary
        )
        FeatureChip(
            label = stringResource(R.string.music_island_title),
            icon = Icons.Default.MusicNote,
            tint = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun FeatureChip(label: String, icon: ImageVector, tint: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
