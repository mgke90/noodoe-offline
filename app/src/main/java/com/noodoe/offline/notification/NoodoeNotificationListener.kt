package com.noodoe.offline.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.noodoe.offline.NoodoeApp

class NoodoeNotificationListener : NotificationListenerService() {

    private var lastPackage: String? = null
    private var lastContent: String? = null
    private var lastAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val controller = (application as NoodoeApp).controller
        if (!controller.settings.notifyApps) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)
            ?.toString()?.trim().orEmpty()
        val lines = extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)
        val body = when {
            text.isNotEmpty() -> text
            !lines.isNullOrEmpty() -> lines
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
            else -> ""
        }
        if (title.isEmpty() && body.isEmpty()) return

        val content = when {
            title.isEmpty() -> body
            body.isEmpty() -> title
            else -> "$title: $body"
        }.take(180)

        val now = System.currentTimeMillis()
        if (sbn.packageName == lastPackage &&
            content == lastContent &&
            now - lastAt < 3000L
        ) {
            return
        }
        lastPackage = sbn.packageName
        lastContent = content
        lastAt = now

        val appId = when {
            sbn.packageName in APPS_WITH_CUSTOM_ICON -> sbn.packageName
            notification.category == NotificationCompat.CATEGORY_MESSAGE ||
                    notification.category == NotificationCompat.CATEGORY_SOCIAL -> "chat"
            notification.category == "email" -> "email"
            else -> sbn.packageName
        }
        val appName = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        controller.notifyApp(appId, appName, content)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        (application as NoodoeApp).controller.log("notification access connected")
    }

    private companion object {
        val APPS_WITH_CUSTOM_ICON = setOf(
            "com.facebook.katana",
            "com.cnn.mobile.android.phone",
            "com.whatsapp",
            "com.noodoe.sunray.alpha",
            "com.noodoe.sunray.dev",
            "com.bbm",
            "com.instagram.android",
            "com.kakao.talk",
            "jp.naver.line.android",
            "com.facebook.orca",
            "com.tencent.mobileqq",
            "com.snapchat.android",
            "com.twitter.android",
            "com.viber.voip",
            "com.tencent.mm",
            "com.zing.zalo"
        )
    }
}
