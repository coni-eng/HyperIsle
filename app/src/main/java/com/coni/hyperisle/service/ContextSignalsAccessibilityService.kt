package com.coni.hyperisle.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.util.AccessibilityContextSignals
import com.coni.hyperisle.util.AccessibilityContextState

class ContextSignalsAccessibilityService : AccessibilityService() {

    private val bounds = Rect()
    private var lastUpdateAt = 0L

    override fun onServiceConnected() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        HiLog.d(HiLog.TAG_ISLAND, "RID=ACC_CTX EVT=SERVICE_CONNECTED")
        updateSignals("CONNECT", null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        updateSignals("EVENT", event)
    }

    override fun onInterrupt() {
        HiLog.d(HiLog.TAG_ISLAND, "RID=ACC_CTX EVT=SERVICE_INTERRUPTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        HiLog.d(HiLog.TAG_ISLAND, "RID=ACC_CTX EVT=SERVICE_DESTROYED")
        AccessibilityContextState.update(AccessibilityContextSignals(), "DESTROY")
    }

    private fun updateSignals(reason: String, event: AccessibilityEvent?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateAt < 250L) {
            return
        }
        lastUpdateAt = now

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        var isFullscreen = false
        var isImeVisible = false
        var foregroundPackage: String? = null
        val rootBounds = Rect()
        val rootNode = rootInActiveWindow
        val eventPackage = event?.packageName?.toString()
        val candidatePackage = eventPackage ?: rootNode?.packageName?.toString()
        var isPip = false
        var rootWidth = 0
        var candidateWidth = 0

        val windows = windows ?: emptyList()
        for (window in windows) {
            when (window.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                    isImeVisible = true
                }
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    window.getBoundsInScreen(bounds)
                    if (bounds.width() >= (screenWidth * 0.9f) &&
                        bounds.height() >= (screenHeight * 0.9f)
                    ) {
                        isFullscreen = true
                    }
                    val windowPkg = window.root?.packageName?.toString()
                    if (candidatePackage != null && windowPkg == candidatePackage) {
                        candidateWidth = maxOf(candidateWidth, bounds.width())
                    }
                    if (foregroundPackage == null) {
                        foregroundPackage = windowPkg
                    }
                }
            }
        }

        if (rootNode != null) {
            rootNode.getBoundsInScreen(rootBounds)
            rootWidth = rootBounds.width()
        }

        if (foregroundPackage == null) {
            foregroundPackage = candidatePackage
        }

        if (candidatePackage == "com.google.android.apps.maps" &&
            candidateWidth > 0 &&
            candidateWidth < (screenWidth * 0.9f)
        ) {
            isPip = true
            isFullscreen = false
        }
        if (BuildConfig.DEBUG) {
            val pkg = foregroundPackage ?: "unknown"
            val logWidth = if (candidateWidth > 0) candidateWidth else rootWidth
            HiLog.d(HiLog.TAG_ISLAND,
                "RID=ACC_CTX EVT=ACC_CTX_BOUNDS pkg=$pkg width=$logWidth screenW=$screenWidth isPip=$isPip"
            )
        }

        val signals = AccessibilityContextSignals(
            isFullscreen = isFullscreen,
            isImeVisible = isImeVisible,
            foregroundPackage = foregroundPackage,
            updatedAt = System.currentTimeMillis()
        )
        AccessibilityContextState.update(signals, reason)
    }
}
