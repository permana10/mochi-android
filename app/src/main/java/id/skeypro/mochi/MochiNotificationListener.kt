package id.skeypro.mochi

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MochiNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "MochiNotif"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification.extras
            val app = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (_: Exception) {
                sbn.packageName.substringAfterLast('.')
            }

            val sender = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.trim().orEmpty().ifBlank { "Notifikasi" }

            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString().orEmpty()
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" ") { it.toString() }.orEmpty()

            val message = listOf(bigText, text, lines)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")

            if (message.isBlank()) return
            // Avoid forwarding this app's own notifications.
            if (sbn.packageName == packageName) return

            val safeFields = listOf(app, sender, message).map {
                it.replace("|", " ").replace("\\n", " ").replace("\\r", " ").trim()
            }
            MochiBleService.sendCommand(
                applicationContext,
                "NOTIF|${safeFields[0]}|${safeFields[1]}|${safeFields[2]}"
            )
            Log.d(TAG, "Forwarded notification from $app")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward notification", e)
        }
    }
}
