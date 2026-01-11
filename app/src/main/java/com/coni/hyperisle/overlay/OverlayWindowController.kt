package com.coni.hyperisle.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.DisplayCutout
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.content.res.Configuration
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
     */
    fun showOverlay(event: OverlayEvent, content: @Composable () -> Unit) {
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Overlay permission not granted, ignoring event")
            return
        }

        // Remove existing overlay first
        removeOverlay()

        try {
            currentEvent = event

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
            // REMOVED FLAG_LAYOUT_NO_LIMITS to prevent off-screen positioning
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
            if (!hasLoggedWindowFlags) {
                val touchable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
                val focusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
                Log.d(
                    "HyperIsleIsland",
                    "RID=OVL_FLAGS EVT=WINDOW_FLAGS flags=${params.flags} type=${params.type} touchable=$touchable focusable=$focusable"
                )
                hasLoggedWindowFlags = true
            }

            // Add view with MIUI-safe error handling
            // MIUI's MiuiCameraCoveredManager may throw NullPointerException when accessing cloud settings
            // This is a MIUI system bug, not our fault - we catch and log it safely
            try {
                windowManager.addView(overlayView, params)
                Log.d(TAG, "Overlay shown successfully")
                Log.d("HyperIsleIsland", "RID=${overlayRid(event)} EVT=OVERLAY_SHOW_OK")
            } catch (miuiException: NullPointerException) {
                // MIUI MiuiCameraCoveredManager bug - safe to ignore
                // The view is still added successfully despite the exception
                if (miuiException.message?.contains("MiuiSettings") == true || 
                    miuiException.message?.contains("CloudData") == true) {
                    Log.w(TAG, "MIUI MiuiCameraCoveredManager NullPointerException (safe to ignore): ${miuiException.message}")
                    Log.d("HyperIsleIsland", "RID=${overlayRid(event)} EVT=OVERLAY_SHOW_OK reason=MIUI_EXCEPTION_IGNORED")
                } else {
                    // Re-throw if it's a different NullPointerException
                    throw miuiException
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}", e)
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
                    windowManager.removeView(view)
                }
                Log.d(TAG, "Overlay removed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay: ${e.message}", e)
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
                Log.d(
                    "HyperIsleIsland",
                    "RID=$logRid EVT=OVL_FLAGS_UPDATE focusable=$focusable reason=${reason ?: "unknown"} flags=${params.flags}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update overlay focusable: ${e.message}", e)
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
     */
    private fun getClampedPosition(): PositionResult {
        val screenWidth = getScreenWidth()
        val y = getStatusBarHeight()
        
        // For X position: we use CENTER_HORIZONTAL gravity, so x=0 means centered.
        // We only need to adjust x if there's an asymmetric cutout.
        // For now, keep x=0 (centered) but add bounds logging.
        val x = 0
        
        // Estimate island width (will be measured properly after layout)
        // Use a reasonable max width for clamping validation
        val estimatedIslandWidth = (screenWidth * 0.9).toInt()
        val maxX = (screenWidth - estimatedIslandWidth) / 2
        val clampedX = x.coerceIn(-maxX, maxX)
        
        if (BuildConfig.DEBUG) {
            Log.d(
                "HyperIsleIsland",
                "EVT=POSITION_CALC screenW=$screenWidth estimatedIslandW=$estimatedIslandWidth desiredX=$x clampedX=$clampedX y=$y orientation=${context.resources.configuration.orientation}"
            )
        }
        
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
            Log.d(TAG, "Using camera cutout position: $cutoutTop")
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
                Log.d("HyperIsleIsland", "EVT=CUTOUT_DETECTED top=${topCutout.top} bottom=${topCutout.bottom} left=${topCutout.left} right=${topCutout.right} position=$position")
            }
            
            position
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get camera cutout: ${e.message}")
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
