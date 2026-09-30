package io.github.ndev.roadsight.plates

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.MainActivity
import io.github.ndev.roadsight.R
import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.core.plate.PlateResult
import io.github.ndev.roadsight.data.WatchRow
import io.github.ndev.roadsight.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Plates to look out for ("watch": a notification when one is seen) and plates to leave alone
 * ("ignore": your own cars, never saved to history or shown in the tray). Kept in the database; a copy
 * in memory makes checking a plate instant.
 */
class Watchlist(private val app: App) {
    companion object {
        const val WATCH = "watch"
        const val IGNORE = "ignore"

        /** The same plate is only announced again after this long (a car parked in view, say). */
        const val QUIET_MS = 10 * 60_000L

        private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

        /**
         * The key a typed plate is matched by: letters and digits only, with the Irish or UK format's
         * corrections when it fits one (so "24l-D-12345" matches the reader's 241D12345).
         */
        fun keyFor(text: String): String {
            val c = Formats.clean(text)
            for (profile in listOf("IE", "UK")) {
                val v = Formats.validate(c, profile)
                if (v.valid) return v.key
            }
            return c
        }
    }

    /** A watched plate just seen, for the banner on the Plates screen. */
    class Hit(val row: WatchRow, val text: String, val at: Long)

    val rows = MutableStateFlow<List<WatchRow>>(emptyList())
    val lastHit = MutableStateFlow<Hit?>(null)

    @Volatile
    private var byKey: Map<String, WatchRow> = emptyMap()
    private val lastAlert = HashMap<String, Long>()

    /** Reads the lists from the database (on the background thread). */
    fun load() {
        app.io.execute {
            val r = runCatching { app.db.watchList() }.getOrDefault(emptyList())
            byKey = r.associateBy { it.key }
            rows.value = r
        }
    }

    fun modeOf(key: String): String? = byKey[key]?.mode

    fun ignored(key: String): Boolean = byKey[key]?.mode == IGNORE

    fun rowFor(key: String): WatchRow? = byKey[key]

    fun set(text: String, label: String, mode: String) {
        val key = keyFor(text)
        if (key.length < 2) return
        app.io.execute {
            runCatching { app.db.setWatch(key, text.trim().uppercase(), label.trim(), mode) }
            DebugLog.add("watch", "${if (mode == WATCH) "Watching for" else "Ignoring"} $key${if (label.isNotBlank()) " ($label)" else ""}")
        }
        load()
    }

    fun remove(key: String) {
        app.io.execute { runCatching { app.db.removeWatch(key) } }
        load()
    }

    /**
     * A plate was read (live, in the background, in a photo or video). If it's being watched for: a
     * notification (at most once every [QUIET_MS] per plate), a buzz, and the banner. Returns its row if
     * it's on the watchlist.
     */
    fun seen(r: PlateResult, thumb: Bitmap?, source: String): WatchRow? {
        val w = byKey[r.key] ?: return null
        if (w.mode != WATCH) return null
        val now = System.currentTimeMillis()
        app.io.execute { runCatching { app.db.watchSeen(r.key, now) } }
        synchronized(lastAlert) {
            val prev = lastAlert[r.key] ?: 0L
            if (now - prev < QUIET_MS) return w
            lastAlert[r.key] = now
        }
        DebugLog.add("watch", "Watched plate seen: ${r.text}${if (w.label.isNotEmpty()) " (${w.label})" else ""} from the $source")
        lastHit.value = Hit(w, r.text, now)
        notify(w, r, thumb, now)
        buzz()
        load()
        return w
    }

    private fun notify(w: WatchRow, r: PlateResult, thumb: Bitmap?, now: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            app, 2, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (w.label.isNotEmpty()) "${w.label} is here" else "${r.text} is here"
        val text = "${r.text} seen at ${TIME.format(Instant.ofEpochMilli(now))}"
        val b = NotificationCompat.Builder(app, App.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_roadsight)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(open)
        if (thumb != null) {
            b.setLargeIcon(thumb)
            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(text))
        }
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(1000 + (r.key.hashCode() and 0xffff), b.build()) }
    }

    /** Three short buzzes, so a watched plate feels different from any other. */
    private fun buzz() {
        if (!app.prefs.haptics) return
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 80, 90, 80, 160), -1))
        }
    }
}
