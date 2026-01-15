package com.coni.hyperisle.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coni.hyperisle.util.CallManager
import com.coni.hyperisle.util.HiLog

class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_HANGUP = "com.coni.hyperisle.action.CALL_HANGUP"
        const val ACTION_SPEAKER = "com.coni.hyperisle.action.CALL_SPEAKER"
        const val ACTION_MUTE = "com.coni.hyperisle.action.CALL_MUTE"
        const val ACTION_ACCEPT = "com.coni.hyperisle.action.CALL_ACCEPT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        HiLog.d(HiLog.TAG_ISLAND, "CallActionReceiver received action: $action")

        when (action) {
            ACTION_HANGUP -> {
                CallManager.endCall(context)
            }
            ACTION_SPEAKER -> {
                CallManager.toggleSpeaker(context, null)
            }
            ACTION_MUTE -> {
                CallManager.toggleMute(context, null)
            }
            ACTION_ACCEPT -> {
                CallManager.acceptCall(context, null)
            }
        }
    }
}
