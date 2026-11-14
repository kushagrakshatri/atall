package app.atall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * One notification per talk (ux.md §10): nothing for rounds or replies, one when Juno's wrap-up lands, and only when
 * Atall isn't on screen. It opens the thread; it never acts from the shade. Posting silently does nothing when the
 * user hasn't allowed notifications.
 */
class TalkNotifier(private val context: Context) {
    private val posted = java.util.Collections.synchronizedSet(HashSet<String>())

    fun talkDone(runId: String, text: String) {
        if (!posted.add(runId)) return
        if (foreground()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Answers from Juno", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, runId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = text.replace("**", "").trim()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Juno")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(runId.hashCode(), n) }
    }

    private fun foreground(): Boolean = visible

    companion object {
        const val CHANNEL = "juno-answers"
        /** Set by MainActivity on start/stop: the thread is on screen, so the answer is seen without a notification. */
        @Volatile var visible: Boolean = false
    }
}
