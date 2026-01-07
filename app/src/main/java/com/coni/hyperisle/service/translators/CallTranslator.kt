package com.coni.hyperisle.service.translators

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.Log
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.models.BridgeAction
import com.coni.hyperisle.models.HyperIslandData
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.util.DebugTimeline
import com.coni.hyperisle.util.PendingIntentHelper
import io.github.d4viddf.hyperisland_kit.HyperAction
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

class CallTranslator(context: Context) : BaseTranslator(context) {

    private val TAG = "CallTranslator"

    // Keywords (Loaded from XML)
    private val hangUpKeywords by lazy {
        context.resources.getStringArray(R.array.call_keywords_hangup).toList()
    }
    private val answerKeywords by lazy {
        context.resources.getStringArray(R.array.call_keywords_answer).toList()
    }
    private val speakerKeywords by lazy {
        context.resources.getStringArray(R.array.call_keywords_speaker).toList()
    }

    fun translate(sbn: StatusBarNotification, picKey: String, config: IslandConfig, durationSeconds: Long? = null): HyperIslandData {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: "Call"

        val isChronometerShown = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)

        val actions = sbn.notification.actions ?: emptyArray()
        val hasAnswerAction = actions.any { action ->
            val txt = action.title.toString().lowercase(java.util.Locale.getDefault())
            answerKeywords.any { k -> txt.contains(k) }
        }

        val isIncoming = !isChronometerShown && hasAnswerAction
        val isOngoing = !isIncoming && isChronometerShown

        // Debug-only lifecycle logging
        val callState = when {
            isIncoming -> "INCOMING"
            isOngoing -> "ONGOING"
            else -> "ENDED"
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "event=callState $callState pkg=${sbn.packageName} keyHash=${sbn.key.hashCode()}")
        }
        
        // Timeline: call state transition event
        DebugTimeline.log(
            "callStateTransition",
            sbn.packageName,
            sbn.key.hashCode(),
            mapOf("callState" to callState)
        )

        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", title)

        val finalTimeout = config.timeout ?: 5000L
        // Keep calls persistent/floating if active
        val shouldFloat = if (finalTimeout == 0L) false else (config.isFloat ?: true)
        builder.setEnableFloat(shouldFloat)
        builder.setTimeout(finalTimeout)
        builder.setShowNotification(config.isShowShade ?: true)

        // Ongoing calls: collapse to small island (no expanded state)
        // Incoming calls: allow expanded state for accept/reject visibility
        builder.setIslandFirstFloat(!isOngoing)

        val hiddenKey = "hidden_pixel"
        builder.addPicture(resolveIcon(sbn, picKey))
        builder.addPicture(getTransparentPicture(hiddenKey))

        // --- ACTIONS ---
        val bridgeActions = getFilteredCallActions(sbn, isIncoming)

        bridgeActions.forEach {
            builder.addAction(it.action)
            it.actionImage?.let { pic -> builder.addPicture(pic) }
        }
        val actionKeys = bridgeActions.map { it.action.key }

        // --- TEXT ---
        val rightText: String

        if (isIncoming) {
            rightText = context.getString(R.string.call_incoming)
        } else {
            val subText = extras.getString(Notification.EXTRA_TEXT)
            // Priority: 1. System-provided time (subText with ":"), 2. Our timer, 3. Fallback label
            rightText = when {
                !subText.isNullOrEmpty() && subText.contains(":") -> subText
                durationSeconds != null -> formatDuration(durationSeconds)
                else -> context.getString(R.string.call_ongoing)
            }
        }

        builder.setBaseInfo(
            title = title,
            content = rightText,
            pictureKey = picKey,
            actionKeys = actionKeys
        )

        builder.setBigIslandInfo(
            left = ImageTextInfoLeft(1, PicInfo(1, picKey), TextInfo(title, "", narrowFont = true)),
            right = ImageTextInfoRight(2, PicInfo(1, hiddenKey), TextInfo(rightText, "", narrowFont = true))
        )

        builder.setSmallIsland(picKey)

        return HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())
    }

    private fun getFilteredCallActions(sbn: StatusBarNotification, isIncoming: Boolean): List<BridgeAction> {
        val rawActions = sbn.notification.actions ?: return emptyList()
        val results = mutableListOf<BridgeAction>()

        var answerIndex = -1
        var hangUpIndex = -1
        var speakerIndex = -1

        rawActions.forEachIndexed { index, action ->
            val txt = action.title.toString().lowercase(java.util.Locale.getDefault())
            if (answerKeywords.any { txt.contains(it) }) answerIndex = index
            else if (hangUpKeywords.any { txt.contains(it) }) hangUpIndex = index
            else if (speakerKeywords.any { txt.contains(it) }) speakerIndex = index
        }

        val indicesToShow = mutableListOf<Int>()

        if (isIncoming) {
            // 1. Decline (Red), 2. Answer (Green)
            if (hangUpIndex != -1) indicesToShow.add(hangUpIndex)
            if (answerIndex != -1) indicesToShow.add(answerIndex)
        } else {
            // 1. Speaker (Grey), 2. Hang Up (Red)
            if (speakerIndex != -1) indicesToShow.add(speakerIndex)
            if (hangUpIndex != -1) indicesToShow.add(hangUpIndex)
        }

        // Fallback
        if (indicesToShow.isEmpty()) {
            if (rawActions.isNotEmpty()) indicesToShow.add(0)
            if (rawActions.size > 1) indicesToShow.add(1)
        }

        indicesToShow.take(2).forEach { index ->
            val action = rawActions[index]
            val title = action.title?.toString() ?: ""
            val uniqueKey = "act_${sbn.key.hashCode()}_$index"

            var hyperPic: HyperPicture? = null
            var actionIcon: Icon? = null

            val isHangUp = index == hangUpIndex
            val isAnswer = index == answerIndex

            // DETERMINE BACKGROUND COLOR - More vibrant for better visibility
            val bgColor = when {
                isHangUp -> "#E53935" // Vibrant Red (Material Red 600)
                isAnswer -> "#43A047" // Vibrant Green (Material Green 600)
                else -> "#616161"     // Grey for other actions
            }

            // LOAD & TINT ICON
            val originalIcon = action.getIcon()
            val originalBitmap = if (originalIcon != null) loadIconBitmap(originalIcon, sbn.packageName) else null

            if (originalBitmap != null) {
                // Tint icon WHITE for contrast against colored background
                val finalBitmap = tintBitmap(originalBitmap, Color.WHITE)

                val picKey = "${uniqueKey}_icon"
                actionIcon = Icon.createWithBitmap(finalBitmap)
                hyperPic = HyperPicture(picKey, finalBitmap)
            }

            // Infer intent type from PendingIntent (Activity/Broadcast/Service)
            // Falls back to Activity if detection fails, preserving existing behavior
            val inferredIntentType = PendingIntentHelper.inferIntentType(action.actionIntent)

            val hyperAction = HyperAction(
                key = uniqueKey,
                title = null, // Title usually hidden if icon is prominent in island style
                icon = actionIcon,
                pendingIntent = action.actionIntent,
                actionIntentType = inferredIntentType,
                actionBgColor = bgColor
            )

            results.add(BridgeAction(hyperAction, hyperPic))
        }

        return results
    }

    private fun tintBitmap(source: Bitmap, color: Int): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }
}