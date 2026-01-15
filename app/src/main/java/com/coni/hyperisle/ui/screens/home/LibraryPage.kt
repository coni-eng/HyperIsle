package com.coni.hyperisle.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.R
import com.coni.hyperisle.ui.AppInfo
import com.coni.hyperisle.ui.AppListViewModel
import com.coni.hyperisle.ui.components.AppListFilterSection
import com.coni.hyperisle.ui.components.AppListItem
import com.coni.hyperisle.ui.components.EmptyState



@Composable
fun LibraryPage(
    apps: List<AppInfo>,
    isLoading: Boolean,
    viewModel: AppListViewModel,
    onConfig: (AppInfo) -> Unit
) {
    val searchQuery = viewModel.librarySearch.collectAsState().value
    val selectedCategory = viewModel.libraryCategory.collectAsState().value
    val sortOption = viewModel.librarySort.collectAsState().value

    Column {
        AppListFilterSection(
            searchQuery = searchQuery,
            onSearchChange = { viewModel.librarySearch.value = it },
            selectedCategory = selectedCategory,
            onCategoryChange = { viewModel.libraryCategory.value = it },
            sortOption = sortOption,
            onSortChange = { viewModel.librarySort.value = it }
        )

        val selectedApps = remember(apps) { apps.filter { it.isBridged } }
        val unselectedApps = remember(apps) { apps.filterNot { it.isBridged } }
        val allSelected = unselectedApps.isEmpty() && selectedApps.isNotEmpty()
        val hasApps = apps.isNotEmpty()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = {
                    if (allSelected) {
                        viewModel.disableApps(selectedApps.map { it.packageName })
                    } else {
                        viewModel.enableApps(unselectedApps.map { it.packageName })
                    }
                },
                enabled = hasApps
            ) {
                Icon(
                    if (allSelected) Icons.Default.ClearAll else Icons.Default.DoneAll,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (allSelected) stringResource(R.string.deselect_all_apps)
                    else stringResource(R.string.select_all_apps)
                )
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {  
                CircularProgressIndicator()
            }
        } else if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.no_apps_found),
                    description = "", // Optional
                    icon = Icons.Default.SearchOff
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    Column(modifier = Modifier.animateItem()) {
                        AppListItem(
                            app = app,
                            onToggle = { viewModel.toggleApp(app.packageName, it) },
                            onSettingsClick = { onConfig(app) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}
