package com.coni.hyperisle.ui.screens.home

import com.coni.hyperisle.util.HiLog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.PermissionRegistry
import com.coni.hyperisle.ui.AppListViewModel
import com.coni.hyperisle.ui.components.AccessibilityBanner
import com.coni.hyperisle.ui.components.AppConfigBottomSheet
import com.coni.hyperisle.ui.components.SetupBanner
import com.coni.hyperisle.util.isAccessibilityServiceEnabled
import com.coni.hyperisle.util.openAccessibilitySettings
import kotlinx.coroutines.launch



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppListViewModel = viewModel(),
    onSettingsClick: () -> Unit,
    onNavConfigClick: (String) -> Unit,
    onSetupClick: () -> Unit = onSettingsClick
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }
    
    var selectedTab by remember { mutableIntStateOf(0) }

    val activeApps by viewModel.activeAppsState.collectAsState()
    val libraryApps by viewModel.libraryAppsState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // State to hold the app being configured
    var configApp by remember { mutableStateOf<com.coni.hyperisle.ui.AppInfo?>(null) }
    
    // --- PERMISSION BANNER STATE ---
    var missingRequiredCount by remember { mutableStateOf(PermissionRegistry.getMissingRequiredCount(context)) }
    val snoozeUntil by preferences.permissionBannerSnoozeUntilFlow.collectAsState(initial = 0L)
    var bannerDismissedThisSession by remember { mutableStateOf(false) }
    
    // --- ACCESSIBILITY BANNER STATE ---
    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    val accessibilitySnoozeUntil by preferences.accessibilityBannerSnoozeUntilFlow.collectAsState(initial = 0L)
    var accessibilityBannerDismissedThisSession by remember { mutableStateOf(false) }
    
    // Recalculate missing permissions on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                missingRequiredCount = PermissionRegistry.getMissingRequiredCount(context)
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // Banner visibility logic:
    // Show only if: missing required > 0 AND not snoozed AND not dismissed this session
    val isSnoozed = System.currentTimeMillis() < snoozeUntil
    val showBanner = missingRequiredCount > 0 && !isSnoozed && !bannerDismissedThisSession
    
    // Accessibility banner visibility:
    // Show only if: accessibility not enabled AND required permissions are OK AND not snoozed AND not dismissed
    val isAccessibilitySnoozed = System.currentTimeMillis() < accessibilitySnoozeUntil
    val showAccessibilityBanner = !isAccessibilityEnabled && 
        missingRequiredCount == 0 && 
        !isAccessibilitySnoozed && 
        !accessibilityBannerDismissedThisSession
    
    // Debug log for missing permissions
    if (BuildConfig.DEBUG && missingRequiredCount > 0) {
        HiLog.d("HomeScreen", "event=missingRequiredPermissions count=$missingRequiredCount")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(id = R.string.app_name), fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Icon(if(selectedTab==0) Icons.Filled.ToggleOn else Icons.Outlined.ToggleOff, null) },
                    label = { Text(stringResource(R.string.tab_active)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = { Icon(if(selectedTab==1) Icons.Filled.Apps else Icons.Outlined.Apps, null) },
                    label = { Text(stringResource(R.string.tab_library)) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // --- SETUP BANNER (only for REQUIRED permissions) ---
            if (showBanner) {
                SetupBanner(
                    missingCount = missingRequiredCount,
                    onFixNow = { onSetupClick() },
                    onLater = {
                        scope.launch { preferences.snoozePermissionBanner() }
                        bannerDismissedThisSession = true
                    }
                )
            }
            
            // --- ACCESSIBILITY BANNER (recommended for smart overlay hiding) ---
            if (showAccessibilityBanner) {
                AccessibilityBanner(
                    onEnable = { openAccessibilitySettings(context) },
                    onDismiss = {
                        scope.launch { preferences.snoozeAccessibilityBanner() }
                        accessibilityBannerDismissedThisSession = true
                    }
                )
            }
            
            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    ActiveAppsPage(activeApps, isLoading, viewModel) { configApp = it }
                } else {
                    LibraryPage(libraryApps, isLoading, viewModel) { configApp = it }
                }
            }
        }
    }

    // --- BOTTOM SHEET HANDLER (CRASH FIX) ---
    if (configApp != null) {
        // Capture the non-null app object locally
        val safeApp = configApp!!

        AppConfigBottomSheet(
            app = safeApp,
            viewModel = viewModel,
            onDismiss = { configApp = null },
            onNavConfigClick = {
                // 1. Trigger Navigation using the SAFE local variable
                onNavConfigClick(safeApp.packageName)
                // 2. Close Sheet
                configApp = null
            }
        )
    }
}