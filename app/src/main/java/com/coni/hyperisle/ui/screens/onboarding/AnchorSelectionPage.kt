package com.coni.hyperisle.ui.screens.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.R
import com.coni.hyperisle.models.AnchorVisibilityMode

@Composable
fun AnchorSelectionPage(
    stepIndex: Int,
    stepCount: Int,
    initialMode: AnchorVisibilityMode = AnchorVisibilityMode.ALWAYS,
    onModeSelected: (AnchorVisibilityMode) -> Unit
) {
    var selectedMode by remember { mutableStateOf(initialMode) }

    LaunchedEffect(selectedMode) {
        onModeSelected(selectedMode)
    }

    OnboardingPageLayout(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.anchor_style_title),
        description = stringResource(R.string.anchor_style_desc),
        icon = Icons.Default.Visibility,
        iconColor = MaterialTheme.colorScheme.primary
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SelectionCard(
                title = stringResource(R.string.mode_always_on),
                description = stringResource(R.string.mode_always_on_desc),
                icon = Icons.Default.Visibility,
                isSelected = selectedMode == AnchorVisibilityMode.ALWAYS,
                onClick = { selectedMode = AnchorVisibilityMode.ALWAYS }
            )

            SelectionCard(
                title = stringResource(R.string.mode_smart),
                description = stringResource(R.string.mode_smart_desc),
                icon = Icons.Default.VisibilityOff,
                isSelected = selectedMode == AnchorVisibilityMode.TRIGGERED_ONLY,
                onClick = { selectedMode = AnchorVisibilityMode.TRIGGERED_ONLY }
            )
        }
    }
}

@Composable
private fun SelectionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "borderColor"
    )
    val containerColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) 
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "containerColor"
    )

    Card(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(2.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
