package com.coni.hyperisle.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coni.hyperisle.R
import com.coni.hyperisle.ui.AppCategory
import com.coni.hyperisle.ui.AppListViewModel
import com.coni.hyperisle.ui.appCategoryIcon
import com.coni.hyperisle.ui.appCategoryLabelRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationManagementAppsScreen(
    onBack: () -> Unit,
    viewModel: AppListViewModel = viewModel()
) {
    val activeApps by viewModel.activeAppsRawState.collectAsState()
    val shadeCancelEnabledPackages by viewModel.shadeCancelEnabledPackagesFlow.collectAsState(
        initial = emptySet()
    )
    var selectedCategory by remember { mutableStateOf(AppCategory.ALL) }

    val filteredApps = remember(activeApps, selectedCategory) {
        if (selectedCategory == AppCategory.ALL) {
            activeApps
        } else {
            activeApps.filter { it.category == selectedCategory }
        }
    }
    val allSelected = filteredApps.isNotEmpty() &&
        filteredApps.all { shadeCancelEnabledPackages.contains(it.packageName) }

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
                        stringResource(R.string.notification_management_apps_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.notification_management_apps_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Text(
                    text = stringResource(R.string.shade_cancel_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                AppCategoryFilterRow(
                    selectedCategory = selectedCategory,
                    onCategoryChange = { selectedCategory = it }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.setShadeCancelForPackages(
                                filteredApps.map { it.packageName },
                                !allSelected
                            )
                        },
                        enabled = filteredApps.isNotEmpty()
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (allSelected) R.string.deselect_all_apps else R.string.select_all_apps
                            )
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { appInfo ->
                        ShadeCancelAppItem(
                            appInfo = appInfo,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppCategoryFilterRow(
    selectedCategory: AppCategory,
    onCategoryChange: (AppCategory) -> Unit
) {
    val scrollState = rememberScrollState()
    val categories = AppCategory.entries.toTypedArray()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        categories.forEachIndexed { index, category ->
            val isSelected = selectedCategory == category
            val shape = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                categories.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }

            ToggleButton(
                checked = isSelected,
                onCheckedChange = { onCategoryChange(category) },
                shapes = shape,
                modifier = Modifier.semantics { role = Role.RadioButton },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                AnimatedContent(
                    targetState = isSelected,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(200))).togetherWith(
                            fadeOut(animationSpec = tween(200))
                        )
                    },
                    label = "IconTransition"
                ) { selected ->
                    Icon(
                        imageVector = appCategoryIcon(category, selected),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))
                Text(stringResource(appCategoryLabelRes(category)))
            }
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    )
}

@Composable
private fun ShadeCancelAppItem(
    appInfo: com.coni.hyperisle.ui.AppInfo,
    viewModel: AppListViewModel
) {
    val shadeCancelEnabled by viewModel.isShadeCancelFlow(appInfo.packageName).collectAsState(initial = false)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
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
                    .size(40.dp)
                    .clip(CircleShape)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    appInfo.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Switch(
                checked = shadeCancelEnabled,
                onCheckedChange = { checked ->
                    viewModel.setShadeCancel(appInfo.packageName, checked)
                }
            )
        }
    }
}
