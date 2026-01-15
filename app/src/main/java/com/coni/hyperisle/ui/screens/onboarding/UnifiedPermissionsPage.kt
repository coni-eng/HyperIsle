package com.coni.hyperisle.ui.screens.onboarding

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.coni.hyperisle.R
import com.coni.hyperisle.models.PermissionItem
import com.coni.hyperisle.models.PermissionRegistry
import com.coni.hyperisle.util.setAutostartAcknowledged
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun UnifiedPermissionsPage(
    stepIndex: Int,
    stepCount: Int,
    onPermissionsUpdated: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Trigger permission checks
    var checkTrigger by remember { mutableStateOf(0) }
    
    // Auto-refresh on resume (when returning from Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkTrigger++
                onPermissionsUpdated()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Permission Launchers
    val postPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { 
            checkTrigger++
            onPermissionsUpdated()
        }
    )
    
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            checkTrigger++
            onPermissionsUpdated()
        }
    )

    OnboardingPageLayout(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.permissions_title),
        description = stringResource(R.string.permissions_desc),
        icon = Icons.Default.Security,
        iconColor = MaterialTheme.colorScheme.primary
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PermissionRegistry.allPermissions.forEach { permission ->
                PermissionCard(
                    permission = permission,
                    context = context,
                    checkTrigger = checkTrigger,
                    onGrant = {
                        when (permission.id) {
                            PermissionRegistry.POST_NOTIFICATIONS.id -> {
                                postPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            PermissionRegistry.PHONE_CALLS.id -> {
                                phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                            }
                            PermissionRegistry.AUTOSTART.id -> {
                                setAutostartAcknowledged(context, true)
                                permission.openSettings(context)
                            }
                            else -> {
                                permission.openSettings(context)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permission: PermissionItem,
    context: Context,
    checkTrigger: Int,
    onGrant: () -> Unit
) {
    // Immediate state check
    var isGranted by remember { mutableStateOf(permission.isGranted(context)) }
    
    // Update when external trigger fires (OnResume)
    LaunchedEffect(checkTrigger) {
        isGranted = permission.isGranted(context)
    }

    // Polling for fast feedback when user toggles switch in Settings overlay (if visible)
    // or quickly switches back.
    LaunchedEffect(Unit) {
        while(isActive) {
            val currentStatus = permission.isGranted(context)
            if (currentStatus != isGranted) {
                isGranted = currentStatus
            }
            delay(500)
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) 
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f) 
            else 
                MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Animated Icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (isGranted) Color(0xFF34C759).copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(targetState = isGranted, label = "icon_anim") { granted ->
                        if (granted) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF34C759),
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = permission.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title with REQUIRED badge
                Text(
                    text = stringResource(permission.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                if (permission.isRequired && !isGranted) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "REQUIRED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            // Description
            Text(
                text = stringResource(permission.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )

            // Action Button - at bottom
            AnimatedVisibility(
                visible = !isGranted,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                Button(
                    onClick = onGrant,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_enable),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
