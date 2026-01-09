package com.coni.hyperisle

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.data.db.AppDatabase
import com.coni.hyperisle.data.model.HyperIsleBackup
import com.coni.hyperisle.ui.components.ChangelogDialog
import com.coni.hyperisle.ui.components.PriorityEducationDialog
import com.coni.hyperisle.ui.screens.home.HomeScreen
import com.coni.hyperisle.ui.screens.onboarding.OnboardingScreen
import com.coni.hyperisle.ui.screens.settings.AppPriorityScreen
import com.coni.hyperisle.ui.screens.settings.BackupSettingsScreen
import com.coni.hyperisle.ui.screens.settings.BlocklistAppListScreen
import com.coni.hyperisle.ui.screens.settings.ChangelogHistoryScreen
import com.coni.hyperisle.ui.screens.settings.GlobalBlocklistScreen
import com.coni.hyperisle.ui.screens.settings.GlobalSettingsScreen
import com.coni.hyperisle.ui.screens.settings.ImportPreviewScreen
import com.coni.hyperisle.ui.screens.settings.InfoScreen
import com.coni.hyperisle.ui.screens.settings.IslandQuickActionsScreen
import com.coni.hyperisle.ui.screens.settings.LicensesScreen
import com.coni.hyperisle.ui.screens.settings.MusicIslandSettingsScreen
import com.coni.hyperisle.ui.screens.settings.NavCustomizationScreen
import com.coni.hyperisle.ui.screens.settings.PrioritySettingsScreen
import com.coni.hyperisle.ui.screens.settings.SetupHealthScreen
import com.coni.hyperisle.ui.screens.settings.SettingsCustomizationScreen
import com.coni.hyperisle.ui.screens.settings.SmartFeaturesScreen
import com.coni.hyperisle.ui.screens.settings.NotificationSummaryScreenV2       
import com.coni.hyperisle.ui.screens.settings.NotificationManagementScreen      
import com.coni.hyperisle.ui.screens.settings.NotificationManagementAppsScreen  
import com.coni.hyperisle.ui.screens.settings.DiagnosticsScreen
import com.coni.hyperisle.ui.screens.settings.IslandStylePreviewScreen
import com.coni.hyperisle.ui.screens.settings.TypePriorityScreen
import com.coni.hyperisle.ui.theme.HyperIsleTheme
import com.coni.hyperisle.util.BackupManager
import com.coni.hyperisle.worker.NotificationSummaryWorker
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check for quick actions intent
        val openQuickActions = intent?.getBooleanExtra("openQuickActions", false) ?: false
        val quickActionsPackage = intent?.getStringExtra("quickActionsPackage")
        val openNotificationManagement = intent?.getBooleanExtra("openNotificationManagement", false) ?: false
        
        setContent {
            HyperIsleTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainRootNavigation(
                        initialOpenQuickActions = openQuickActions,
                        initialQuickActionsPackage = quickActionsPackage,
                        initialOpenNotificationManagement = openNotificationManagement
                    )
                }
            }
        }
    }
}

enum class Screen(val depth: Int) {
    ONBOARDING(0), HOME(1), INFO(2), SETUP(3), LICENSES(3), BEHAVIOR(3), GLOBAL_SETTINGS(3), HISTORY(3),
    BACKUP(3), IMPORT_PREVIEW(4), // Backup Flow
    NAV_CUSTOMIZATION(4), APP_PRIORITY(4), TYPE_PRIORITY(4), GLOBAL_BLOCKLIST(4), BLOCKLIST_APPS(5),
    MUSIC_ISLAND(3), // Music Island Settings
    SMART_FEATURES(3), // Smart Silence, Focus, Summary settings
    NOTIFICATION_SUMMARY(4), // Summary list screen
    ISLAND_QUICK_ACTIONS(3), // Quick actions for island (mute/block app)
    NOTIFICATION_MANAGEMENT(3), NOTIFICATION_MANAGEMENT_APPS(4), // Notification Management (v0.9.7)
    SETTINGS_CUSTOMIZE(3), // Settings layout customization
    DIAGNOSTICS(3), // Debug-only diagnostics screen
    ISLAND_STYLE_PREVIEW(4) // Debug-only island style preview
}

@Composable
fun MainRootNavigation(
    initialOpenQuickActions: Boolean = false,
    initialQuickActionsPackage: String? = null,
    initialOpenNotificationManagement: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }

    // --- 1. INITIALIZE DB & MANAGER ---
    val database = remember { AppDatabase.getDatabase(context) }
    val backupManager = remember { BackupManager(context, preferences, database) }

    val packageInfo = remember { try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null } }
    @Suppress("DEPRECATION")
    val currentVersionCode = packageInfo?.longVersionCode?.toInt() ?: 0
    val currentVersionName = packageInfo?.versionName ?: "0.3.1"

    // --- 2. ROBUST DATA COLLECTION ---
    val isSetupComplete by produceState<Boolean?>(initialValue = null) {
        preferences.isSetupComplete.collect { value = it }
    }

    val lastSeenVersion by preferences.lastSeenVersion.collectAsState(initial = currentVersionCode)
    val isPriorityEduShown by preferences.isPriorityEduShown.collectAsState(initial = true)

    var currentScreen by remember { mutableStateOf<Screen?>(null) }
    var showChangelog by remember { mutableStateOf(false) }
    var showPriorityEdu by remember { mutableStateOf(false) }
    var navConfigPackage by remember { mutableStateOf<String?>(null) }
    var quickActionsPackage by remember { mutableStateOf(initialQuickActionsPackage) }
    var pendingQuickActions by remember { mutableStateOf(initialOpenQuickActions) }
    var pendingNotificationManagement by remember { mutableStateOf(initialOpenNotificationManagement) }

    // State to hold the parsed backup file before restoring
    var pendingImportBackup by remember { mutableStateOf<HyperIsleBackup?>(null) }

    // Observe summary settings for worker scheduling
    val summaryEnabled by preferences.summaryEnabledFlow.collectAsState(initial = false)
    val summaryHour by preferences.summaryHourFlow.collectAsState(initial = 21)

    // --- 3. ROUTING LOGIC ---
    LaunchedEffect(isSetupComplete) {
        if (isSetupComplete != null) {
            if (currentScreen == null) {
                // Check if we should open Quick Actions directly
                if (pendingNotificationManagement && isSetupComplete == true) {
                    currentScreen = Screen.NOTIFICATION_MANAGEMENT
                    pendingNotificationManagement = false
                } else if (pendingQuickActions && isSetupComplete == true && quickActionsPackage != null) {
                    currentScreen = Screen.ISLAND_QUICK_ACTIONS
                    pendingQuickActions = false
                } else {
                    currentScreen = if (isSetupComplete == true) Screen.HOME else Screen.ONBOARDING
                }
            }

            if (isSetupComplete == true && !pendingQuickActions && !pendingNotificationManagement) {
                // UX: Automatic update popup disabled - users can view changelog via Settings → Version history
                // if (currentVersionCode > lastSeenVersion) {
                //     showChangelog = true
                // } else
                if (!isPriorityEduShown && !showChangelog) {
                    showPriorityEdu = true
                }
            }
        }
    }

    // Schedule/cancel summary worker based on settings
    LaunchedEffect(summaryEnabled, summaryHour) {
        if (summaryEnabled) {
            NotificationSummaryWorker.schedule(context, summaryHour)
        } else {
            NotificationSummaryWorker.cancel(context)
        }
    }

    // --- 4. BACK HANDLER ---
    BackHandler(enabled = currentScreen != Screen.HOME && currentScreen != Screen.ONBOARDING) {
        currentScreen = when (currentScreen) {
            Screen.IMPORT_PREVIEW -> Screen.BACKUP
            Screen.BACKUP -> Screen.INFO
            Screen.BLOCKLIST_APPS -> Screen.GLOBAL_BLOCKLIST
            Screen.GLOBAL_BLOCKLIST -> Screen.INFO
            Screen.NAV_CUSTOMIZATION -> if (navConfigPackage != null) Screen.HOME else Screen.GLOBAL_SETTINGS
            Screen.GLOBAL_SETTINGS -> Screen.INFO
            Screen.APP_PRIORITY, Screen.TYPE_PRIORITY -> Screen.BEHAVIOR
            Screen.HISTORY -> Screen.INFO
            Screen.BEHAVIOR, Screen.SETUP, Screen.LICENSES, Screen.MUSIC_ISLAND, Screen.SMART_FEATURES, Screen.NOTIFICATION_MANAGEMENT -> Screen.INFO
            Screen.NOTIFICATION_SUMMARY -> Screen.SMART_FEATURES
            Screen.NOTIFICATION_MANAGEMENT_APPS -> Screen.NOTIFICATION_MANAGEMENT
            Screen.INFO -> Screen.HOME
            Screen.ISLAND_STYLE_PREVIEW -> Screen.DIAGNOSTICS
            else -> Screen.HOME
        }
    }

    // --- 5. RENDER SCREENS ---
    if (isSetupComplete == null || currentScreen == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        AnimatedContent(
            targetState = currentScreen!!,
            transitionSpec = {
                if (targetState.depth > initialState.depth) {
                    (slideInHorizontally { width -> width } + fadeIn(tween(400))).togetherWith(slideOutHorizontally { width -> -width / 3 } + fadeOut(tween(400)))
                } else {
                    (slideInHorizontally { width -> -width } + fadeIn(tween(400))).togetherWith(slideOutHorizontally { width -> width / 3 } + fadeOut(tween(400)))
                } using SizeTransform(clip = false)
            },
            label = "ScreenTransition"
        ) { target ->
            when (target) {
                Screen.ONBOARDING -> OnboardingScreen {
                    scope.launch {
                        preferences.setSetupComplete(true)
                        preferences.setLastSeenVersion(currentVersionCode)
                        preferences.setPriorityEduShown(true)
                        currentScreen = Screen.HOME
                    }
                }
                Screen.HOME -> HomeScreen(
                    onSettingsClick = { currentScreen = Screen.INFO },
                    onNavConfigClick = { pkg -> navConfigPackage = pkg; currentScreen = Screen.NAV_CUSTOMIZATION },
                    onSetupClick = { currentScreen = Screen.SETUP }
                )
                Screen.INFO -> InfoScreen(
                    onBack = { currentScreen = Screen.HOME },
                    onSetupClick = { currentScreen = Screen.SETUP },
                    onLicensesClick = { currentScreen = Screen.LICENSES },
                    onBehaviorClick = { currentScreen = Screen.BEHAVIOR },
                    onGlobalSettingsClick = { currentScreen = Screen.GLOBAL_SETTINGS },
                    onHistoryClick = { currentScreen = Screen.HISTORY },
                    onBlocklistClick = { currentScreen = Screen.GLOBAL_BLOCKLIST },
                    onBackupClick = { currentScreen = Screen.BACKUP },
                    onMusicIslandClick = { currentScreen = Screen.MUSIC_ISLAND },
                    onSmartFeaturesClick = { currentScreen = Screen.SMART_FEATURES },
                    onNotificationManagementClick = { currentScreen = Screen.NOTIFICATION_MANAGEMENT },
                    onDiagnosticsClick = { currentScreen = Screen.DIAGNOSTICS },
                    onCustomizeClick = { currentScreen = Screen.SETTINGS_CUSTOMIZE }
                )
                Screen.GLOBAL_SETTINGS -> GlobalSettingsScreen(onBack = { currentScreen = Screen.INFO }, onNavSettingsClick = { navConfigPackage = null; currentScreen = Screen.NAV_CUSTOMIZATION })
                Screen.NAV_CUSTOMIZATION -> NavCustomizationScreen(onBack = { currentScreen = if (navConfigPackage != null) Screen.HOME else Screen.GLOBAL_SETTINGS }, packageName = navConfigPackage)
                Screen.SETUP -> SetupHealthScreen(onBack = { currentScreen = Screen.INFO })
                Screen.LICENSES -> LicensesScreen(onBack = { currentScreen = Screen.INFO })
                Screen.BEHAVIOR -> PrioritySettingsScreen(
                    onBack = { currentScreen = Screen.INFO },
                    onNavigateToPriorityList = { currentScreen = Screen.APP_PRIORITY },
                    onNavigateToTypePriorityList = { currentScreen = Screen.TYPE_PRIORITY },
                    onNavigateToSmartFeatures = { currentScreen = Screen.SMART_FEATURES }
                )
                Screen.APP_PRIORITY -> AppPriorityScreen(onBack = { currentScreen = Screen.BEHAVIOR })
                Screen.TYPE_PRIORITY -> TypePriorityScreen(onBack = { currentScreen = Screen.BEHAVIOR })
                Screen.HISTORY -> ChangelogHistoryScreen(onBack = { currentScreen = Screen.INFO })
                Screen.MUSIC_ISLAND -> MusicIslandSettingsScreen(onBack = { currentScreen = Screen.INFO })

                // --- SMART FEATURES ---
                Screen.SMART_FEATURES -> SmartFeaturesScreen(
                    onBack = { currentScreen = Screen.INFO },
                    onSummaryListClick = { currentScreen = Screen.NOTIFICATION_SUMMARY }
                )
                Screen.NOTIFICATION_SUMMARY -> NotificationSummaryScreenV2(onBack = { currentScreen = Screen.SMART_FEATURES })

                // --- ISLAND QUICK ACTIONS ---
                Screen.ISLAND_QUICK_ACTIONS -> {
                    if (quickActionsPackage != null) {
                        IslandQuickActionsScreen(
                            packageName = quickActionsPackage!!,
                            onBack = { currentScreen = Screen.HOME }
                        )
                    } else {
                        LaunchedEffect(Unit) { currentScreen = Screen.HOME }
                    }
                }

                Screen.GLOBAL_BLOCKLIST -> GlobalBlocklistScreen(
                    onBack = { currentScreen = Screen.INFO },
                    onNavigateToAppList = { currentScreen = Screen.BLOCKLIST_APPS }
                )
                Screen.BLOCKLIST_APPS -> BlocklistAppListScreen(
                    onBack = { currentScreen = Screen.GLOBAL_BLOCKLIST }
                )

                // --- BACKUP FLOW ---
                Screen.BACKUP -> BackupSettingsScreen(
                    onBack = { currentScreen = Screen.INFO },
                    backupManager = backupManager,
                    onBackupFileLoaded = { backup ->
                        pendingImportBackup = backup
                        currentScreen = Screen.IMPORT_PREVIEW
                    }
                )

                Screen.IMPORT_PREVIEW -> {
                    if (pendingImportBackup != null) {
                        ImportPreviewScreen(
                            backupData = pendingImportBackup!!,
                            onBack = { currentScreen = Screen.BACKUP },
                            onConfirmRestore = { selection ->
                                scope.launch {
                                    val result = backupManager.restoreBackup(pendingImportBackup!!, selection)
                                    if (result.isSuccess) {
                                        Toast.makeText(context, context.getString(R.string.import_success), Toast.LENGTH_LONG).show()
                                        currentScreen = Screen.HOME
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.import_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    } else {
                        LaunchedEffect(Unit) { currentScreen = Screen.BACKUP }
                    }
                }

                // --- NOTIFICATION MANAGEMENT (v0.9.7) ---
                Screen.NOTIFICATION_MANAGEMENT -> NotificationManagementScreen(
                    onBack = { currentScreen = Screen.INFO },
                    onAppsListClick = { currentScreen = Screen.NOTIFICATION_MANAGEMENT_APPS }
                )
                Screen.SETTINGS_CUSTOMIZE -> SettingsCustomizationScreen(
                    onBack = { currentScreen = Screen.INFO }
                )
                Screen.NOTIFICATION_MANAGEMENT_APPS -> NotificationManagementAppsScreen(
                    onBack = { currentScreen = Screen.NOTIFICATION_MANAGEMENT }
                )

                // --- DIAGNOSTICS (Debug only) ---
                Screen.DIAGNOSTICS -> DiagnosticsScreen(
                    onBack = { currentScreen = Screen.INFO },
                    onIslandStylePreviewClick = { currentScreen = Screen.ISLAND_STYLE_PREVIEW }
                )

                // --- ISLAND STYLE PREVIEW (Debug only) ---
                Screen.ISLAND_STYLE_PREVIEW -> IslandStylePreviewScreen(
                    onBack = { currentScreen = Screen.DIAGNOSTICS }
                )
            }
        }
    }

    if (showChangelog) {
        ChangelogDialog(currentVersionName = currentVersionName, changelogText = stringResource(R.string.changelog_0_3_1)) {
            showChangelog = false
            scope.launch {
                preferences.setLastSeenVersion(currentVersionCode)
                if (!isPriorityEduShown) showPriorityEdu = true
            }
        }
    }

    if (showPriorityEdu) {
        PriorityEducationDialog(
            onDismiss = { showPriorityEdu = false; scope.launch { preferences.setPriorityEduShown(true) } },
            onConfigure = {
                showPriorityEdu = false
                scope.launch { preferences.setPriorityEduShown(true) }
                currentScreen = Screen.BEHAVIOR
            }
        )
    }
}
