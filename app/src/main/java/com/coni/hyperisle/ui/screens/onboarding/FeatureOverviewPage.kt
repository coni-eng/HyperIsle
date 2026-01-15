package com.coni.hyperisle.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.R

@Composable
fun FeatureOverviewPage(stepIndex: Int, stepCount: Int) {
    OnboardingPageLayout(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.feature_overview_title),
        description = stringResource(R.string.feature_overview_desc),
        icon = Icons.Default.AutoAwesome,
        iconColor = MaterialTheme.colorScheme.tertiary
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FeatureCard(
                title = stringResource(R.string.feature_nav_title),
                description = stringResource(R.string.feature_nav_desc),
                icon = Icons.Default.Navigation,
                iconColor = Color(0xFF007AFF) // Blue
            )
            
            FeatureCard(
                title = stringResource(R.string.feature_music_title),
                description = stringResource(R.string.feature_music_desc),
                icon = Icons.Default.MusicNote,
                iconColor = Color(0xFFFF2D55) // Pink/Red
            )
            
            FeatureCard(
                title = stringResource(R.string.feature_notif_title),
                description = stringResource(R.string.feature_notif_desc),
                icon = Icons.Default.NotificationsActive,
                iconColor = Color(0xFFFF9500) // Orange
            )

            FeatureCard(
                title = stringResource(R.string.feature_priority_title),
                description = stringResource(R.string.feature_priority_desc),
                icon = Icons.Default.Bolt,
                iconColor = Color(0xFFFFCC00) // Yellow
            )
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = iconColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
