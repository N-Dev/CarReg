package io.github.ndev.roadsight.data

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.BuildConfig
import io.github.ndev.roadsight.core.Json
import io.github.ndev.roadsight.debug.DebugLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything RoadSight keeps, in one file: plate history (with photos), counting sessions, the watchlist
 * and the settings. For moving to a new phone, or keeping a copy. A zip holding the database, the
 * settings and a short description.
 */
object Backup {
    private const val DB = "roadsight.db"
    private const val SETTINGS = "settings.json"
    private const val INFO = "info.json"

    /** What's in a backup (or on the phone), for the confirmations. */
    class Summary(val plates: Int, val sessions: Int, val watch: Int, val created: Long, val version: String) {
        fun text(): String = listOf(
            "$plates plate${if (plates == 1) "" else "s"}",
            "$sessions counting session${if (sessions == 1) "" else "s"}",
            "$watch on the watchlist and ignore list",
        ).joinToString(", ")
    }

    fun fileName(): String = "RoadSight backup ${LocalDate.now()}.zip"

    private fun count(db: SQLiteDatabase, table: String): Int =
        runCatching { db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } }.getOrDefault(0)

    /** Writes a backup to `out`. Run off the main thread. */
    fun write(app: App, out: OutputStream): Summary {
        val file = app.getDatabasePath(DB)
        val snapshot = File(app.cacheDir, "backup.db")
        // Nothing is written to the database while it's copied.
        val summary = synchronized(app.db) {
            app.db.checkpoint()
            file.copyTo(snapshot, overwrite = true)
            val d = app.db.readableDatabase
            Summary(count(d, "plates"), count(d, "sessions"), count(d, "watch"), System.currentTimeMillis(), BuildConfig.VERSION_NAME)
        }
        try {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(INFO))
                zip.write(
                    Json.write(
                        mapOf(
                            "app" to "RoadSight", "version" to summary.version, "build" to BuildConfig.VERSION_CODE, "created" to summary.created,
                            "plates" to summary.plates, "sessions" to summary.sessions, "watch" to summary.watch,
                        ),
                    ).toByteArray(),
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(SETTINGS))
                zip.write(Json.write(app.prefs.export()).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DB))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        } finally {
            snapshot.delete()
        }
        DebugLog.add("backup", "Backed up: ${summary.text()}")
        return summary
    }

    /** A backup read and checked, ready to restore. */
    class Checked(val db: File, val settings: Map<String, Any?>, val summary: Summary)

    /** Reads a backup into a temporary file and checks it's one of ours. Throws with a readable message if not. */
    fun check(app: App, input: InputStream): Checked {
        val tmp = File(app.cacheDir, "restore.db")
        tmp.delete()
        var settings: Map<String, Any?> = emptyMap()
        var info: Map<String, Any?>? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when (e.name) {
                    DB -> tmp.outputStream().use { zip.copyTo(it) }
                    SETTINGS -> settings = runCatching { Json.obj(zip.readBytes().decodeToString()) }.getOrDefault(emptyMap())
                    INFO -> info = runCatching { Json.obj(zip.readBytes().decodeToString()) }.getOrNull()
                }
            }
        }
        require(info?.get("app") == "RoadSight" && tmp.exists()) { "That isn’t a RoadSight backup" }
        val summary = try {
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                require(d.version <= app.db.readableDatabase.version) { "That backup is from a newer RoadSight. Update the app first." }
                for (t in listOf("plates", "sessions", "events")) {
                    d.rawQuery("SELECT COUNT(*) FROM $t", null).use { }
                }
                Summary(count(d, "plates"), count(d, "sessions"), count(d, "watch"), (info!!["created"] as? Double)?.toLong() ?: 0L, info!!["version"] as? String ?: "?")
            }
        } catch (e: IllegalArgumentException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalArgumentException("The backup’s database can’t be read (${e.message})")
        }
        return Checked(tmp, settings, summary)
    }

    /**
     * Replaces everything on the phone with the backup: plate history, counts, watchlist and settings.
     * Counting and background watching must be stopped first. Run off the main thread.
     */
    fun restore(app: App, b: Checked) {
        val file = app.getDatabasePath(DB)
        synchronized(app.db) {
            app.db.close()
            for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
            b.db.copyTo(file, overwrite = true)
            b.db.delete()
            // The next use opens the restored file (and brings an older one up to date).
            app.db.readableDatabase
        }
        app.prefs.import(b.settings)
        app.watch.load()
        app.plates.clearTray()
        app.traffic.reconfigure()
        app.dataChanged()
        DebugLog.add("backup", "Restored a backup from ${b.summary.version}: ${b.summary.text()}")
    }

    fun openOut(app: App, uri: Uri): OutputStream = app.contentResolver.openOutputStream(uri) ?: error("couldn’t open the file")

    fun openIn(app: App, uri: Uri): InputStream = app.contentResolver.openInputStream(uri) ?: error("couldn’t open the file")
}
