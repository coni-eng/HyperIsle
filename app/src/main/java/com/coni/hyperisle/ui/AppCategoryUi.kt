package com.coni.hyperisle.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import com.coni.hyperisle.R



@StringRes
fun appCategoryLabelRes(category: AppCategory): Int {
    return when (category) {
        AppCategory.ALL -> R.string.cat_all
        AppCategory.GAMES -> R.string.cat_games
        AppCategory.MESSAGING -> R.string.cat_messaging
        AppCategory.MUSIC -> R.string.cat_music
        AppCategory.MAPS -> R.string.cat_nav
        AppCategory.TIMER -> R.string.cat_timer
        AppCategory.OTHER -> R.string.cat_other
    }
}

fun appCategoryIcon(category: AppCategory, isSelected: Boolean): ImageVector {
    return if (isSelected) {
        when (category) {
            AppCategory.ALL -> Icons.Filled.Apps
            AppCategory.GAMES -> Icons.Filled.SportsEsports
            AppCategory.MESSAGING -> Icons.AutoMirrored.Filled.Chat
            AppCategory.MUSIC -> Icons.Filled.MusicNote
            AppCategory.MAPS -> Icons.Filled.Place
            AppCategory.TIMER -> Icons.Filled.Timer
            AppCategory.OTHER -> Icons.Filled.Category
        }
    } else {
        when (category) {
            AppCategory.ALL -> Icons.Outlined.Apps
            AppCategory.GAMES -> Icons.Outlined.SportsEsports
            AppCategory.MESSAGING -> Icons.AutoMirrored.Outlined.Chat
            AppCategory.MUSIC -> Icons.Outlined.MusicNote
            AppCategory.MAPS -> Icons.Outlined.Place
            AppCategory.TIMER -> Icons.Outlined.Timer
            AppCategory.OTHER -> Icons.Outlined.Category
        }
    }
}
