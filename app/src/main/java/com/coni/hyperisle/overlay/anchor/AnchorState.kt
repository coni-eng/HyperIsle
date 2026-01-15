package com.coni.hyperisle.overlay.anchor

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.graphics.vector.ImageVector



/**
 * State for the anchor pill display.
 * Contains all information needed to render the anchor in any mode.
 */
data class AnchorState(
    /**
     * Current island mode.
     */
    val mode: IslandMode = IslandMode.ANCHOR_IDLE,

    /**
     * Left slot content.
     */
    val leftSlot: SlotContent? = null,

    /**
     * Right slot content.
     */
    val rightSlot: SlotContent? = null,

    /**
     * Cutout rect from DisplayCutout.
     * Used to calculate the gap in the middle of the pill.
     */
    val cutoutRect: Rect? = null,

    /**
     * Call-specific state (when mode == CALL_ACTIVE).
     */
    val callState: CallAnchorState? = null,

    /**
     * Navigation-specific state (when mode == NAV_ACTIVE).
     */
    val navState: NavAnchorState? = null,

    /**
     * Notification key for expanded mode.
     */
    val expandedNotificationKey: String? = null,

    /**
     * Previous mode before NOTIF_EXPANDED (for shrink animation).
     */
    val previousMode: IslandMode? = null,

    /**
     * Timestamp when current mode started.
     */
    val modeStartedAtMs: Long = System.currentTimeMillis()
)

/**
 * Content for a slot in the anchor pill.
 */
sealed class SlotContent {
    /**
     * Text content with optional icon.
     */
    data class Text(
        val text: String,
        val icon: SlotIcon? = null,
        val textColor: Int? = null
    ) : SlotContent()

    /**
     * Icon only content.
     */
    data class IconOnly(
        val icon: SlotIcon
    ) : SlotContent()

    /**
     * Wave bar animation (for active call).
     */
    data object WaveBar : SlotContent()

    /**
     * Icon with Wave bar animation (for active call).
     */
    data class IconWithWaveBar(
        val icon: SlotIcon
    ) : SlotContent()

    /**
     * Empty slot.
     */
    data object Empty : SlotContent()
}

/**
 * Icon for slot content.
 */
sealed class SlotIcon {
    data class Resource(val resId: Int) : SlotIcon()
    data class Bitmap(val bitmap: android.graphics.Bitmap) : SlotIcon()
    data class Vector(val imageVector: ImageVector, val description: String? = null) : SlotIcon()
}

/**
 * Call-specific state for anchor display.
 */
data class CallAnchorState(
    val notificationKey: String,
    val packageName: String,
    val callerName: String,
    val durationText: String = "",
    val isIncoming: Boolean = false,
    val isActive: Boolean = false,
    val avatarBitmap: Bitmap? = null
)

/**
 * Navigation-specific state for anchor display.
 */
data class NavAnchorState(
    val notificationKey: String,
    val packageName: String,
    val instruction: String = "",
    val distance: String = "",
    val eta: String = "",
    val remainingTime: String = "",
    val appIcon: Bitmap? = null,
    val contentIntent: android.app.PendingIntent? = null,
    /**
     * User-selected left slot info type.
     */
    val leftInfoType: NavInfoType = NavInfoType.INSTRUCTION,
    /**
     * User-selected right slot info type.
     */
    val rightInfoType: NavInfoType = NavInfoType.ETA
)

/**
 * Navigation info types for slot selection.
 */
enum class NavInfoType {
    INSTRUCTION,
    DISTANCE,
    ETA,
    REMAINING_TIME,
    SPEED,
    NONE
}

/**
 * Cutout information for layout calculations.
 */
data class CutoutInfo(
    /**
     * Bounding rect of the camera cutout.
     */
    val rect: Rect,

    /**
     * Width of the cutout.
     */
    val width: Int = rect.width(),

    /**
     * Height of the cutout.
     */
    val height: Int = rect.height(),

    /**
     * Center X position of the cutout.
     */
    val centerX: Int = rect.centerX(),

    /**
     * Top Y position of the cutout.
     */
    val top: Int = rect.top,

    /**
     * Bottom Y position of the cutout.
     */
    val bottom: Int = rect.bottom
) {
    companion object {
        /**
         * Default cutout info for devices without a cutout.
         * Uses a centered position with reasonable defaults.
         */
        fun default(screenWidth: Int): CutoutInfo {
            val defaultWidth = 80
            val defaultHeight = 32
            val centerX = screenWidth / 2
            return CutoutInfo(
                rect = Rect(
                    centerX - defaultWidth / 2,
                    0,
                    centerX + defaultWidth / 2,
                    defaultHeight
                )
            )
        }
    }
}
