package io.github.ndev.roadsight

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.mutableIntStateOf
import io.github.ndev.roadsight.ai.Engine
import io.github.ndev.roadsight.core.Json
import io.github.ndev.roadsight.core.traffic.Lines
import io.github.ndev.roadsight.data.Db
import io.github.ndev.roadsight.plates.PlateScanner
import io.github.ndev.roadsight.traffic.TrafficCounter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** App-wide singletons: settings, storage, the AI engine and the two live scanners. */
class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var db: Db
        private set
    lateinit var engine: Engine
        private set
    val plates: PlateScanner by lazy { PlateScanner(this) }
    val traffic: TrafficCounter by lazy { TrafficCounter(this) }

    /** A photo shared to the app ("Share → RoadSight"), waiting to be read. */
    val sharedPhoto = MutableStateFlow<Uri?>(null)

    /** A counting session to show in History (after "See results"). */
    val openSession = MutableStateFlow<Long?>(null)

    /** Bumped when plates or counts are saved or deleted, so lists reload. */
    val dataVersion = MutableStateFlow(0)

    fun dataChanged() {
        dataVersion.update { it + 1 }
    }

    /** Background work that mustn't hold up the camera: saving to the database, tidying history. */
    val io = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        db = Db(this)
        engine = Engine(this)
        io.execute {
            // History deletes itself after the chosen period.
            runCatching { db.prunePlates(prefs.retentionDays()) }
            // Start loading the live models so the camera is ready sooner.
            runCatching { engine.warmUp() }
        }
    }

    fun haptic(ms: Long = 18) {
        if (!prefs.haptics) return
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

/**
 * Settings, kept in SharedPreferences. Reading one from a screen subscribes it to changes (through a
 * snapshot state), so screens redraw when a setting changes; `version` does the same for other code.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("roadsight", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val changes = mutableIntStateOf(0)

    private fun bump() {
        _version.update { it + 1 }
        changes.intValue += 1
    }

    /** Marks the caller as depending on the settings (for Compose). */
    private fun track() {
        changes.intValue
    }

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String {
            track()
            return sp.getString(key, def) ?: def
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
            bump()
        }
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
            track()
            return sp.getBoolean(key, def)
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
            bump()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            track()
            return sp.getInt(key, def)
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
            bump()
        }
    }

    private fun dbl(key: String, def: Double) = object : ReadWriteProperty<Any?, Double> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Double {
            track()
            return sp.getString(key, null)?.toDoubleOrNull() ?: def
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Double) {
            sp.edit().putString(key, value.toString()).apply()
            bump()
        }
    }

    // ---------------------------------------------------------------- plates
    /** auto | IE | UK | ANY */
    var format by str("format", "auto")

    /** low | medium | high */
    var sensitivity by str("sensitivity", "medium")

    /** auto | fast | balanced | sharp */
    var quality by str("quality", "auto")
    var haptics by bool("haptics", true)
    var history by bool("history", true)
    var keepPhotos by bool("keepPhotos", true)

    /** Days to keep plate history: 7 | 30 | 365 | forever */
    var retention by str("retention", "30")

    fun retentionDays(): Int? = retention.toIntOrNull()

    // ---------------------------------------------------------------- traffic
    /** auto | tiny | nano */
    var trafficModel by str("t_model", "auto")
    var trafficSensitivity by str("t_sensitivity", "medium")

    /** fast | cool */
    var trafficPace by str("t_pace", "fast")

    /** Seconds without a touch before the screen dims while counting (0 = never). */
    var dimAfter by int("t_dim", 60)
    var distanceM by dbl("t_distance", 20.0)
    var speedLimit by int("t_limit", 50)
    var dir1 by str("t_dir1", "Left to right")
    var dir2 by str("t_dir2", "Right to left")
    var site by str("t_site", "")
    var setupDone by bool("t_setup", false)
    private var linesJson by str("t_lines", "")

    var lines: Lines
        get() = runCatching {
            @Suppress("UNCHECKED_CAST")
            val m = Json.obj(linesJson)
            fun arr(k: String) = (m[k] as List<Double>).toDoubleArray()
            Lines(arr("a"), arr("b"))
        }.getOrElse { Lines.default() }
        set(v) {
            linesJson = Json.write(mapOf("a" to v.a.toList(), "b" to v.b.toList()))
        }

    // ---------------------------------------------------------------- AI engine
    /** auto | CPU | XNNPACK | NNAPI */
    var accel by str("accel", "auto")

    /** CPU threads; 0 = automatic */
    var threads by int("threads", 0)

    /** Per-model settings found fastest by the speed test, e.g. {"det384": "XNNPACK:4"}. */
    var tuned by str("tuned", "")
}

val SENS = mapOf("low" to 0.5, "medium" to 0.35, "high" to 0.25)
val TRAFFIC_SENS = mapOf("low" to 0.45, "medium" to 0.3, "high" to 0.2)
