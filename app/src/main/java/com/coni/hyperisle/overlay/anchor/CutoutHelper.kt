package com.coni.hyperisle.overlay.anchor

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.util.HiLog



/**
 * Helper class for getting camera cutout information.
 * Used to position the anchor pill around the camera cutout.
 */
object CutoutHelper {
    private const val DEFAULT_CUTOUT_WIDTH = 120
    private const val TAG = "CutoutHelper"

    /**
     * Get the camera cutout information.
     * Returns null if no cutout is detected.
     * 
     * API 30+: Uses maximumWindowMetrics.windowInsets.displayCutout (stable)
     * API 28-29: Falls back to deprecated defaultDisplay.cutout
     */
    @Suppress("DEPRECATION")
    fun getCutoutInfo(context: Context): CutoutInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // API 30+: Use maximumWindowMetrics for stable cutout detection
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.maximumWindowMetrics
                metrics.windowInsets.displayCutout
            } else {
                // API 28-29: Fallback to deprecated defaultDisplay.cutout
                windowManager.defaultDisplay.cutout
            }
            
            if (cutout != null) {
                val boundingRects = cutout.boundingRects
                HiLog.d(HiLog.TAG_ISLAND, "Found ${boundingRects.size} cutouts via Display")
                
                val topCutout = boundingRects.firstOrNull { it.top < 200 }
                
                if (topCutout != null) {
                    return CutoutInfo(
                        width = topCutout.width(),
                        height = topCutout.height(),
                        rect = topCutout,
                        centerX = topCutout.centerX()
                    )
                }
            }
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Failed to get cutout info", emptyMap<String, Any?>(), e)
        }
        
        HiLog.w(HiLog.TAG_ISLAND, "No cutout found, using default")
        return null
    }

    /**
     * Get cutout info or default if not available.
     */
    fun getCutoutInfoOrDefault(context: Context): CutoutInfo {
        return getCutoutInfo(context) ?: CutoutInfo.default(getScreenWidth(context))
    }

    /**
     * Get screen width.
     */
    @Suppress("DEPRECATION")
    fun getScreenWidth(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.width()
        } else {
            val metrics = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels
        }
    }

    /**
     * Calculate anchor pill layout parameters based on cutout.
     */
    fun calculateAnchorLayout(
        cutoutInfo: CutoutInfo,
        gapPadding: Int = 8,
        slotMinWidth: Int = 60,
        pillHeight: Int = 36
    ): AnchorLayoutParams {
        val cutoutGapWidth = cutoutInfo.width + (gapPadding * 2)
        val baseWidth = cutoutGapWidth + (slotMinWidth * 2) + (gapPadding * 2)

        return AnchorLayoutParams(
            cutoutGapWidth = cutoutGapWidth,
            cutoutGapStart = slotMinWidth + gapPadding,
            pillBaseWidth = baseWidth,
            pillHeight = pillHeight,
            pillY = cutoutInfo.top,
            pillCenterX = cutoutInfo.centerX
        )
    }
}

/**
 * Layout parameters for the anchor pill.
 */
data class AnchorLayoutParams(
    /**
     * Width of the cutout gap in the center.
     */
    val cutoutGapWidth: Int,

    /**
     * Start position of the cutout gap from left edge of pill.
     */
    val cutoutGapStart: Int,

    /**
     * Base width of the pill.
     */
    val pillBaseWidth: Int,

    /**
     * Height of the pill.
     */
    val pillHeight: Int,

    /**
     * Y position of the pill (from top of screen).
     */
    val pillY: Int,

    /**
     * Center X position for pill alignment.
     */
    val pillCenterX: Int
)
