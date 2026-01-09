package com.coni.hyperisle.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts and caches dominant accent colors from app icons.
 * Used for adaptive progress ring and accent coloring in islands.
 */
object AccentColorResolver {

    private const val SAMPLE_SIZE = 32
    private const val DEFAULT_COLOR = "#007AFF" // iOS blue fallback

    // Cache: packageName -> hex color string
    private val colorCache = ConcurrentHashMap<String, String>()

    /**
     * Get the accent color for a package.
     * Returns cached value if available, otherwise extracts from app icon.
     * @param context Application context
     * @param packageName The package to get color for
     * @return Hex color string (e.g., "#FF5722")
     */
    fun getAccentColor(context: Context, packageName: String): String {
        // Return cached if available
        colorCache[packageName]?.let { return it }

        // Extract from app icon
        val color = try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            val bitmap = icon.toSmallBitmap()
            val dominant = extractDominantColor(bitmap)
            toHexString(dominant)
        } catch (e: Exception) {
            DEFAULT_COLOR
        }

        // Cache and return
        colorCache[packageName] = color
        return color
    }

    /**
     * Get accent color with a specific fallback.
     */
    fun getAccentColorOrDefault(context: Context, packageName: String, default: String): String {
        return try {
            val color = getAccentColor(context, packageName)
            if (color == DEFAULT_COLOR) default else color
        } catch (e: Exception) {
            default
        }
    }

    /**
     * Clear the color cache (useful for testing or memory pressure).
     */
    fun clearCache() {
        colorCache.clear()
    }

    /**
     * Remove a specific package from cache.
     */
    fun invalidate(packageName: String) {
        colorCache.remove(packageName)
    }

    private fun Drawable.toSmallBitmap(): Bitmap {
        // If already a bitmap, scale it down
        if (this is BitmapDrawable && this.bitmap != null) {
            return this.bitmap.scale(SAMPLE_SIZE, SAMPLE_SIZE)
        }

        // Create bitmap from drawable
        val width = if (intrinsicWidth > 0) intrinsicWidth else SAMPLE_SIZE
        val height = if (intrinsicHeight > 0) intrinsicHeight else SAMPLE_SIZE
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)

        // Scale down for faster processing
        return if (width > SAMPLE_SIZE || height > SAMPLE_SIZE) {
            bitmap.scale(SAMPLE_SIZE, SAMPLE_SIZE)
        } else {
            bitmap
        }
    }

    /**
     * Extract the dominant color from a bitmap using simple pixel sampling.
     * Ignores near-white, near-black, and low-saturation pixels.
     */
    @ColorInt
    private fun extractDominantColor(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // Color bucket map: quantized color -> count
        val colorCounts = mutableMapOf<Int, Int>()

        for (pixel in pixels) {
            val alpha = Color.alpha(pixel)
            if (alpha < 128) continue // Skip transparent pixels

            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            // Skip near-white and near-black
            val luminance = 0.299 * r + 0.587 * g + 0.114 * b
            if (luminance > 240 || luminance < 15) continue

            // Skip low saturation (grayish)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val saturation = if (max > 0) (max - min).toFloat() / max else 0f
            if (saturation < 0.2f) continue

            // Quantize to reduce color space (group similar colors)
            val quantized = quantizeColor(r, g, b)
            colorCounts[quantized] = (colorCounts[quantized] ?: 0) + 1
        }

        // Find most common color
        val dominant = colorCounts.maxByOrNull { it.value }?.key
        return dominant ?: DEFAULT_COLOR.toColorInt()
    }

    /**
     * Quantize RGB values to reduce color space.
     * Groups similar colors together for better dominant detection.
     */
    @ColorInt
    private fun quantizeColor(r: Int, g: Int, b: Int): Int {
        // Quantize to 32 levels per channel (8-bit -> 5-bit equivalent)
        val qr = (r / 8) * 8
        val qg = (g / 8) * 8
        val qb = (b / 8) * 8
        return Color.rgb(qr, qg, qb)
    }

    /**
     * Convert color int to hex string.
     */
    private fun toHexString(@ColorInt color: Int): String {
        return String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and color)
    }
}
