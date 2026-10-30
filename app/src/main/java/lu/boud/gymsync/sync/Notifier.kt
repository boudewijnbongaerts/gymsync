package lu.boud.gymsync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import lu.boud.gymsync.MainActivity
import lu.boud.gymsync.data.Store

object Notifier {
    private const val CHANNEL = "sync_problems"
    private const val ID = 1
    const val THRESHOLD = 3

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sync problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** After [THRESHOLD] consecutive failed syncs, notify once (reset by the next success). */
    suspend fun maybeNotifyFailures(context: Context, store: Store, consecutiveFailures: Int) {
        if (consecutiveFailures < THRESHOLD) return
        if (store.markFailureNotified()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(context)
        val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("GymSync can't sync")
            .setContentText("$consecutiveFailures syncs in a row failed. Open the app for details.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(ID, n) } catch (e: SecurityException) { /* permission revoked */ }
    }
}
