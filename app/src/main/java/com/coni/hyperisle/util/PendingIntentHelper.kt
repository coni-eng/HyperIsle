package com.coni.hyperisle.util

import android.app.PendingIntent
import com.coni.hyperisle.util.HiLog



/**
 * Helper for safely inferring PendingIntent types for HyperAction.
 * 
 * HyperOS action intent types:
 *   1 = Activity
 *   2 = Broadcast
 *   3 = Service
 * 
 * BACKGROUND:
 * Android's PendingIntent can wrap Activity, Broadcast, or Service intents.
 * The Kit's HyperAction requires specifying the correct type for proper dispatch.
 * Previously, the code hardcoded type=1 (Activity), which is incorrect for
 * Broadcast or Service PendingIntents.
 * 
 * APPROACH:
 * - On API 30+, use PendingIntent reflection to check internal flags (best effort)
 * - If detection fails or is unreliable, fall back to ACTIVITY (preserves existing behavior)
 * - This is defensive: we prefer correct inference but never break existing functionality
 * 
 * SAFETY NOTE:
 * Android does NOT expose a reliable public API to determine PendingIntent type.
 * All inference logic here is best-effort based on toString() parsing and reflection.
 * The code is wrapped in try/catch(Throwable) to ensure it NEVER crashes.
 * When inference fails, we always fall back to TYPE_ACTIVITY (safe default).
 */
object PendingIntentHelper {

    private const val TAG = "PendingIntentHelper"

    // HyperOS action intent type constants
    const val TYPE_ACTIVITY = 1
    const val TYPE_BROADCAST = 2
    const val TYPE_SERVICE = 3

    // PendingIntent internal type flags (from Android source)
    // These are internal constants used by PendingIntent implementation
    private const val PENDING_INTENT_TYPE_ACTIVITY = 2
    private const val PENDING_INTENT_TYPE_BROADCAST = 1
    private const val PENDING_INTENT_TYPE_SERVICE = 4

    /**
     * Result of PendingIntent type inference, including whether fallback was used.
     */
    data class InferenceResult(val type: Int, val fallbackUsed: Boolean)

    /**
     * Infers the HyperOS action intent type from a PendingIntent.
     * 
     * @param pendingIntent The PendingIntent to analyze
     * @return The inferred type (1=Activity, 2=Broadcast, 3=Service)
     * 
     * SAFETY: If inference fails, returns TYPE_ACTIVITY to preserve existing behavior.
     * This method is wrapped in try/catch(Throwable) to ensure it NEVER crashes.
     */
    fun inferIntentType(pendingIntent: PendingIntent?): Int {
        return inferIntentTypeWithFallbackInfo(pendingIntent).type
    }

    /**
     * Infers the HyperOS action intent type from a PendingIntent, also returning
     * whether fallback was used (for diagnostics).
     * 
     * @param pendingIntent The PendingIntent to analyze
     * @return InferenceResult with type and fallbackUsed flag
     */
    fun inferIntentTypeWithFallbackInfo(pendingIntent: PendingIntent?): InferenceResult {
        if (pendingIntent == null) {
            return InferenceResult(TYPE_ACTIVITY, fallbackUsed = true)
        }

        return try {
            val result = inferIntentTypeInternal(pendingIntent)
            InferenceResult(result.first, result.second)
        } catch (t: Throwable) {
            // Any failure (including Error subclasses) -> fall back to Activity (existing behavior)
            HiLog.d(HiLog.TAG_ISLAND, "PendingIntent type inference failed, defaulting to ACTIVITY: ${t.message}")
            InferenceResult(TYPE_ACTIVITY, fallbackUsed = true)
        }
    }

    /**
     * Internal inference using reflection on PendingIntent internals.
     * 
     * NOTE: This uses reflection on private fields which may change across Android versions.
     * The approach is defensive - any failure returns the safe default.
     * 
     * @return Pair of (type, fallbackUsed)
     */
    private fun inferIntentTypeInternal(pendingIntent: PendingIntent): Pair<Int, Boolean> {
        // Method 1: Try to get the internal "mType" or similar field via toString() parsing
        // PendingIntent.toString() often contains type info like "PendingIntent{...type activity...}"
        val description = pendingIntent.toString().lowercase()
        
        return when {
            description.contains("broadcast") -> {
                HiLog.d(HiLog.TAG_ISLAND, "Inferred BROADCAST from PendingIntent description")
                Pair(TYPE_BROADCAST, false)
            }
            description.contains("service") -> {
                HiLog.d(HiLog.TAG_ISLAND, "Inferred SERVICE from PendingIntent description")
                Pair(TYPE_SERVICE, false)
            }
            description.contains("activity") -> {
                HiLog.d(HiLog.TAG_ISLAND, "Inferred ACTIVITY from PendingIntent description")
                Pair(TYPE_ACTIVITY, false)
            }
            else -> {
                // Method 2: Try reflection on API 30+ if toString() didn't help
                inferViaReflection(pendingIntent)
            }
        }
    }

    /**
     * Attempts to infer type via reflection on PendingIntent's internal fields.
     * This is a best-effort approach and may not work on all devices/ROMs.
     * 
     * @return Pair of (type, fallbackUsed)
     */
    /**
     * Best-effort inference using ONLY public APIs.
     * NOTE: Reflection on PendingIntent internals (mTarget, etc.) is not allowed and will throw on API 36+.
     *
     * @return Pair of (type, fallbackUsed)
     */
    private fun inferViaReflection(pendingIntent: PendingIntent): Pair<Int, Boolean> {
        return try {
            // Use public APIs when available; never crash if ROM/framework differs.
            when {
                runCatching { pendingIntent.isBroadcast }.getOrDefault(false) ->
                    Pair(TYPE_BROADCAST, false)

                runCatching { pendingIntent.isService }.getOrDefault(false) ->
                    Pair(TYPE_SERVICE, false)

                runCatching { pendingIntent.isActivity }.getOrDefault(false) ->
                    Pair(TYPE_ACTIVITY, false)

                else ->
                    // Unknown → keep historical safe default
                    Pair(TYPE_ACTIVITY, true)
            }
        } catch (_: Throwable) {
            Pair(TYPE_ACTIVITY, true)
        }
    }
}
