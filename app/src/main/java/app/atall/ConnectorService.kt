package app.atall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Atall running while Juno waits on agents over the connector, so the run finishes with the phone in a pocket
 * (C2 exit test: the phone backgrounded mid-run). One quiet, silent notification: "Waiting on Grok and Duet". It
 * stops as soon as nothing is open. Started only from the events loop right after a task was posted.
 */
class ConnectorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val names = intent?.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val n = notification(this, names)
        try {
            ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            // Not allowed right now (started from the background on Android 12+): the loop still runs while it can.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 7301
        private const val CHANNEL = "atall-waiting"
        private const val EXTRA_NAMES = "names"
        @Volatile private var running = false
        @Volatile private var shown: List<String> = emptyList()

        /** Start (or update) the service while [busy], stop it otherwise. Cheap when nothing changes. */
        fun keep(context: Context, busy: Boolean, names: () -> List<String>) {
            if (!busy) {
                if (running) { running = false; shown = emptyList(); runCatching { context.stopService(Intent(context, ConnectorService::class.java)) } }
                return
            }
            val now = names()
            if (running && now == shown) return
            running = true
            shown = now
            val i = Intent(context, ConnectorService::class.java).putStringArrayListExtra(EXTRA_NAMES, ArrayList(now))
            runCatching { ContextCompat.startForegroundService(context, i) }.onFailure { running = false }
        }

        fun line(names: List<String>): String = when (names.size) {
            0 -> "Waiting on your agents"
            1 -> "Waiting on ${names[0]}"
            2 -> "Waiting on ${names[0]} and ${names[1]}"
            else -> "Waiting on ${names[0]}, ${names[1]} and ${names.size - 2} more"
        }

        private fun notification(context: Context, names: List<String>): android.app.Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
                nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Waiting on agents", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
            }
            val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Juno")
                .setContentText(line(names))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }

        @Suppress("unused")
        private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
