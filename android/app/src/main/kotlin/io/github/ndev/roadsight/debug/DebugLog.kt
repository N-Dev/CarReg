package io.github.ndev.roadsight.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the app did, for debug mode and bug reports: models loaded, accelerators, quality changes,
 * plates confirmed, road users counted, background running, errors. Kept in memory only (the last 1,000
 * lines) and also written to the Android log.
 */
object DebugLog {
    class Entry(val t: Long, val cat: String, val msg: String)

    private const val MAX = 1000
    private val entries = ArrayDeque<Entry>()
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    /** Changes whenever a line is added, so the log screen can update. */
    val version = MutableStateFlow(0)

    fun add(cat: String, msg: String) {
        synchronized(entries) {
            entries.addLast(Entry(System.currentTimeMillis(), cat, msg))
            while (entries.size > MAX) entries.removeFirst()
        }
        version.update { it + 1 }
        Log.i("RoadSight", "[$cat] $msg")
    }

    fun error(cat: String, msg: String, e: Throwable? = null) {
        add(cat, if (e != null) "$msg: ${e.javaClass.simpleName}: ${e.message}" else msg)
        if (e != null) Log.e("RoadSight", msg, e)
    }

    fun entries(): List<Entry> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        version.update { it + 1 }
    }

    fun time(t: Long): String = TIME.format(Instant.ofEpochMilli(t))

    fun text(): String = entries().joinToString("\n") { "${time(it.t)} [${it.cat}] ${it.msg}" }
}
