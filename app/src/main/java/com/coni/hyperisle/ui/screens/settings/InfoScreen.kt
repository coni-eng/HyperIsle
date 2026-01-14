package com.coni.hyperisle.ui.screens.settings

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.DEFAULT_CONFIG_IDS
import com.coni.hyperisle.models.DEFAULT_QUICK_ACTION_IDS
import com.coni.hyperisle.models.SettingsLayoutIds
import com.coni.hyperisle.ui.components.SetupBanner
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.util.isNotificationServiceEnabled
import com.coni.hyperisle.util.isOverlayPermissionGranted
import com.coni.hyperisle.util.isPostNotificationsEnabled
import com.coni.hyperisle.util.parseBold



@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InfoScreen(
    onBack: () -> Unit,
    onSetupClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onBehaviorClick: () -> Unit,
    onGlobalSettingsClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onBlocklistClick: () -> Unit,
    onBackupClick: () -> Unit,
    onMusicIslandClick: () -> Unit,
    onSmartFeaturesClick: () -> Unit = {},
    onNotificationManagementClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    onCustomizeClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val preferences = remember { AppPreferences(context) }

    // FIX: Setup Scroll Behavior state
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    // --- STATE ---
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSetupBanner by remember { mutableStateOf(true) }
    var isListenerGranted by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var isOverlayGranted by remember { mutableStateOf(isOverlayPermissionGranted(context)) }
    var isPostGranted by remember { mutableStateOf(isPostNotificationsEnabled(context)) }
    var isBatteryOptimized by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isListenerGranted = isNotificationServiceEnabled(context)
                isOverlayGranted = isOverlayPermissionGranted(context)
                isPostGranted = isPostNotificationsEnabled(context)
                isBatteryOptimized = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val appVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0" }
        catch (e: Exception) { "1.0.0" }
    }

    val appIconBitmap = remember(context) {
        try { context.packageManager.getApplicationIcon(context.packageName).toBitmap().asImageBitmap() }
        catch (e: Exception) { null }
    }

    val missingRequiredCount = listOf(!isListenerGranted, !isOverlayGranted).count { it }
    val missingRecommendedCount = listOf(!isPostGranted, !isBatteryOptimized).count { it }
    val requiredDone = 2 - missingRequiredCount
    val recommendedDone = 2 - missingRecommendedCount

    val quickActionOrder by preferences.settingsQuickActionsOrderFlow.collectAsState(
        initial = DEFAULT_QUICK_ACTION_IDS
    )
    val configOrder by preferences.settingsConfigOrderFlow.collectAsState(
        initial = DEFAULT_CONFIG_IDS
    )

    val quickActionSpecs = settingsQuickActionSpecs()
    val quickActionMap = quickActionSpecs.associateBy { it.id }
    val orderedQuickActions = quickActionOrder.mapNotNull { quickActionMap[it] }

    val configSpecs = settingsConfigSpecs()
    val configSpecMap = configSpecs.associateBy { it.id }
    val orderedConfigSpecs = configOrder.mapNotNull { configSpecMap[it] }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
                collapsedHeight = TopAppBarDefaults.MediumAppBarCollapsedHeight,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        // FIX: LazyColumn is critical for LargeTopAppBar scroll physics
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- HEADER ---
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    if (appIconBitmap != null) {
                        Image(
                            bitmap = appIconBitmap,
                            contentDescription = stringResource(R.string.logo_desc),
                            modifier = Modifier.size(96.dp).padding(bottom = 12.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = stringResource(R.string.logo_desc),
                            modifier = Modifier
                                .size(80.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                .padding(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = stringResource(R.string.developer_credit),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.version_template, appVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            item {
                if (missingRequiredCount > 0 && showSetupBanner) {
                    SetupBanner(
                        missingCount = missingRequiredCount,
                        onFixNow = {
                            HiLog.d(HiLog.TAG_PREF,
                                "SETTINGS_SETUP_BANNER",
                                mapOf("action" to "fix_now")
                            )
                            onSetupClick()
                        },
                        onLater = {
                            HiLog.d(HiLog.TAG_PREF,
                                "SETTINGS_SETUP_BANNER",
                                mapOf("action" to "later")
                            )
                            showSetupBanner = false
                        }
                    )
                }
            }

            item {
                SetupHealthSummaryCard(
                    requiredDone = requiredDone,
                    requiredTotal = 2,
                    recommendedDone = recommendedDone,
                    recommendedTotal = 2,
                    missingRequiredCount = missingRequiredCount,
                    onClick = {
                        HiLog.d(HiLog.TAG_PREF,
                            "SETTINGS_SETUP_SUMMARY",
                            mapOf("action" to "open")
                        )
                        onSetupClick()
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingsGroupTitle(stringResource(R.string.quick_actions_title))
                    if (orderedQuickActions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_quick_actions_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    } else {
                        orderedQuickActions.chunked(3).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                rowItems.forEach { item ->
                                    val accent = when (item.id) {
                                        SettingsLayoutIds.SETUP -> MaterialTheme.colorScheme.primary
                                        SettingsLayoutIds.SMART -> MaterialTheme.colorScheme.tertiary
                                        SettingsLayoutIds.NOTIFICATION -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                    QuickActionTile(
                                        icon = item.icon,
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        accent = accent,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            HiLog.d(HiLog.TAG_PREF,
                                                "SETTINGS_QUICK_ACTION",
                                                mapOf("target" to item.id)
                                            )
                                            when (item.id) {
                                                SettingsLayoutIds.SETUP -> onSetupClick()
                                                SettingsLayoutIds.SMART -> onSmartFeaturesClick()
                                                SettingsLayoutIds.NOTIFICATION -> onNotificationManagementClick()
                                            }
                                        }
                                    )
                                }
                                if (rowItems.size < 3) {
                                    repeat(3 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingsGroupCard {
                        SettingsItem(
                            icon = Icons.Default.Tune,
                            title = stringResource(R.string.settings_customize_title),
                            subtitle = stringResource(R.string.settings_customize_desc),
                            onClick = {
                                HiLog.d(HiLog.TAG_PREF,
                                    "SETTINGS_CUSTOMIZE_OPEN",
                                    mapOf("source" to "info")
                                )
                                onCustomizeClick()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // --- CONFIGURATION ---
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingsGroupTitle(stringResource(R.string.group_configuration))
                    SettingsGroupCard {
                        orderedConfigSpecs.forEachIndexed { index, item ->
                            SettingsItem(
                                icon = item.icon,
                                title = item.title,
                                subtitle = item.subtitle,
                                onClick = {
                                    HiLog.d(HiLog.TAG_PREF,
                                        "SETTINGS_CONFIG_OPEN",
                                        mapOf("target" to item.id)
                                    )
                                    when (item.id) {
                                        SettingsLayoutIds.SETUP -> onSetupClick()
                                        SettingsLayoutIds.BEHAVIOR -> onBehaviorClick()
                                        SettingsLayoutIds.GLOBAL -> onGlobalSettingsClick()
                                        SettingsLayoutIds.BLOCKLIST -> onBlocklistClick()
                                        SettingsLayoutIds.BACKUP -> onBackupClick()
                                        SettingsLayoutIds.MUSIC -> onMusicIslandClick()
                                        SettingsLayoutIds.SMART -> onSmartFeaturesClick()
                                        SettingsLayoutIds.NOTIFICATION -> onNotificationManagementClick()
                                    }
                                }
                            )
                            if (index < orderedConfigSpecs.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // --- ABOUT ---
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingsGroupTitle(stringResource(R.string.group_about))
                    SettingsGroupCard {
                        SettingsItem(Icons.Default.Language, stringResource(R.string.language), stringResource(R.string.language_desc)) { showLanguageDialog = true }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))
                        SettingsItem(Icons.Default.Person, stringResource(R.string.developer), stringResource(R.string.developer_subtitle)) { uriHandler.openUri("https://github.com/coni-eng") }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))
                        SettingsItem(Icons.Default.History, stringResource(R.string.version_history), "0.1.0 - $appVersion") {
                            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/coni-eng/HyperIsle/blob/main/CHANGELOG.md".toUri())
                            context.startActivity(intent)
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))
                        SettingsItem(Icons.Default.Code, stringResource(R.string.source_code), stringResource(R.string.source_code_subtitle)) { uriHandler.openUri("https://github.com/coni-eng/HyperIsle") }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))
                        SettingsItem(Icons.Default.Description, stringResource(R.string.licenses), stringResource(R.string.licenses_subtitle), onLicensesClick)
                        if (BuildConfig.DEBUG) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(0.2f))
                            SettingsItem(Icons.Default.BugReport, "Diagnostics", "Debug logging & export", onDiagnosticsClick)
                        }
                    }
                    Spacer(modifier = Modifier.height(48.dp))

                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.footer_made_with_love).parseBold(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    if (showLanguageDialog) {
        LanguageSelectorDialog(onDismiss = { showLanguageDialog = false })
    }
}

// ... (Rest of helpers same as before) ...
@Composable
fun LanguageSelectorDialog(onDismiss: () -> Unit) {
    val languages = mapOf(
        stringResource(R.string.system_default) to "",
        "Türkçe" to "tr",
        "English" to "en"
    )
    val currentAppLocales = AppCompatDelegate.getApplicationLocales()
    val initialTag = if (!currentAppLocales.isEmpty) currentAppLocales.toLanguageTags().split(",")[0] else ""
    val bestMatchKey = languages.entries.find { (_, tag) -> tag.isNotEmpty() && initialTag.startsWith(tag) }?.value ?: ""
    var selectedTag by remember { mutableStateOf(bestMatchKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup()
                ) {
                    languages.forEach { (name, tag) ->
                        val isSelected = (tag == selectedTag)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .selectable(
                                    selected = isSelected,
                                    onClick = { selectedTag = tag },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val appLocale = if (selectedTag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(selectedTag)
                    AppCompatDelegate.setApplicationLocales(appLocale)
                    onDismiss()
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
fun SettingsGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp, top = 8.dp).semantics { heading() }
    )
}

@Composable
fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) { content() }
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SetupHealthSummaryCard(
    requiredDone: Int,
    requiredTotal: Int,
    recommendedDone: Int,
    recommendedTotal: Int,
    missingRequiredCount: Int,
    onClick: () -> Unit
) {
    val statusColor = if (missingRequiredCount > 0) MaterialTheme.colorScheme.error else Color(0xFF34C759)
    val statusIcon = if (missingRequiredCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(statusColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.system_setup),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.system_setup_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusCountChip(
                    label = stringResource(R.string.perm_required_section),
                    value = "$requiredDone/$requiredTotal",
                    isOk = missingRequiredCount == 0
                )
                StatusCountChip(
                    label = stringResource(R.string.perm_recommended_section),
                    value = "$recommendedDone/$recommendedTotal",
                    isOk = recommendedDone == recommendedTotal
                )
            }
        }
    }
}

@Composable
private fun StatusCountChip(label: String, value: String, isOk: Boolean) {
    val accent = if (isOk) Color(0xFF34C759) else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent
        )
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .heightIn(min = 120.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

