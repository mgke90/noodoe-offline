package com.noodoe.offline.notification

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.noodoe.offline.NoodoeApp

class NoodoeSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val controller = (context.applicationContext as NoodoeApp).controller
        if (!controller.settings.notifySms) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: Exception) {
            return
        }
        if (messages.isNullOrEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress
            ?.takeIf { it.isNotBlank() } ?: "Unknown"
        val body = messages.joinToString("") {
            it.displayMessageBody ?: ""
        }.trim().take(180)
        controller.notifySms(sender, body)
    }
}
