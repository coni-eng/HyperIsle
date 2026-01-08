package com.coni.hyperisle.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
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
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Add top margin for status bar
                y = getStatusBarHeight()
            }

            windowManager.addView(overlayView, params)
            Log.d(TAG, "Overlay shown successfully")

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
        }
    }

    /**
     * Check if an overlay is currently showing.
     */
    fun isShowing(): Boolean {
        return overlayView != null && overlayView?.isAttachedToWindow == true
    }

    /**
     * Get status bar height for proper positioning.
     */
    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId) + 16 // Add 16px padding
        } else {
            80 // Default fallback
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
