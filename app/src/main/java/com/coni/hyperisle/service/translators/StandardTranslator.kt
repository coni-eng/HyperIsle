package com.coni.hyperisle.service.translators

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.Log
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.models.HyperIslandData
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.receiver.IslandActionReceiver
import com.coni.hyperisle.util.AccentColorResolver
import com.coni.hyperisle.util.FocusActionHelper
import com.coni.hyperisle.util.IslandStyleContract
import io.github.d4viddf.hyperisland_kit.HyperAction
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

class StandardTranslator(context: Context) : BaseTranslator(context) {

    fun translate(sbn: StatusBarNotification, picKey: String, config: IslandConfig, notificationId: Int = 0, styleResult: IslandStyleContract.StyleResult? = null): HyperIslandData {
        val extras = sbn.notification.extras
        val rawTitle = extras.getString(Notification.EXTRA_TITLE)
        val rawText = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""

        val isMedia = template.contains("MediaStyle")
        val isCall = sbn.notification.category == Notification.CATEGORY_CALL

        // --- UI FALLBACK GUARANTEE (Contract A) ---
        // If classification fails (no title/subtitle), render minimal pill: app icon + app name + "New notification"
        // This ensures Island is ALWAYS shown for allowed apps, never "nothing shown"
        val appName = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName.substringAfterLast('.')
        }
        
        val title = rawTitle ?: appName
        val needsFallback = rawTitle.isNullOrEmpty() && rawText.isEmpty() && subText.isEmpty()
        
        val displayTitle = title
        val displayContent = when {
            needsFallback -> context.getString(R.string.fallback_new_notification)
            isMedia -> {
                // For media, prefer actual metadata over static "Now Playing"
                when {
                    rawText.isNotEmpty() && subText.isNotEmpty() -> "$rawText • $subText"
                    rawText.isNotEmpty() -> rawText
                    subText.isNotEmpty() -> subText
                    else -> context.getString(R.string.status_now_playing)
                }
            }
            isCall && subText.isNotEmpty() -> "$rawText • $subText"
            subText.isNotEmpty() -> if (rawText.isNotEmpty()) "$rawText • $subText" else subText
            else -> rawText
        }

        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", displayTitle)

        // --- CONFIGURATION ---
        val finalTimeout = config.timeout ?: 5000L
        // If timeout is 0, we force float to false to prevent stuck heads-up
        val shouldFloat = if (finalTimeout == 0L) false else (config.isFloat ?: true)

        builder.setEnableFloat(shouldFloat)
        builder.setTimeout(finalTimeout)
        builder.setShowNotification(config.isShowShade ?: true)
        // Enable swipe-to-dismiss for user gesture handling
        builder.setIslandConfig(dismissible = true)
        // ---------------------

        val hiddenKey = "hidden_pixel"
        builder.addPicture(resolveIcon(sbn, picKey))
        builder.addPicture(getTransparentPicture(hiddenKey))

        val rawActions = extractBridgeActions(sbn)
        
        // --- ISLAND STYLE CONTRACT ENFORCEMENT ---
        // When legacy style is blocked, limit actions to prevent expanded action-row layout
        val actions = if (styleResult?.wasBlocked == true) {
            // Limit to 2 actions max to ensure MODERN_PILL style renders correctly
            rawActions.take(2)
        } else {
            rawActions
        }
        val bridgeActionKeys = actions.map { it.action.key }

        // Wire options and dismiss actions to expanded island controls
        val optionsKey = "options_$notificationId"
        val dismissKey = "dismiss_$notificationId"
        val actionKeys = if (!isMedia) {
            bridgeActionKeys + listOf(optionsKey, dismissKey)
        } else {
            bridgeActionKeys
        }

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
        // UI Polish: Use narrowFont for cleaner text rendering and better spacing
        builder.setBigIslandInfo(
            left = ImageTextInfoLeft(1, PicInfo(1, picKey), TextInfo("", "")),
            right = ImageTextInfoRight(
                2,
                PicInfo(1, hiddenKey),
                TextInfo(
                    title = displayTitle,
                    content = displayContent,
                    narrowFont = true
                )
            )
        )
        builder.setSmallIsland(picKey)

        actions.forEach {
            builder.addAction(it.action)
            it.actionImage?.let { iconPic -> builder.addPicture(iconPic) }
        }

        // Add Options and Dismiss actions for non-media notifications only
        if (!isMedia) {
            val (optionsAction, optionsIcon) = createOptionsAction(notificationId, sbn.packageName)
            val (dismissAction, dismissIcon) = createDismissAction(notificationId)
            builder.addPicture(optionsIcon)
            builder.addPicture(dismissIcon)
            builder.addAction(optionsAction)
            builder.addAction(dismissAction)
        }

        if (BuildConfig.DEBUG) {
            val keyHash = sbn.key.hashCode()
            val actionKeyList = actionKeys.joinToString("|")
            val showShade = config.isShowShade ?: true
            Log.d(
                "HyperIsleIsland",
                "RID=$keyHash EVT=MIUI_UI_BUILD type=STANDARD titleLen=${displayTitle.length} contentLen=${displayContent.length} actions=${actionKeys.size} actionKeys=$actionKeyList picKey=$picKey hiddenKey=$hiddenKey float=$shouldFloat timeout=$finalTimeout showShade=$showShade dismissible=true media=$isMedia"
            )
        }

        return HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())
    }

    /**
     * Creates the "Options" action that opens Quick Actions UI.
     * Uses explicit receiver and unique key per island.
     * Icon: Settings gear with subtle primary color tint (20dp, 48dp touch target)
     */
    private fun createOptionsAction(notificationId: Int, packageName: String): Pair<HyperAction, io.github.d4viddf.hyperisland_kit.HyperPicture> {
        val actionString = FocusActionHelper.buildActionString(FocusActionHelper.TYPE_OPTIONS, notificationId)
        val intent = Intent(actionString).apply {
            setPackage(context.packageName)
            setClass(context, IslandActionReceiver::class.java)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "options_$notificationId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val accentColor = AccentColorResolver.getAccentColorOrDefault(context, packageName, "#6750A4")
        val iconKey = "options_icon_$notificationId"
        val iconPicture = getColoredPicture(iconKey, R.drawable.ic_action_settings, accentColor)

        val action = HyperAction(
            key = "options_$notificationId",
            title = "",
            icon = iconPicture.icon,
            pendingIntent = pendingIntent,
            actionIntentType = 2 // Broadcast
        )
        
        return Pair(action, iconPicture)
    }

    /**
     * Creates the "Dismiss" action that cancels island + records cooldown.
     * Uses explicit receiver and unique key per island.
     * Icon: Close X with subtle error color tint (20dp, 48dp touch target)
     */
    private fun createDismissAction(notificationId: Int): Pair<HyperAction, io.github.d4viddf.hyperisland_kit.HyperPicture> {
        val actionString = FocusActionHelper.buildActionString(FocusActionHelper.TYPE_DISMISS, notificationId)
        val intent = Intent(actionString).apply {
            setPackage(context.packageName)
            setClass(context, IslandActionReceiver::class.java)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "dismiss_$notificationId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconKey = "dismiss_icon_$notificationId"
        val iconPicture = getColoredPicture(iconKey, R.drawable.ic_action_close, "#B3261E")

        val action = HyperAction(
            key = "dismiss_$notificationId",
            title = "",
            icon = iconPicture.icon,
            pendingIntent = pendingIntent,
            actionIntentType = 2 // Broadcast
        )
        
        return Pair(action, iconPicture)
    }
}
