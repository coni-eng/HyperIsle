package com.coni.hyperisle.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.data.db.AppDatabase
import com.coni.hyperisle.data.db.NotificationDigestItem
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch



enum class TimeBucket {
    MORNING,    // 6:00 - 12:00
    AFTERNOON,  // 12:00 - 18:00
    EVENING     // 18:00 - 6:00
}

fun getTimeBucket(timestamp: Long): TimeBucket {
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 6..11 -> TimeBucket.MORNING
        in 12..17 -> TimeBucket.AFTERNOON
        else -> TimeBucket.EVENING
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSummaryScreenV2(onBack: () -> Unit) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val preferences = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    val since24h = remember { System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24) }
    val items by database.digestDao().getItemsSinceFlowOrdered(since24h).collectAsState(initial = emptyList())

    val top3Apps = remember(items) {
        items.groupBy { it.packageName }
            .map { (pkg, notifs) -> pkg to notifs.size }
            .sortedByDescending { it.second }
            .take(3)
    }

    val timeBucketGroups = remember(items) {
        items.groupBy { getTimeBucket(it.postTime) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.summary_history_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.summary_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.summary_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.summary_top_apps_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                }

                items(top3Apps) { (packageName, count) ->
                    TopAppCard(
                        packageName = packageName,
                        count = count,
                        onMute = {
                            scope.launch {
                                preferences.muteApp(packageName)
                            }
                        },
                        onBlock = {
                            scope.launch {
                                preferences.blockAppIslands(packageName)
                            }
                        }
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.summary_by_time_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                }

                TimeBucket.entries.forEach { bucket ->
                    val bucketItems = timeBucketGroups[bucket] ?: emptyList()
                    if (bucketItems.isNotEmpty()) {
                        item {
                            TimeBucketSection(
                                bucket = bucket,
                                items = bucketItems,
                                onMute = { pkg ->
                                    scope.launch {
                                        preferences.muteApp(pkg)
                                    }
                                },
                                onBlock = { pkg ->
                                    scope.launch {
                                        preferences.blockAppIslands(pkg)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopAppCard(
    packageName: String,
    count: Int,
    onMute: () -> Unit,
    onBlock: () -> Unit
) {
    val context = LocalContext.current
    val appName = remember(packageName) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    val appIcon = remember(packageName) {
        try {
            val pm = context.packageManager
            pm.getApplicationIcon(packageName).toBitmap(48, 48).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (appIcon != null) {
                androidx.compose.foundation.Image(
                    bitmap = appIcon,
                    contentDescription = appName,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    appName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.summary_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMute) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringResource(R.string.quick_actions_mute_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onBlock) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = stringResource(R.string.quick_actions_block_title),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun TimeBucketSection(
    bucket: TimeBucket,
    items: List<NotificationDigestItem>,
    onMute: (String) -> Unit,
    onBlock: (String) -> Unit
) {
    val bucketTitle = when (bucket) {
        TimeBucket.MORNING -> stringResource(R.string.time_bucket_morning)
        TimeBucket.AFTERNOON -> stringResource(R.string.time_bucket_afternoon)
        TimeBucket.EVENING -> stringResource(R.string.time_bucket_evening)
    }

    val groupedByApp = items.groupBy { it.packageName }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "$bucketTitle (${items.size})",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))

            groupedByApp.entries.take(5).forEach { (packageName, notifications) ->
                AppInBucketItem(
                    packageName = packageName,
                    count = notifications.size,
                    onMute = { onMute(packageName) },
                    onBlock = { onBlock(packageName) }
                )
                Spacer(Modifier.height(8.dp))
            }

            if (groupedByApp.size > 5) {
                Text(
                    stringResource(R.string.summary_more_apps, groupedByApp.size - 5),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppInBucketItem(
    packageName: String,
    count: Int,
    onMute: () -> Unit,
    onBlock: () -> Unit
) {
    val context = LocalContext.current
    val appName = remember(packageName) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                appName,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.summary_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onMute) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = stringResource(R.string.quick_actions_mute_title),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onBlock) {
            Icon(
                Icons.Default.Block,
                contentDescription = stringResource(R.string.quick_actions_block_title),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
