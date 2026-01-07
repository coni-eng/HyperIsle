package com.coni.hyperisle.service.translators

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import com.coni.hyperisle.R
import com.coni.hyperisle.models.HyperIslandData
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.util.AccentColorResolver
import com.coni.hyperisle.util.FocusActionHelper
import io.github.d4viddf.hyperisland_kit.HyperAction
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

class StandardTranslator(context: Context) : BaseTranslator(context) {

    fun translate(sbn: StatusBarNotification, picKey: String, config: IslandConfig, notificationId: Int = 0): HyperIslandData {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: sbn.packageName
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""

        val isMedia = template.contains("MediaStyle")
        val isCall = sbn.notification.category == Notification.CATEGORY_CALL

        val displayTitle = title
        val displayContent = when {
            isMedia -> {
                // For media, prefer actual metadata over static "Now Playing"
                when {
                    text.isNotEmpty() && subText.isNotEmpty() -> "$text • $subText"
                    text.isNotEmpty() -> text
                    subText.isNotEmpty() -> subText
                    else -> context.getString(R.string.status_now_playing)
                }
            }
            isCall && subText.isNotEmpty() -> "$text • $subText"
            subText.isNotEmpty() -> if (text.isNotEmpty()) "$text • $subText" else subText
            else -> text
        }

        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", displayTitle)

        // --- CONFIGURATION ---
        val finalTimeout = config.timeout ?: 5000L
        // If timeout is 0, we force float to false to prevent stuck heads-up
        val shouldFloat = if (finalTimeout == 0L) false else (config.isFloat ?: true)

        builder.setEnableFloat(shouldFloat)
        builder.setTimeout(finalTimeout)
        builder.setShowNotification(config.isShowShade ?: true)
        // ---------------------

        val hiddenKey = "hidden_pixel"
        builder.addPicture(resolveIcon(sbn, picKey))
        builder.addPicture(getTransparentPicture(hiddenKey))

        val actions = extractBridgeActions(sbn)
        val actionKeys = actions.map { it.action.key }

        // Action Logic: Move to Hint if > 1 (Optional, keeping standard behavior for now)
        builder.setBaseInfo(
            title = displayTitle,
            content = displayContent,
            pictureKey = picKey,
            actionKeys = actionKeys
        )

        // Standard layout for all non-media notifications
        // Note: Media notifications are handled by HyperOS natively (SYSTEM_ONLY mode)
        // Type 2 for ImageTextInfoRight renders as a close button on the expanded island
        builder.setBigIslandInfo(
            left = ImageTextInfoLeft(1, PicInfo(1, picKey), TextInfo("", "")),
            right = ImageTextInfoRight(2, PicInfo(1, hiddenKey), TextInfo(displayTitle, displayContent))
        )
        builder.setSmallIsland(picKey)

        actions.forEach {
            builder.addAction(it.action)
            it.actionImage?.let { iconPic -> builder.addPicture(iconPic) }
        }

        // Add Options and Dismiss actions for non-media notifications only
        if (!isMedia) {
            val optionsAction = createOptionsAction(notificationId)
            val dismissAction = createDismissAction(notificationId)
            builder.addAction(optionsAction)
            builder.addAction(dismissAction)
        }

        return HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())
    }

    /**
     * Creates the "Options" action that opens Quick Actions UI.
     * Uses explicit receiver and unique key per island.
     */
    private fun createOptionsAction(notificationId: Int): HyperAction {
        val actionString = FocusActionHelper.buildActionString(FocusActionHelper.TYPE_OPTIONS, notificationId)
        val intent = Intent(actionString)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "options_$notificationId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return HyperAction(
            key = "options_$notificationId",
            title = context.getString(R.string.action_options),
            pendingIntent = pendingIntent,
            actionIntentType = 2 // Broadcast
        )
    }

    /**
     * Creates the "Dismiss" action that cancels island + records cooldown.
     * Uses explicit receiver and unique key per island.
     */
    private fun createDismissAction(notificationId: Int): HyperAction {
        val actionString = FocusActionHelper.buildActionString(FocusActionHelper.TYPE_DISMISS, notificationId)
        val intent = Intent(actionString)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "dismiss_$notificationId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return HyperAction(
            key = "dismiss_$notificationId",
            title = context.getString(R.string.action_dismiss),
            pendingIntent = pendingIntent,
            actionIntentType = 2 // Broadcast
        )
    }
}