package com.d4viddf.hyperbridge

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
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.db.AppDatabase
import com.d4viddf.hyperbridge.data.model.HyperBridgeBackup
import com.d4viddf.hyperbridge.ui.components.ChangelogDialog
import com.d4viddf.hyperbridge.ui.components.PriorityEducationDialog
import com.d4viddf.hyperbridge.ui.screens.home.HomeScreen
import com.d4viddf.hyperbridge.ui.screens.onboarding.OnboardingScreen
import com.d4viddf.hyperbridge.ui.screens.settings.AppPriorityScreen
import com.d4viddf.hyperbridge.ui.screens.settings.BackupSettingsScreen
import com.d4viddf.hyperbridge.ui.screens.settings.BlocklistAppListScreen
import com.d4viddf.hyperbridge.ui.screens.settings.ChangelogHistoryScreen
import com.d4viddf.hyperbridge.ui.screens.settings.GlobalBlocklistScreen
import com.d4viddf.hyperbridge.ui.screens.settings.GlobalSettingsScreen
import com.d4viddf.hyperbridge.ui.screens.settings.ImportPreviewScreen
import com.d4viddf.hyperbridge.ui.screens.settings.InfoScreen
import com.d4viddf.hyperbridge.ui.screens.settings.IslandQuickActionsScreen
import com.d4viddf.hyperbridge.ui.screens.settings.LicensesScreen
import com.d4viddf.hyperbridge.ui.screens.settings.MusicIslandSettingsScreen
import com.d4viddf.hyperbridge.ui.screens.settings.NavCustomizationScreen
import com.d4viddf.hyperbridge.ui.screens.settings.PrioritySettingsScreen
import com.d4viddf.hyperbridge.ui.screens.settings.SetupHealthScreen
import com.d4viddf.hyperbridge.ui.screens.settings.SmartFeaturesScreen
import com.d4viddf.hyperbridge.ui.screens.settings.NotificationSummaryScreen
import com.d4viddf.hyperbridge.ui.theme.HyperBridgeTheme
import com.d4viddf.hyperbridge.util.BackupManager
import com.d4viddf.hyperbridge.worker.NotificationSummaryWorker
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check for quick actions intent
        val openQuickActions = intent?.getBooleanExtra("openQuickActions", false) ?: false
        val quickActionsPackage = intent?.getStringExtra("quickActionsPackage")
        
        setContent {
            HyperBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainRootNavigation(
                        initialOpenQuickActions = openQuickActions,
                        initialQuickActionsPackage = quickActionsPackage
                    )
                }
            }
        }
    }
}

enum class Screen(val depth: Int) {
    ONBOARDING(0), HOME(1), INFO(2), SETUP(3), LICENSES(3), BEHAVIOR(3), GLOBAL_SETTINGS(3), HISTORY(3),
    BACKUP(3), IMPORT_PREVIEW(4), // Backup Flow
    NAV_CUSTOMIZATION(4), APP_PRIORITY(4), GLOBAL_BLOCKLIST(4), BLOCKLIST_APPS(5),
    MUSIC_ISLAND(3), // Music Island Settings
    SMART_FEATURES(3), // Smart Silence, Focus, Summary settings
    NOTIFICATION_SUMMARY(4), // Summary list screen
    ISLAND_QUICK_ACTIONS(3) // Quick actions for island (mute/block app)
}

@Composable
fun MainRootNavigation(
    initialOpenQuickActions: Boolean = false,
    initialQuickActionsPackage: String? = null
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

    // State to hold the parsed backup file before restoring
    var pendingImportBackup by remember { mutableStateOf<HyperBridgeBackup?>(null) }

    // Observe summary settings for worker scheduling
    val summaryEnabled by preferences.summaryEnabledFlow.collectAsState(initial = false)
    val summaryHour by preferences.summaryHourFlow.collectAsState(initial = 21)

    // --- 3. ROUTING LOGIC ---
    LaunchedEffect(isSetupComplete) {
        if (isSetupComplete != null) {
            if (currentScreen == null) {
                // Check if we should open Quick Actions directly
                if (pendingQuickActions && isSetupComplete == true && quickActionsPackage != null) {
                    currentScreen = Screen.ISLAND_QUICK_ACTIONS
                    pendingQuickActions = false
                } else {
                    currentScreen = if (isSetupComplete == true) Screen.HOME else Screen.ONBOARDING
                }
            }

            if (isSetupComplete == true && !pendingQuickActions) {
                if (currentVersionCode > lastSeenVersion) {
                    showChangelog = true
                } else if (!isPriorityEduShown && !showChangelog) {
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
            Screen.APP_PRIORITY -> Screen.BEHAVIOR
            Screen.HISTORY -> Screen.INFO
            Screen.BEHAVIOR, Screen.SETUP, Screen.LICENSES, Screen.MUSIC_ISLAND, Screen.SMART_FEATURES -> Screen.INFO
            Screen.NOTIFICATION_SUMMARY -> Screen.SMART_FEATURES
            Screen.INFO -> Screen.HOME
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
                    onNavConfigClick = { pkg -> navConfigPackage = pkg; currentScreen = Screen.NAV_CUSTOMIZATION }
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
                    onSmartFeaturesClick = { currentScreen = Screen.SMART_FEATURES }
                )
                Screen.GLOBAL_SETTINGS -> GlobalSettingsScreen(onBack = { currentScreen = Screen.INFO }, onNavSettingsClick = { navConfigPackage = null; currentScreen = Screen.NAV_CUSTOMIZATION })
                Screen.NAV_CUSTOMIZATION -> NavCustomizationScreen(onBack = { currentScreen = if (navConfigPackage != null) Screen.HOME else Screen.GLOBAL_SETTINGS }, packageName = navConfigPackage)
                Screen.SETUP -> SetupHealthScreen(onBack = { currentScreen = Screen.INFO })
                Screen.LICENSES -> LicensesScreen(onBack = { currentScreen = Screen.INFO })
                Screen.BEHAVIOR -> PrioritySettingsScreen(onBack = { currentScreen = Screen.INFO }, onNavigateToPriorityList = { currentScreen = Screen.APP_PRIORITY })
                Screen.APP_PRIORITY -> AppPriorityScreen(onBack = { currentScreen = Screen.BEHAVIOR })
                Screen.HISTORY -> ChangelogHistoryScreen(onBack = { currentScreen = Screen.INFO })
                Screen.MUSIC_ISLAND -> MusicIslandSettingsScreen(onBack = { currentScreen = Screen.INFO })

                // --- SMART FEATURES ---
                Screen.SMART_FEATURES -> SmartFeaturesScreen(
                    onBack = { currentScreen = Screen.INFO },
                    onSummaryListClick = { currentScreen = Screen.NOTIFICATION_SUMMARY }
                )
                Screen.NOTIFICATION_SUMMARY -> NotificationSummaryScreen(onBack = { currentScreen = Screen.SMART_FEATURES })

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
                                        // Force restart navigation to reflect changes immediately
                                        currentScreen = Screen.HOME
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.import_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    } else {
                        // Fallback logic
                        LaunchedEffect(Unit) { currentScreen = Screen.BACKUP }
                    }
                }
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