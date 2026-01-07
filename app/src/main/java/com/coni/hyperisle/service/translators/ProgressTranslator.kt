package com.coni.hyperisle.service.translators

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.coni.hyperisle.R
import com.coni.hyperisle.models.HyperIslandData
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.util.AccentColorResolver
import com.coni.hyperisle.util.IslandActivityStateMachine
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo
import kotlin.math.max

class ProgressTranslator(context: Context) : BaseTranslator(context) {

    private val finishKeywords by lazy {
        context.resources.getStringArray(R.array.progress_finish_keywords).toList()
    }

    // Maximum allowed progress jump per update (prevents jarring visual changes)
    private val MAX_PROGRESS_JUMP = 25

    /**
     * Data class for completion result.
     * @param isCompleted Whether the progress is complete
     * @param completionTimeoutMs Timeout before dismissal (only set if completed)
     */
    data class CompletionInfo(
        val isCompleted: Boolean,
        val completionTimeoutMs: Long? = null
    )

    fun translate(sbn: StatusBarNotification, title: String, picKey: String, config: IslandConfig): HyperIslandData {
        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", title)

        // --- CONFIGURATION ---
        val finalTimeout = config.timeout ?: 5000L
        val shouldFloat = if (finalTimeout == 0L) false else (config.isFloat ?: true)
        builder.setEnableFloat(shouldFloat)
        builder.setTimeout(finalTimeout)
        builder.setShowNotification(config.isShowShade ?: true)
        // ---------------------

        val extras = sbn.notification.extras

        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val textContent = (extras.getString(Notification.EXTRA_TEXT) ?: "")

        val rawPercent = if (max > 0) ((current.toFloat() / max.toFloat()) * 100).toInt() else 0

        // Smooth progress: ignore backward jitter and clamp extreme jumps
        val groupKey = "${sbn.packageName}:PROGRESS"
        val percent = smoothProgress(groupKey, rawPercent)

        val isTextFinished = finishKeywords.any { textContent.contains(it, ignoreCase = true) }
        val isFinished = percent >= 100 || isTextFinished

        val tickKey = "${picKey}_tick"
        val hiddenKey = "hidden_pixel"
        val greenColor = "#34C759"

        // Adaptive accent color from app icon
        val accentColor = AccentColorResolver.getAccentColor(context, sbn.packageName)

        // Resources
        builder.addPicture(resolveIcon(sbn, picKey))
        builder.addPicture(getTransparentPicture(hiddenKey))

        val actions = extractBridgeActions(sbn)
        val actionKeys = actions.map { it.action.key }

        // Expanded Info
        builder.setChatInfo(
            title = title,
            content = if (isFinished) "Download Complete" else textContent,
            pictureKey = picKey,
            actionKeys = actionKeys
        )

        // *** FIX: Correct setProgressBar Signature ***
        if (!isFinished && !indeterminate) {
            builder.setProgressBar(
                progress = percent, // Must be 0-100 Int
                color = accentColor,
            )
        }

        // Big Island
        if (isFinished) {
            builder.setBigIslandInfo(
                left = ImageTextInfoLeft(1, PicInfo(1, hiddenKey), TextInfo("", "")),
                right = ImageTextInfoRight(
                    1,
                    PicInfo(1, tickKey),
                    TextInfo("Finished", title, narrowFont = true)
                )
            )
            builder.setSmallIsland(tickKey)
        } else {
            builder.setBigIslandInfo(
                left = ImageTextInfoLeft(1, PicInfo(1, picKey), TextInfo("", "")),
                right = ImageTextInfoRight(
                    1,
                    PicInfo(1, hiddenKey),
                    TextInfo(title, "$percent%", narrowFont = true)
                )
            )

            if (!indeterminate) {
                // *** FIX: Added Title String Argument ***
                builder.setBigIslandProgressCircle(
                    picKey,
                    "", // Title inside circle (Empty)
                    percent,
                    accentColor,
                    true,
                )
                builder.setSmallIslandCircularProgress(picKey, percent, accentColor, isCCW = true)
            } else {
                builder.setSmallIsland(picKey)
            }
        }

        actions.forEach {
            builder.addAction(it.action)
            it.actionImage?.let { pic -> builder.addPicture(pic) }
        }

        return HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())
    }

    /**
     * Check if the current notification represents a completed progress.
     * Used by NotificationReaderService to trigger completion flow.
     */
    fun checkCompletion(sbn: StatusBarNotification): CompletionInfo {
        val extras = sbn.notification.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val textContent = extras.getString(Notification.EXTRA_TEXT) ?: ""

        val percent = if (max > 0) ((current.toFloat() / max.toFloat()) * 100).toInt() else 0
        val isTextFinished = finishKeywords.any { textContent.contains(it, ignoreCase = true) }
        val isCompleted = percent >= 100 || isTextFinished

        if (isCompleted) {
            val groupKey = "${sbn.packageName}:PROGRESS"
            val timeout = IslandActivityStateMachine.markCompleted(groupKey)
            return CompletionInfo(true, timeout ?: 2000L)
        }

        return CompletionInfo(false)
    }

    /**
     * Smooth progress updates:
     * - Ignore backward jitter (progress going backwards slightly)
     * - Clamp extreme forward jumps to prevent jarring visuals
     */
    private fun smoothProgress(groupKey: String, newProgress: Int): Int {
        val lastProgress = IslandActivityStateMachine.getLastProgress(groupKey) ?: return newProgress

        // Ignore backward jitter (allow small backward movement for corrections)
        if (newProgress < lastProgress) {
            val diff = lastProgress - newProgress
            // Allow backward movement only if it's significant (>10%) - likely a real reset
            return if (diff > 10) newProgress else lastProgress
        }

        // Clamp extreme forward jumps
        val jump = newProgress - lastProgress
        return if (jump > MAX_PROGRESS_JUMP) {
            // Allow the jump but cap it to prevent jarring visual change
            max(lastProgress + MAX_PROGRESS_JUMP, newProgress.coerceAtMost(100))
        } else {
            newProgress
        }
    }
}