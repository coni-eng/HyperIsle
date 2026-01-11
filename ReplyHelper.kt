@file:Suppress("unused")

package com.example.mydynamicisland.notification

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Helper for sending Quick Reply via RemoteInput.
 * 
 * **Process:**
 * 1. Create Intent
 * 2. Build Bundle with reply text
 * 3. Add RemoteInput results to Intent using RemoteInput.addResultsToIntent()
 * 4. Send via PendingIntent.send()
 * 
 * **Compatibility:**
 * - Android 4.4+ (KitKat Watch) for RemoteInput
 * - Exception handling for all failure cases
 * - Wrap EVERYTHING in try-catch
 * 
 * **Usage:**
 * - Called from OverlayService when user sends quick reply
 * - Requires replyAction (PendingIntent) and replyInputs (Array<RemoteInput>)
 */
object ReplyHelper {

    private const val TAG = "ReplyHelper"

    /**
     * Send quick reply via RemoteInput.
     * 
     * **Process:**
     * 1. Create Intent
     * 2. Build Bundle with reply text
     * 3. Add RemoteInput results to Intent using RemoteInput.addResultsToIntent()
     * 4. Send via PendingIntent.send()
     * 
     * **Exception Handling:**
     * - Wrap EVERYTHING in try-catch
     * - Standard RemoteInput reply logic
     * 
     * @param context Application context
     * @param replyAction PendingIntent for reply action
     * @param replyInputs Array of RemoteInput (usually single element)
     * @param replyText Text to send as reply
     * @return true if sent successfully, false otherwise
     */
    fun sendReply(
        context: Context,
        replyAction: PendingIntent?,
        replyInputs: Array<RemoteInput>?,
        replyText: String
    ): Boolean {
        if (replyAction == null) {
            Log.w(TAG, "Reply action is null")
            return false
        }

        if (replyInputs.isNullOrEmpty()) {
            Log.w(TAG, "Reply inputs are null or empty")
            return false
        }

        if (replyText.isBlank()) {
            Log.w(TAG, "Reply text is blank")
            return false
        }

        // Wrap EVERYTHING in try-catch
        return try {
            // Create Intent
            val intent = Intent()

            // Build Bundle with reply text
            // Use first RemoteInput's resultKey
            val resultKey = replyInputs[0].resultKey
            val bundle = Bundle().apply {
                putCharSequence(resultKey, replyText)
            }

            // Add RemoteInput results to Intent using RemoteInput.addResultsToIntent()
            // minSdk=26, so SDK_INT is always >= KITKAT_WATCH (API 20)
            RemoteInput.addResultsToIntent(replyInputs, intent, bundle)

            // Send via PendingIntent.send()
            replyAction.send(context, 0, intent)

            Log.d(TAG, "Quick reply sent successfully: text=$replyText")
            true

        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "PendingIntent canceled while sending reply", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while sending reply", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException while sending reply", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending reply", e)
            false
        }
    }

    /**
     * Extract reply text from Intent (for testing/debugging).
     * 
     * Used when receiving reply in BroadcastReceiver.
     */
    fun extractReplyText(intent: Intent, resultKey: String): String? {
        return try {
            // minSdk=26, so SDK_INT is always >= KITKAT_WATCH (API 20)
            val results = RemoteInput.getResultsFromIntent(intent)
            results?.getCharSequence(resultKey)?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting reply text", e)
            null
        }
    }
}
