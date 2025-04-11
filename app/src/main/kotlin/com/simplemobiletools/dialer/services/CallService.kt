package com.simplemobiletools.dialer.services

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import androidx.annotation.RequiresApi
import com.simplemobiletools.dialer.activities.CallActivity
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.extensions.isOutgoing
import com.simplemobiletools.dialer.extensions.powerManager
import com.simplemobiletools.dialer.helpers.CallManager
import com.simplemobiletools.dialer.helpers.CallNotificationManager
import com.simplemobiletools.dialer.helpers.NoCall

class CallService : InCallService() {

    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(this, call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        // ✅ Start recording the call via service intent
        val recordIntent = Intent(this, CallRecordingService::class.java).apply {
            putExtra("AUTO_RECORD_ENABLED", true)  // You can change to use preferences later
        }
        startForegroundService(recordIntent)

        val isScreenLocked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
        if (!powerManager.isInteractive || call.isOutgoing() || isScreenLocked || config.alwaysShowFullscreen) {
            try {
                callNotificationManager.setupNotification(true)
                startActivity(CallActivity.getStartIntent(this))
            } catch (e: Exception) {
                callNotificationManager.setupNotification()
            }
        } else {
            callNotificationManager.setupNotification()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)

        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(this, call)

        val stopIntent = Intent(this, CallRecordingService::class.java).apply {
            action = "STOP_RECORDING"
        }
        stopService(stopIntent)

        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState?.let { CallManager.onAudioStateChanged(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }
}
