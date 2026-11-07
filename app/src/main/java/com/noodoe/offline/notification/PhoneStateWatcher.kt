package com.noodoe.offline.notification

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.noodoe.offline.NoodoeApp

/** Keeps one call-state listener for the lifetime of the process. */
@SuppressLint("MissingPermission")
class PhoneStateWatcher private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val controller = (appContext as NoodoeApp).controller
    private val telephony = appContext
        .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var registered = false
    private var ringing = false
    private var lastCaller = "Unknown"

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val caller = phoneNumber?.takeIf { it.isNotBlank() } ?: "Unknown"
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!ringing) {
                        ringing = true
                        lastCaller = caller
                        controller.notifyIncomingCall(caller)
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (ringing) {
                        ringing = false
                        controller.notifyCallStop()
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    if (ringing) {
                        ringing = false
                        controller.notifyMissedCall(lastCaller)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun register() {
        if (registered) return
        if (!controller.settings.notifyCalls) return
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            telephony?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            registered = true
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    fun unregister() {
        if (!registered) return
        try {
            telephony?.listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {
        }
        registered = false
        ringing = false
    }

    companion object {
        @Volatile
        private var instance: PhoneStateWatcher? = null

        @Synchronized
        fun update(context: Context) {
            val watcher = instance ?: PhoneStateWatcher(context).also { instance = it }
            val controller = (context.applicationContext as NoodoeApp).controller
            if (controller.settings.notifyCalls) watcher.register() else watcher.unregister()
        }
    }
}
