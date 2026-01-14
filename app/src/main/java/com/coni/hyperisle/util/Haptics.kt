package com.coni.hyperisle.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.coni.hyperisle.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking



/**
 * Utility class for haptic feedback on island events.
 * Provides iOS-like tactile feedback for island interactions.
 */
object Haptics {

    /**
     * Light tick haptic when an island is shown/updated.
     * Pattern: single short vibration (15ms)
     */
    fun hapticOnIslandShown(context: Context) {
        if (!isHapticsEnabled(context)) return
        
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        val effect = VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    /**
     * Double-tick haptic for success actions (dismiss, acknowledge).
     * Pattern: 10ms vibrate, 30ms pause, 15ms vibrate
     */
    fun hapticOnIslandSuccess(context: Context) {
        if (!isHapticsEnabled(context)) return
        
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        // Pattern: [delay, vibrate, pause, vibrate]
        // Timings: 0ms delay, 10ms vibrate, 30ms pause, 15ms vibrate
        val timings = longArrayOf(0, 10, 30, 15)
        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        vibrator.vibrate(effect)
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(context: Context): Vibrator? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun isHapticsEnabled(context: Context): Boolean {
        return try {
            val preferences = AppPreferences(context)
            runBlocking { preferences.hapticsEnabledFlow.first() }
        } catch (e: Exception) {
            true // Default to enabled if preferences fail
        }
    }
}
