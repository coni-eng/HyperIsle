package com.coni.hyperisle.util

import android.content.Context
import android.os.Build
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+: Use VibrationEffect
            val effect = VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            // API < 26: Legacy vibration
            @Suppress("DEPRECATION")
            vibrator.vibrate(15)
        }
    }

    /**
     * Double-tick haptic for success actions (dismiss, acknowledge).
     * Pattern: 10ms vibrate, 30ms pause, 15ms vibrate
     */
    fun hapticOnIslandSuccess(context: Context) {
        if (!isHapticsEnabled(context)) return
        
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+: Use VibrationEffect with waveform
            // Pattern: [delay, vibrate, pause, vibrate]
            // Timings: 0ms delay, 10ms vibrate, 30ms pause, 15ms vibrate
            val timings = longArrayOf(0, 10, 30, 15)
            val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            // API < 26: Legacy waveform vibration
            // Pattern: [delay, vibrate, pause, vibrate]
            val pattern = longArrayOf(0, 10, 30, 15)
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
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
