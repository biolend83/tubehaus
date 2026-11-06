package app.tubehaus.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.tubehaus.ui.CastDevice
import app.tubehaus.ui.tr

/**
 * Runs while a video plays on a TV: the TV fetches the video from this phone (CastServer), so
 * the app must keep running with the screen off, and the Wi-Fi must stay awake. A notification
 * says what it is doing, with a button to stop.
 */
class CastService : Service() {
    companion object {
        private const val CHANNEL = "cast"
        private const val STOP = "app.tubehaus.CAST_STOP"
        private const val ID = 7

        /** casting to [d] now; null: stopped */
        fun set(ctx: Context, d: CastDevice?) {
            val i = Intent(ctx, CastService::class.java)
            if (d == null) { runCatching { ctx.stopService(i) }; return }
            i.putExtra("name", d.name)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }
    }

    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Handler(mainLooper).post { runCatching { tube.state.casting.stop() } }
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra("name") ?: ""
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Cast"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, CastService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr("Playing on {d}").replace("{d}", name))
            .setContentText(runCatching { tube.state.playback.video?.title }.getOrNull() ?: "")
            .setContentIntent(open)
            .addAction(0, tr("Stop casting"), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
        } catch (_: Exception) {
            // not allowed to run in the foreground just now: casting still works while the app is open
        }
        if (wifi == null) wifi = runCatching {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tubehaus:cast").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        if (wake == null) wake = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tubehaus:cast").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }.getOrNull()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wifi?.release() }
        runCatching { wake?.release() }
        wifi = null
        wake = null
        super.onDestroy()
    }
}
