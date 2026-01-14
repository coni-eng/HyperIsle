package com.coni.hyperisle.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.DisplayCutout
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.anchor.CutoutHelper
import com.coni.hyperisle.overlay.anchor.CutoutInfo
import com.coni.hyperisle.util.HiLog




/**
 * Controller for managing overlay windows using WindowManager.
 * Handles adding/removing ComposeView overlays with TYPE_APPLICATION_OVERLAY.
 */
class OverlayWindowController(private val context: Context) {

    private val TAG = "OverlayWindowController"

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var hasLoggedWindowFlags = false
    private var lastScreenWidth: Int = 0
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    // Current overlay state
    var currentEvent: OverlayEvent? by mutableStateOf(null)
        private set

    /**
     * Check if overlay permission is granted.
     */
    fun hasOverlayPermission(): Boolean {
        return android.provider.Settings.canDrawOverlays(context)
    }

    /**
     * Show an overlay with the given composable content.
     * @param interactive If false, overlay is non-touchable (passive mode for calls on MIUI)
     */
    fun showOverlay(event: OverlayEvent, interactive: Boolean = true, content: @Composable () -> Unit) {
        if (!hasOverlayPermission()) {
            HiLog.w(HiLog.TAG_ISLAND, "Overlay permission not granted, ignoring event")
            return
        }

        try {
            currentEvent = event

            // Reuse existing overlay if available to prevent blinking
            if (overlayView != null && overlayView?.isAttachedToWindow == true) {
                // Update content
                overlayView?.setContent(content)
                
                // Update params
                val params = overlayParams ?: return
                val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        
                val flags = if (!interactive) {
                    baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    baseFlags
                }
                
                var paramsChanged = false
                if (params.flags != flags) {
                    params.flags = flags
                    paramsChanged = true
                }
                
                // Update position just in case
                val positionResult = getClampedPosition()
                if (params.x != positionResult.x || params.y != positionResult.y) {
                    params.x = positionResult.x
                    params.y = positionResult.y
                    paramsChanged = true
                }

                // Force layout update if flags/pos changed or if content might have changed size
                // Always updating ensures smooth transition
                windowManager.updateViewLayout(overlayView, params)
                
                HiLog.d(HiLog.TAG_ISLAND, "Overlay updated (reused) interactive=$interactive")
                return
            }

            // Remove existing overlay first (if detached or null but not cleaned up)
            removeOverlay()

            // Create lifecycle owner for ComposeView
            lifecycleOwner = OverlayLifecycleOwner().apply {
                performCreate()
                performStart()
                performResume()
            }

            // Create ComposeView
            overlayView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent(content)
            }

            // Create layout params for overlay
            // Use WRAP_CONTENT for width to allow touch pass-through outside the island
            // ADDED FLAG_LAYOUT_NO_LIMITS to allow drawing over cutout/status bar
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

            val flags = if (!interactive) {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                baseFlags
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Add top margin for status bar with clamped position
                val positionResult = getClampedPosition()
                y = positionResult.y
                x = positionResult.x
            }
            
            // Track screen dimensions for orientation changes
            lastScreenWidth = getScreenWidth()
            lastOrientation = context.resources.configuration.orientation
            overlayParams = params
            val touchable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
            val focusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND,
                    "RID=${overlayRid(event)} EVT=WINDOW_ATTACHED interactive=$interactive touchable=$touchable focusable=$focusable flags=${params.flags}"
                )
            }
            if (!hasLoggedWindowFlags) {
                HiLog.d(HiLog.TAG_ISLAND,
                    "RID=OVL_FLAGS EVT=WINDOW_FLAGS flags=${params.flags} type=${params.type} touchable=$touchable focusable=$focusable"
                )
                hasLoggedWindowFlags = true
            }

            // Add overlay measurement logging after layout
            if (BuildConfig.DEBUG) {
                overlayView?.viewTreeObserver?.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        overlayView?.let { view ->
                            val w = view.width
                            val h = view.height
                            HiLog.d(HiLog.TAG_ISLAND,
                                "RID=${overlayRid(event)} EVT=OVERLAY_MEASURED w=$w h=$h interactive=$interactive"
                            )
                            // Remove listener after first measurement to avoid spam
                            view.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                        }
                    }
                })
            }
            
            // Add view with MIUI-safe error handling
            // MIUI's MiuiCameraCoveredManager may throw NullPointerException when accessing cloud settings
            // This is a MIUI system bug, not our fault - we catch and log it safely
            try {
                windowManager.addView(overlayView, params)
                HiLog.d(HiLog.TAG_ISLAND, "Overlay shown successfully (interactive=$interactive)")
                HiLog.d(HiLog.TAG_ISLAND, "RID=${overlayRid(event)} EVT=OVERLAY_SHOW_OK")
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "RID=${overlayRid(event)} EVT=ISLAND_RENDER interactive=$interactive flags=${params.flags}")
                }
            } catch (miuiException: NullPointerException) {
                // MIUI MiuiCameraCoveredManager bug - safe to ignore
                // The view is still added successfully despite the exception
                if (miuiException.message?.contains("MiuiSettings") == true || 
                    miuiException.message?.contains("CloudData") == true) {
                    HiLog.w(HiLog.TAG_ISLAND, "MIUI MiuiCameraCoveredManager NullPointerException (safe to ignore): ${miuiException.message}")
                    HiLog.d(HiLog.TAG_ISLAND, "RID=${overlayRid(event)} EVT=OVERLAY_SHOW_OK reason=MIUI_EXCEPTION_IGNORED")
                } else {
                    // Re-throw if it's a different NullPointerException
                    throw miuiException
                }
            }

        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Failed to show overlay: ${e.message}", emptyMap(), e)
            currentEvent = null
        }
    }

    /**
     * Remove the current overlay.
     */
    fun removeOverlay() {
        try {
            overlayView?.let { view ->
                lifecycleOwner?.performPause()
                lifecycleOwner?.performStop()
                lifecycleOwner?.performDestroy()

                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                }
                HiLog.d(HiLog.TAG_ISLAND, "Overlay removed successfully")
                if (BuildConfig.DEBUG) {
                    val rid = currentEvent?.let { overlayRid(it) } ?: 0
                    HiLog.d(HiLog.TAG_ISLAND, "RID=$rid EVT=WINDOW_DETACHED")
                }
            }
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Failed to remove overlay: ${e.message}", emptyMap(), e)
        } finally {
            overlayView = null
            lifecycleOwner = null
            currentEvent = null
            overlayParams = null
        }
    }

    /**
     * Force dismiss overlay with guaranteed cleanup (for stuck overlay scenarios).
     */
    fun forceDismissOverlay(reason: String) {
        val rid = currentEvent?.let { overlayRid(it) } ?: 0
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "RID=$rid EVT=ISLAND_FORCE_DISMISS reason=$reason")
        }
        try {
            overlayView?.let { view ->
                try {
                    lifecycleOwner?.performPause()
                    lifecycleOwner?.performStop()
                    lifecycleOwner?.performDestroy()
                } catch (e: Exception) {
                    HiLog.w(HiLog.TAG_ISLAND, "Lifecycle cleanup failed during force dismiss: ${e.message}")
                }

                try {
                    if (view.isAttachedToWindow) {
                        windowManager.removeViewImmediate(view)
                    }
                } catch (e: Exception) {
                    HiLog.w(HiLog.TAG_ISLAND, "View removal failed during force dismiss: ${e.message}")
                }
                HiLog.d(HiLog.TAG_ISLAND, "Overlay force dismissed: $reason")
            }
        } finally {
            overlayView = null
            lifecycleOwner = null
            currentEvent = null
            overlayParams = null
        }
    }

    /**
     * Check if an overlay is currently showing.
     */
    fun isShowing(): Boolean {
        return overlayView != null && overlayView?.isAttachedToWindow == true
    }

    fun setFocusable(isFocusable: Boolean, reason: String? = null, rid: Int? = null) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val wasFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (wasFocusable == isFocusable) return
        params.flags = if (isFocusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            windowManager.updateViewLayout(view, params)
            if (BuildConfig.DEBUG) {
                val focusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
                val logRid = rid ?: currentEvent?.let { overlayRid(it) } ?: 0
                HiLog.d(HiLog.TAG_ISLAND,
                    "RID=$logRid EVT=OVL_FLAGS_UPDATE focusable=$focusable reason=${reason ?: "unknown"} flags=${params.flags}"
                )
            }
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Failed to update overlay focusable: ${e.message}", emptyMap(), e)
        }
    }

    /**
     * Request WindowManager to recalculate layout.
     * Called when overlay content changes size (e.g., expanded -> mini mode).
     * This ensures touch regions are updated to match the new content bounds.
     * 
     * With WRAP_CONTENT, the window should automatically resize, but we force
     * an updateViewLayout call to ensure WindowManager recalculates touch regions.
     */
    fun requestLayoutUpdate(mode: String, rid: Int? = null) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        
        try {
            // Force WindowManager to recalculate layout by calling updateViewLayout
            // This ensures touch regions match the new content size
            windowManager.updateViewLayout(view, params)
            
            if (BuildConfig.DEBUG) {
                val logRid = rid ?: currentEvent?.let { overlayRid(it) } ?: 0
                HiLog.d(HiLog.TAG_ISLAND,
                    "RID=$logRid EVT=WIN_LAYOUT_UPDATE mode=$mode w=${params.width} h=${params.height} flags=${params.flags}"
                )
            }
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Failed to update overlay layout: ${e.message}", emptyMap(), e)
        }
    }

    /**
     * Recalculate and update overlay position based on current cutout/metrics.
     * Should be called when:
     * - Screen orientation changes
     * - Display metrics change
     * - Cutout info needs refresh
     */
    fun updatePosition(reason: String) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        
        try {
            val currentScreenWidth = getScreenWidth()
            val currentOrientation = context.resources.configuration.orientation
            
            // Recalculate position
            val positionResult = getClampedPosition()
            params.x = positionResult.x
            params.y = positionResult.y
            
            windowManager.updateViewLayout(view, params)
            
            // Update tracking
            lastScreenWidth = currentScreenWidth
            lastOrientation = currentOrientation
            
            HiLog.d(HiLog.TAG_ISLAND,
                "EVT=POSITION_UPDATE reason=$reason x=${params.x} y=${params.y} " +
                "screenW=$currentScreenWidth orientation=$currentOrientation"
            )
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Failed to update overlay position: ${e.message}", emptyMap(), e)
        }
    }

    /**
     * Check if position update is needed due to orientation/metrics change.
     */
    fun checkAndUpdatePositionIfNeeded() {
        val currentScreenWidth = getScreenWidth()
        val currentOrientation = context.resources.configuration.orientation
        
        if (currentScreenWidth != lastScreenWidth || currentOrientation != lastOrientation) {
            updatePosition("METRICS_CHANGED")
        }
    }

    private fun overlayRid(event: OverlayEvent): Int {
        return when (event) {
            is OverlayEvent.CallEvent -> event.model.notificationKey.hashCode()
            is OverlayEvent.NotificationEvent -> event.model.notificationKey.hashCode()
            is OverlayEvent.MediaEvent -> event.model.notificationKey.hashCode()
            is OverlayEvent.TimerEvent -> event.model.notificationKey.hashCode()
            is OverlayEvent.NavigationEvent -> event.model.notificationKey.hashCode()
            is OverlayEvent.DismissEvent,
            OverlayEvent.DismissAllEvent -> 0
        }
    }

    /**
     * Result of clamped position calculation.
     */
    data class PositionResult(val x: Int, val y: Int)

    /**
     * Get screen width for position calculations.
     */
    @Suppress("DEPRECATION")
    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars()
            )
            windowMetrics.bounds.width() - insets.left - insets.right
        } else {
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            display.getMetrics(metrics)
            metrics.widthPixels
        }
    }

    /**
     * Get clamped position for island overlay, ensuring it stays within screen bounds.
     * Centers on camera cutout if available, with proper X/Y clamping.
     * 
     * With Gravity.TOP | CENTER_HORIZONTAL:
     * - x=0 means horizontally centered on screen
     * - x>0 shifts right, x<0 shifts left
     * - We calculate offset from screen center to cutout center
     */
    private fun getClampedPosition(): PositionResult {
        val screenWidth = getScreenWidth()
        val cutoutInfo = CutoutHelper.getCutoutInfo(context)
        
        // Calculate X offset: shift from screen center to cutout center
        // With CENTER_HORIZONTAL gravity, x=0 is screen center
        // desiredX = cutoutCenterX - screenWidth/2
        val screenCenterX = screenWidth / 2
        val desiredX = if (cutoutInfo != null) {
            cutoutInfo.centerX - screenCenterX
        } else {
            0 // No cutout, stay centered
        }
        
        // Clamp X to prevent going off-screen
        val estimatedIslandWidth = (screenWidth * 0.9).toInt()
        val maxX = (screenWidth - estimatedIslandWidth) / 2
        val clampedX = desiredX.coerceIn(-maxX, maxX)
        
        // Calculate Y position based on cutout
        val y = if (cutoutInfo != null) {
            // Position at top with 0 offset to overlap camera cutout
            0
        } else {
            // Fallback to status bar height when no cutout
            getStatusBarHeight()
        }
        
        HiLog.d(HiLog.TAG_ISLAND,
            "EVT=POSITION_CALC screenW=$screenWidth screenCenterX=$screenCenterX " +
            "cutoutCenterX=${cutoutInfo?.centerX} cutoutTop=${cutoutInfo?.top} " +
            "desiredX=$desiredX clampedX=$clampedX y=$y"
        )
        
        return PositionResult(clampedX, y)
    }

    /**
     * Get optimal Y position for island overlay, accounting for camera cutout.
     * 
     * Priority:
     * 1. Use DisplayCutout bounding rect if available (center on cutout)
     * 2. Fall back to status bar height
     * 3. Default fallback value
     */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun getStatusBarHeight(): Int {
        // Try to get camera cutout position for proper island centering
        val cutoutTop = getCameraCutoutTop()
        if (cutoutTop > 0) {
            HiLog.d(HiLog.TAG_ISLAND, "Using camera cutout position: $cutoutTop")
            return cutoutTop
        }
        
        // Fallback to status bar height
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            80 // Default fallback
        }
    }
    
    /**
     * Get the top position of the camera cutout for island positioning.
     * Returns 0 if no cutout is detected or on older API levels.
     * 
     * The island should be positioned to visually wrap around the camera cutout,
     * similar to iOS Dynamic Island behavior.
     */
    @Suppress("DEPRECATION")
    private fun getCameraCutoutTop(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return 0 // DisplayCutout API requires API 28+
        }
        
        return try {
            val display = windowManager.defaultDisplay
            val cutout = display.cutout ?: return 0
            
            // Get the bounding rects of all cutouts
            val boundingRects = cutout.boundingRects
            if (boundingRects.isEmpty()) return 0
            
            // Find the top-center cutout (camera notch)
            // This is typically the first rect for phones with a single notch
            val topCutout = boundingRects.firstOrNull { rect ->
                // Top cutout should be near the top of the screen
                rect.top < 200
            } ?: return 0
            
            // Position island just below the cutout with small padding
            val position = topCutout.bottom + 4
            
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=CUTOUT_DETECTED top=${topCutout.top} bottom=${topCutout.bottom} left=${topCutout.left} right=${topCutout.right} position=$position")
            }
            
            position
        } catch (e: Exception) {
            HiLog.w(HiLog.TAG_ISLAND, "Failed to get camera cutout: ${e.message}")
            0
        }
    }

    /**
     * Custom LifecycleOwner for overlay ComposeView.
     */
    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun performCreate() {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun performStart() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun performResume() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun performPause() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }

        fun performStop() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

        fun performDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}