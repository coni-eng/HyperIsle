package com.d4viddf.hyperbridge.util

import android.content.Context
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification

object DeviceCompatibility {
    fun isXiaomiDevice(context: Context): Boolean {
        // Uses the Toolkit's native check (validates HyperOS integration)
        return HyperIslandNotification.isSupported(context)
    }
}