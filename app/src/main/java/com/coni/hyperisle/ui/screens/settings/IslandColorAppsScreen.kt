package com.coni.hyperisle.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.ui.AppInfo
import com.coni.hyperisle.ui.AppListViewModel
import com.coni.hyperisle.util.AccentColorResolver
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IslandColorAppsScreen(
    onBack: () -> Unit,
    viewModel: AppListViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }
    
    // Using active apps (bridged) list
    val activeApps by viewModel.activeAppsState.collectAsState()
    
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    
    if (selectedApp != null) {
        IslandColorDialog(
            app = selectedApp!!,
            onDismiss = { selectedApp = null },
            preferences = preferences
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.island_color_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ColorLens, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.island_color_settings_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            
            items(activeApps, key = { it.packageName }) { app ->
                IslandColorAppItem(
                    app = app,
                    preferences = preferences,
                    onClick = { selectedApp = app }
                )
            }
        }
    }
}

@Composable
private fun IslandColorAppItem(
    app: AppInfo,
    preferences: AppPreferences,
    onClick: () -> Unit
) {
    val isAuto by preferences.isAppColorAutoFlow(app.packageName).collectAsState(initial = false)
    val customColor by preferences.getAppIslandColorFlow(app.packageName).collectAsState(initial = null)
    
    val modeText = when {
        isAuto -> stringResource(R.string.island_color_mode_dynamic)
        customColor != null -> stringResource(R.string.island_color_mode_custom)
        else -> stringResource(R.string.island_color_mode_standard)
    }
    
    val indicatorColor = when {
        isAuto -> MaterialTheme.colorScheme.primary
        customColor != null -> try { Color(android.graphics.Color.parseColor(customColor)) } catch(e:Exception) { Color.Black }
        else -> Color.Black // Standard
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(indicatorColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = modeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IslandColorDialog(
    app: AppInfo,
    onDismiss: () -> Unit,
    preferences: AppPreferences
) {
    val scope = rememberCoroutineScope()
    val isAuto by preferences.isAppColorAutoFlow(app.packageName).collectAsState(initial = false)
    val customColor by preferences.getAppIslandColorFlow(app.packageName).collectAsState(initial = null)
    
    // Local state for editing
    var currentMode by remember { 
        mutableStateOf(
            when {
                isAuto -> ColorMode.DYNAMIC
                customColor != null -> ColorMode.CUSTOM
                else -> ColorMode.STANDARD
            }
        )
    }
    
    var currentHex by remember { mutableStateOf(customColor ?: "#FF0000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.island_color_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(bitmap = app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp).clip(CircleShape))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(app.name, style = MaterialTheme.typography.titleSmall)
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Text(stringResource(R.string.island_color_dialog_mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                
                // Mode Selection
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(
                        selected = currentMode == ColorMode.STANDARD,
                        label = stringResource(R.string.island_color_mode_standard),
                        onClick = { currentMode = ColorMode.STANDARD }
                    )
                    ModeChip(
                        selected = currentMode == ColorMode.DYNAMIC,
                        label = stringResource(R.string.island_color_mode_dynamic),
                        onClick = { currentMode = ColorMode.DYNAMIC }
                    )
                    ModeChip(
                        selected = currentMode == ColorMode.CUSTOM,
                        label = stringResource(R.string.island_color_mode_custom),
                        onClick = { currentMode = ColorMode.CUSTOM }
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Preview Area
                val previewColor = when(currentMode) {
                    ColorMode.STANDARD -> Color.Black
                    ColorMode.DYNAMIC -> {
                        // Attempt to resolve dynamic color for preview
                        val context = LocalContext.current
                        var dynamicColor by remember { mutableStateOf(Color.Gray) }
                        LaunchedEffect(Unit) {
                            val hex = AccentColorResolver.getAccentColor(context, app.packageName)
                            dynamicColor = try { Color(android.graphics.Color.parseColor(hex)) } catch(e:Exception){ Color.Gray }
                        }
                        dynamicColor
                    }
                    ColorMode.CUSTOM -> try { Color(android.graphics.Color.parseColor(currentHex)) } catch(e:Exception){ Color.Gray }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.island_color_preview), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(previewColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(0.2f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Island Content", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
                
                if (currentMode == ColorMode.CUSTOM) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(stringResource(R.string.island_color_dialog_presets), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Presets Grid
                    val presets = listOf(
                        "#FF0000", "#FF9500", "#FFCC00", "#34C759", 
                        "#007AFF", "#5856D6", "#AF52DE", "#FF2D55",
                        "#FFFFFF", "#8E8E93"
                    )
                    
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(40.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(100.dp)
                    ) {
                        items(presets) { hex ->
                            val color = try { Color(android.graphics.Color.parseColor(hex)) } catch(e:Exception){ Color.Transparent }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(0.2f), CircleShape)
                                    .clickable { currentHex = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentHex.equals(hex, ignoreCase = true)) {
                                    Icon(Icons.Default.Check, null, tint = if(hex == "#FFFFFF") Color.Black else Color.White)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = currentHex,
                        onValueChange = { if(it.length <= 9) currentHex = it },
                        label = { Text(stringResource(R.string.island_color_dialog_hex)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        when (currentMode) {
                            ColorMode.STANDARD -> {
                                preferences.setAppColorAuto(app.packageName, false)
                                preferences.setAppIslandColor(app.packageName, null)
                            }
                            ColorMode.DYNAMIC -> {
                                preferences.setAppColorAuto(app.packageName, true)
                                preferences.setAppIslandColor(app.packageName, null)
                            }
                            ColorMode.CUSTOM -> {
                                preferences.setAppColorAuto(app.packageName, false)
                                preferences.setAppIslandColor(app.packageName, currentHex)
                            }
                        }
                        onDismiss()
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ModeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) } } else null
    )
}

private enum class ColorMode { STANDARD, DYNAMIC, CUSTOM }
