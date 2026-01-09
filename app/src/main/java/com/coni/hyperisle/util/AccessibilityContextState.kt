package com.coni.hyperisle.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccessibilityContextSignals(
    val isFullscreen: Boolean = false,
    val isImeVisible: Boolean = false,
    val foregroundPackage: String? = null,
    val updatedAt: Long = 0L
)

object AccessibilityContextState {
    private const val TAG = "HyperIsleIsland"
    private val _signals = MutableStateFlow(AccessibilityContextSignals())
    val signals: StateFlow<AccessibilityContextSignals> = _signals.asStateFlow()

    fun snapshot(): AccessibilityContextSignals = _signals.value

    fun update(newSignals: AccessibilityContextSignals, reason: String) {
        val previous = _signals.value
        if (previous.isFullscreen == newSignals.isFullscreen &&
            previous.isImeVisible == newSignals.isImeVisible &&
            previous.foregroundPackage == newSignals.foregroundPackage
        ) {
            return
        }
        _signals.value = newSignals
        Log.d(
            TAG,
            "RID=ACC_CTX EVT=CTX_UPDATE reason=$reason fullscreen=${newSignals.isFullscreen} ime=${newSignals.isImeVisible} fg=${newSignals.foregroundPackage ?: "unknown"}"
        )
    }
}
