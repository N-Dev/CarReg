package io.github.ndev.roadsight.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.MainActivity
import io.github.ndev.roadsight.R
import io.github.ndev.roadsight.camera.CameraHost
import io.github.ndev.roadsight.camera.CameraUse
import io.github.ndev.roadsight.core.traffic.Kinds
import io.github.ndev.roadsight.debug.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the camera and the AI running while the screen is off or another app is open: for counting
 * traffic, or for watching for plates (and the watchlist). Android requires a notification while it
 * runs; its Stop button ends the counting or watching.
 */
class BackgroundService : LifecycleService() {
    companion object {
        const val TRAFFIC = "traffic"
        const val PLATES = "plates"
        private const val ACTION_STOP = "io.github.ndev.roadsight.STOP"
        private const val NOTE_ID = 7

        /** What's running in the background: [TRAFFIC], [PLATES] or null. */
        val mode = MutableStateFlow<String?>(null)

        /** Starts (or switches) background running. Call from the app while it's on screen. */
        fun start(context: Context, what: String) {
            val i = Intent(context, BackgroundService::class.java).putExtra("mode", what)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                DebugLog.error("background", "Couldn't start running in the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundService::class.java))
        }

        fun useFor(app: App, what: String): CameraUse =
            if (what == TRAFFIC) CameraUse(app.traffic, CameraHost.TRAFFIC_RES, "traffic") else CameraUse(app.plates, CameraHost.PLATES_RES, "plates")
    }

    private var wake: PowerManager.WakeLock? = null
    private var ticker: Job? = null
    private var current: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = App.instance
        if (intent?.action == ACTION_STOP) {
            DebugLog.add("background", "Stopped from the notification")
            when (current) {
                TRAFFIC -> app.traffic.stopSession()
                PLATES -> app.plates.stopWatching()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val what = intent?.getStringExtra("mode") ?: TRAFFIC
        try {
            ServiceCompat.startForeground(
                this, NOTE_ID, notification(what),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
            )
        } catch (e: Exception) {
            DebugLog.error("background", "Android didn't allow running in the background", e)
            stopSelf()
            return START_NOT_STICKY
        }
        current = what
        mode.value = what
        app.camera.keepRunning(useFor(app, what))
        if (wake == null) {
            // The phone mustn't doze off with the screen off: the camera and the AI need the processor.
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoadSight:background")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            var n = 0
            while (isActive) {
                delay(5000)
                runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification(what)) }
                // A running session's end time is saved every 30 seconds, so nothing is lost if the phone dies.
                if (what == TRAFFIC && ++n % 6 == 0) app.traffic.touchSession()
            }
        }
        DebugLog.add("background", "Running in the background: $what")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        App.instance.camera.keepRunning(null)
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        mode.value = null
        current = null
        DebugLog.add("background", "Stopped running in the background")
        super.onDestroy()
    }

    private fun notification(what: String): Notification {
        val app = App.instance
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BackgroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title: String
        val text: String
        if (what == TRAFFIC) {
            val u = app.traffic.ui.value
            var motor = 0
            var others = 0
            for ((k, v) in u.totals) if (Kinds.isMotor(k)) motor += v[0] else others += v[0]
            val secs = if (u.started > 0) (System.currentTimeMillis() - u.started) / 1000 else 0
            title = "Counting traffic"
            text = "%d:%02d:%02d · %d motor vehicle%s, %d other%s".format(
                secs / 3600, secs / 60 % 60, secs % 60, motor, if (motor == 1) "" else "s", others, if (others == 1) "" else "s",
            )
        } else {
            val n = app.plates.confirmedCount
            title = "Watching for plates"
            text = if (n == 0L) "No plates yet" else "$n plate${if (n == 1L) "" else "s"} read"
        }
        return NotificationCompat.Builder(this, App.CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_stat_roadsight)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }
}
