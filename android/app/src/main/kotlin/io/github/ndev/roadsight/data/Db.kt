package io.github.ndev.roadsight.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.ndev.roadsight.core.Json
import io.github.ndev.roadsight.core.plate.PlateInfo
import io.github.ndev.roadsight.core.plate.PlateResult
import io.github.ndev.roadsight.core.traffic.Event
import io.github.ndev.roadsight.core.traffic.Lines

/** A plate in history: one row per plate, however often it was seen. */
class PlateRow(
    val key: String,
    val text: String,
    val region: String?,
    val profile: String,
    val valid: Boolean,
    val conf: Double,
    val count: Int,
    val first: Long,
    val last: Long,
    val source: String,
    val thumb: ByteArray?,
    val info: PlateInfo?,
)

/** A traffic counting session. */
class SessionRow(
    val id: Long,
    val site: String,
    val started: Long,
    val ended: Long,
    val distanceM: Double,
    val limit: Int,
    val dir1: String,
    val dir2: String,
    val lines: Lines,
    val model: String,
    val vehicles: Int,
    /** "live" (the camera) or "video" (a recording). */
    val source: String = "live",
)

/** A plate on the watchlist (mode "watch": tell me when it's seen) or ignored ("ignore": one of my cars). */
class WatchRow(
    val key: String,
    val text: String,
    val label: String,
    val mode: String,
    val added: Long,
    val lastSeen: Long,
    val seen: Int,
)

/** Everything the app keeps, on the phone only: plate history, traffic counts and the watchlist. No images of traffic. */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, 3) {
    companion object {
        const val NAME = "roadsight.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE plates (key TEXT PRIMARY KEY, text TEXT, region TEXT, profile TEXT, valid INTEGER, conf REAL, " +
                "count INTEGER, first INTEGER, last INTEGER, source TEXT, thumb BLOB, info TEXT)",
        )
        db.execSQL(
            "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, site TEXT, started INTEGER, ended INTEGER, " +
                "distance REAL, speed_limit INTEGER, dir1 TEXT, dir2 TEXT, lines TEXT, model TEXT, source TEXT DEFAULT 'live')",
        )
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, session INTEGER, t INTEGER, kind TEXT, dir INTEGER, speed REAL, length REAL)")
        db.execSQL("CREATE INDEX events_session ON events(session)")
        createWatch(db)
    }

    private fun createWatch(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS watch (key TEXT PRIMARY KEY, text TEXT, label TEXT, mode TEXT, added INTEGER, last_seen INTEGER, seen INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createWatch(db)
        // Version 3: counting sessions say whether they came from the camera or a video.
        if (oldVersion < 3) db.execSQL("ALTER TABLE sessions ADD COLUMN source TEXT DEFAULT 'live'")
    }

    // ---------------------------------------------------------------- watchlist
    fun watchList(): List<WatchRow> =
        readableDatabase.rawQuery("SELECT key, text, label, mode, added, last_seen, seen FROM watch ORDER BY mode DESC, text", null).use { c ->
            val out = ArrayList<WatchRow>()
            while (c.moveToNext()) {
                out.add(WatchRow(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2) ?: "", c.getString(3) ?: "watch", c.getLong(4), c.getLong(5), c.getInt(6)))
            }
            out
        }

    /** Adds a plate to the watchlist or the ignore list, or changes its label or list. */
    @Synchronized
    fun setWatch(key: String, text: String, label: String, mode: String) {
        val v = ContentValues()
        v.put("text", text)
        v.put("label", label)
        v.put("mode", mode)
        val db = writableDatabase
        if (db.update("watch", v, "key = ?", arrayOf(key)) == 0) {
            v.put("key", key)
            v.put("added", System.currentTimeMillis())
            v.put("last_seen", 0L)
            v.put("seen", 0)
            db.insert("watch", null, v)
        }
    }

    fun removeWatch(key: String) {
        writableDatabase.delete("watch", "key = ?", arrayOf(key))
    }

    @Synchronized
    fun watchSeen(key: String, time: Long) {
        writableDatabase.execSQL("UPDATE watch SET last_seen = ?, seen = seen + 1 WHERE key = ?", arrayOf<Any>(time, key))
    }

    // ---------------------------------------------------------------- plates
    private fun infoJson(i: PlateInfo?): String? = i?.let {
        Json.write(mapOf("year" to it.year, "period" to it.period, "county" to it.county, "countyGa" to it.countyGa, "area" to it.area))
    }

    private fun infoOf(s: String?): PlateInfo? = s?.let {
        runCatching {
            val m = Json.obj(it)
            PlateInfo((m["year"] as? Double)?.toInt(), m["period"] as? String, m["county"] as? String, m["countyGa"] as? String, m["area"] as? String)
        }.getOrNull()
    }

    /** Adds a sighting, merged into the plate's row: counts it, keeps the best confidence and photo. */
    @Synchronized
    fun savePlate(r: PlateResult, source: String, thumb: ByteArray?, time: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val old = plate(r.key)
        val v = ContentValues()
        v.put("key", r.key)
        v.put("text", r.text)
        v.put("region", r.region ?: old?.region)
        v.put("profile", r.profile)
        v.put("valid", if (r.valid || old?.valid == true) 1 else 0)
        v.put("info", infoJson(r.info ?: old?.info))
        v.put("source", source)
        if (old == null) {
            v.put("conf", r.score)
            v.put("count", 1)
            v.put("first", time)
            v.put("last", time)
            v.put("thumb", thumb)
            db.insert("plates", null, v)
        } else {
            v.put("conf", maxOf(old.conf, r.score))
            v.put("count", old.count + 1)
            v.put("last", time)
            v.put("thumb", if (thumb != null && r.score >= old.conf - 0.05) thumb else old.thumb)
            db.update("plates", v, "key = ?", arrayOf(r.key))
        }
    }

    private fun plateOf(c: Cursor) = PlateRow(
        key = c.getString(0), text = c.getString(1), region = c.getString(2), profile = c.getString(3) ?: "ANY",
        valid = c.getInt(4) == 1, conf = c.getDouble(5), count = c.getInt(6), first = c.getLong(7), last = c.getLong(8),
        source = c.getString(9) ?: "live", thumb = c.getBlob(10), info = infoOf(c.getString(11)),
    )

    private val plateCols = "key, text, region, profile, valid, conf, count, first, last, source, thumb, info"

    // The list leaves the photos out (loaded one by one as they scroll into view), and says whether there is one.
    private val listCols = "key, text, region, profile, valid, conf, count, first, last, source, CASE WHEN thumb IS NULL THEN NULL ELSE X'00' END, info"

    fun plate(key: String): PlateRow? =
        readableDatabase.rawQuery("SELECT $plateCols FROM plates WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) plateOf(c) else null }

    /** Every plate, most recent first. Without photos, `thumb` is a 1-byte marker when the plate has one. */
    fun plates(withThumbs: Boolean = false): List<PlateRow> =
        readableDatabase.rawQuery("SELECT ${if (withThumbs) plateCols else listCols} FROM plates ORDER BY last DESC", null).use { c ->
            val out = ArrayList<PlateRow>()
            while (c.moveToNext()) out.add(plateOf(c))
            out
        }

    fun thumb(key: String): ByteArray? =
        readableDatabase.rawQuery("SELECT thumb FROM plates WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    fun deletePlate(key: String) {
        writableDatabase.delete("plates", "key = ?", arrayOf(key))
    }

    fun clearPlates() {
        writableDatabase.delete("plates", null, null)
    }

    /** Deletes plates last seen longer ago than `days` (null = keep forever). Returns how many. */
    fun prunePlates(days: Int?): Int {
        if (days == null) return 0
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        return writableDatabase.delete("plates", "last < ?", arrayOf(cutoff.toString()))
    }

    /** Keeps the text, removes every saved photo. */
    fun stripPhotos() {
        writableDatabase.execSQL("UPDATE plates SET thumb = NULL")
    }

    // ---------------------------------------------------------------- traffic
    fun newSession(site: String, started: Long, distanceM: Double, limit: Int, dir1: String, dir2: String, lines: Lines, model: String, source: String = "live"): Long {
        val v = ContentValues()
        v.put("source", source)
        v.put("site", site)
        v.put("started", started)
        v.put("ended", started)
        v.put("distance", distanceM)
        v.put("speed_limit", limit)
        v.put("dir1", dir1)
        v.put("dir2", dir2)
        v.put("lines", Json.write(mapOf("a" to lines.a.toList(), "b" to lines.b.toList())))
        v.put("model", model)
        return writableDatabase.insert("sessions", null, v)
    }

    fun endSession(id: Long, ended: Long) {
        val v = ContentValues()
        v.put("ended", ended)
        writableDatabase.update("sessions", v, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun addEvents(session: Long, events: List<Event>) {
        if (events.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (e in events) {
                val v = ContentValues()
                v.put("session", session)
                v.put("t", e.t)
                v.put("kind", e.kind)
                v.put("dir", e.dir)
                if (e.speed != null) v.put("speed", e.speed) else v.putNull("speed")
                if (e.length != null) v.put("length", e.length) else v.putNull("length")
                db.insert("events", null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun linesOf(s: String?): Lines = runCatching {
        val m = Json.obj(s ?: "")
        @Suppress("UNCHECKED_CAST")
        fun arr(k: String) = (m[k] as List<Double>).toDoubleArray()
        Lines(arr("a"), arr("b"))
    }.getOrElse { Lines.default() }

    private val motorKinds = "('car', 'truck', 'bus', 'motorbike')"

    fun sessions(): List<SessionRow> =
        readableDatabase.rawQuery(
            "SELECT s.id, s.site, s.started, s.ended, s.distance, s.speed_limit, s.dir1, s.dir2, s.lines, s.model, " +
                "(SELECT COUNT(*) FROM events e WHERE e.session = s.id AND e.kind IN $motorKinds), s.source FROM sessions s ORDER BY s.started DESC",
            null,
        ).use { c ->
            val out = ArrayList<SessionRow>()
            while (c.moveToNext()) {
                out.add(
                    SessionRow(
                        c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getDouble(4), c.getInt(5),
                        c.getString(6) ?: "Direction 1", c.getString(7) ?: "Direction 2", linesOf(c.getString(8)), c.getString(9) ?: "", c.getInt(10),
                        c.getString(11) ?: "live",
                    ),
                )
            }
            out
        }

    fun session(id: Long): SessionRow? = sessions().firstOrNull { it.id == id }

    fun events(session: Long): List<Event> =
        readableDatabase.rawQuery("SELECT t, kind, dir, speed, length FROM events WHERE session = ? ORDER BY t", arrayOf(session.toString())).use { c ->
            val out = ArrayList<Event>()
            while (c.moveToNext()) {
                out.add(
                    Event(
                        c.getLong(0), c.getString(1), c.getInt(2),
                        if (c.isNull(3)) null else c.getDouble(3),
                        if (c.isNull(4)) null else c.getDouble(4),
                    ),
                )
            }
            out
        }

    fun deleteSession(id: Long) {
        val db = writableDatabase
        db.delete("events", "session = ?", arrayOf(id.toString()))
        db.delete("sessions", "id = ?", arrayOf(id.toString()))
    }

    /** Makes sure everything written is in the main database file (for a backup). */
    @Synchronized
    fun checkpoint() {
        runCatching { writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } }
    }

    fun clearSessions() {
        writableDatabase.delete("events", null, null)
        writableDatabase.delete("sessions", null, null)
    }
}
