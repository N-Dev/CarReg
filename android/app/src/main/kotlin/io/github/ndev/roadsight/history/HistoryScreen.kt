package io.github.ndev.roadsight.history

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.BuildConfig
import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.core.traffic.Event
import io.github.ndev.roadsight.core.traffic.Kinds
import io.github.ndev.roadsight.core.traffic.LONG_VEHICLE_M
import io.github.ndev.roadsight.core.traffic.Report
import io.github.ndev.roadsight.core.traffic.ReportSession
import io.github.ndev.roadsight.core.traffic.SpeedStats
import io.github.ndev.roadsight.core.traffic.Stats
import io.github.ndev.roadsight.core.traffic.Summary
import io.github.ndev.roadsight.data.PlateRow
import io.github.ndev.roadsight.data.SessionRow
import io.github.ndev.roadsight.data.Share
import io.github.ndev.roadsight.plates.PlateSheet
import io.github.ndev.roadsight.plates.PlateView
import io.github.ndev.roadsight.plates.registered
import io.github.ndev.roadsight.traffic.fmtDistance
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.Card
import io.github.ndev.roadsight.ui.HourChart
import io.github.ndev.roadsight.ui.Ic
import io.github.ndev.roadsight.ui.Legend
import io.github.ndev.roadsight.ui.PlateGraphic
import io.github.ndev.roadsight.ui.Segmented
import io.github.ndev.roadsight.ui.SpeedChart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TNUM = TextStyle(fontFeatureSettings = "tnum")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val DAY_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private fun local(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())

/** "Today", "Yesterday", "Mon 28 Sep", or with the year when it isn't this year. */
fun dayLabel(t: Long): String {
    val d = local(t).toLocalDate()
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> DAY.format(d)
        else -> DAY_YEAR.format(d)
    }
}

@Composable
fun HistoryScreen() {
    val app = App.instance
    var section by rememberSaveable { mutableStateOf("plates") }
    var session by rememberSaveable { mutableStateOf<Long?>(null) }
    val open by app.openSession.collectAsState()
    LaunchedEffect(open) {
        open?.let {
            section = "counts"
            session = it
            app.openSession.value = null
        }
    }
    BackHandler(enabled = session != null) { session = null }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        val id = session
        if (section == "counts" && id != null) {
            SessionDetail(id, onBack = { session = null })
        } else {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                Text("History", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Segmented(listOf("plates" to "Plates", "counts" to "Traffic counts"), section) { section = it }
            }
            if (section == "plates") PlatesHistory() else CountsHistory(onOpen = { session = it })
        }
    }
}

// ---------------------------------------------------------------- plates

@Composable
private fun PlatesHistory() {
    val app = App.instance
    val context = LocalContext.current
    val version by app.dataVersion.collectAsState()
    val prefsVersion by app.prefs.version.collectAsState()
    val all by produceState<List<PlateRow>?>(null, version) { value = withContext(Dispatchers.IO) { runCatching { app.db.plates() }.getOrDefault(emptyList()) } }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<PlateRow?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<ByteArray?>(null) }

    val list = all
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.mint) }
        return
    }
    val q = Formats.clean(query)
    val shown = if (q.isEmpty()) list else list.filter { it.key.contains(q) || Formats.clean(it.text).contains(q) }
    val groups = shown.groupBy { dayLabel(it.last) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        item {
            OutlinedTextField(
                query, { query = it.take(20) }, singleLine = true, placeholder = { Text("Search plates") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.mint, unfocusedBorderColor = C.line2, cursorColor = C.mint, focusedTextColor = C.text, unfocusedTextColor = C.text),
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (list.isEmpty()) "" else "${list.size} plate${if (list.size == 1) "" else "s"}",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    if (list.isEmpty()) Toast.makeText(context, "Nothing to export yet", Toast.LENGTH_SHORT).show() else exporting = platesCsv(list).toByteArray()
                }) { Text("Export CSV") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = list.isNotEmpty(), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red)) { Text("Delete all") }
            }
        }
        if (list.isEmpty()) {
            item { Empty("No plates yet", "Plates you scan are kept here, on this phone only.") }
        } else if (shown.isEmpty()) {
            item { Empty("No matches", "Nothing matches “$query”.") }
        }
        for ((day, rows) in groups) {
            item(key = "day-$day") {
                Text(day, color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 2.dp))
            }
            items(rows, key = { it.key }) { row -> PlateItem(row, version) { selected = row } }
        }
        item {
            val note = remember(prefsVersion) { historyNote() }
            Text(note, color = C.muted, fontSize = 12.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
        }
    }

    selected?.let { row ->
        val thumb by produceState<ImageBitmap?>(null, row.key) {
            value = withContext(Dispatchers.IO) { app.db.thumb(row.key)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        }
        PlateSheet(
            PlateView(
                text = row.text, key = row.key, region = row.region, profile = row.profile, format = null, valid = row.valid,
                info = row.info, score = row.conf, thumb = thumb, count = row.count, first = row.first, last = row.last, source = row.source,
            ),
            onDismiss = { selected = null },
            onDelete = {
                app.io.execute {
                    runCatching { app.db.deletePlate(row.key) }
                    app.dataChanged()
                }
                app.plates.removeFromTray(row.key)
                selected = null
            },
        )
    }

    if (confirmClear) {
        Confirm("Delete all saved plates?", "This can’t be undone.", "Delete all", onDismiss = { confirmClear = false }) {
            app.io.execute {
                runCatching { app.db.clearPlates() }
                app.dataChanged()
            }
            app.plates.clearTray()
            confirmClear = false
        }
    }

    exporting?.let { bytes ->
        ExportDialog(
            title = "Plate history", text = "A CSV file (for a spreadsheet) with every saved plate.",
            name = "roadsight-plates-${LocalDate.now()}.csv", mime = "text/csv", bytes = bytes, onDismiss = { exporting = null },
        )
    }
}

@Composable
private fun PlateItem(row: PlateRow, version: Int, onClick: () -> Unit) {
    val app = App.instance
    val thumb by produceState<ImageBitmap?>(null, row.key, version) {
        if (row.thumb != null) {
            value = withContext(Dispatchers.IO) { runCatching { app.db.thumb(row.key)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull() }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(C.surface2), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) {
                Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Ic.plate, null, tint = C.muted, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            PlateGraphic(row.text, Formats.bandFor(row.region), yellow = row.profile == "UK")
            val meta = listOfNotNull(
                "${Formats.flagFor(row.region)} ${row.region ?: "Unknown"}".trim(),
                row.source,
                if (row.count > 1) "${row.count}×" else null,
                TIME.format(local(row.last)),
            )
            Text(meta.joinToString(" · "), color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.muted)
    }
}

/** How long plates are kept, for the foot of the list. */
private fun historyNote(): String {
    val p = App.instance.prefs
    if (!p.history) return "Saving to history is off, so new plates aren’t kept. Turn it on in Settings."
    val kept = when (p.retention) {
        "7" -> "for 7 days, then deleted automatically"
        "30" -> "for 30 days, then deleted automatically"
        "365" -> "for a year, then deleted automatically"
        else -> "until you delete them"
    }
    return "Plates are kept on this phone only, $kept${if (p.keepPhotos) "" else " · photos aren’t kept"}."
}

/** Every saved plate as CSV (the web app's columns). */
private fun platesCsv(rows: List<PlateRow>): String {
    fun q(v: Any?) = "\"" + (v?.toString() ?: "").replace("\"", "\"\"") + "\""
    val sb = StringBuilder()
    sb.append(listOf("plate", "country", "valid_format", "confidence", "times_seen", "first_seen", "last_seen", "source", "registered", "county_or_area").joinToString(",") { q(it) })
    for (e in rows) {
        sb.append('\n')
        sb.append(
            listOf(
                e.text, e.region ?: "", e.valid, "%.2f".format(Locale.ROOT, e.conf), e.count,
                ISO_MS.format(Instant.ofEpochMilli(e.first)), ISO_MS.format(Instant.ofEpochMilli(e.last)), e.source,
                registered(e.profile, e.info), e.info?.county ?: e.info?.area ?: "",
            ).joinToString(",") { q(it) },
        )
    }
    return sb.toString()
}

// ---------------------------------------------------------------- traffic counts

@Composable
private fun CountsHistory(onOpen: (Long) -> Unit) {
    val app = App.instance
    val version by app.dataVersion.collectAsState()
    val live by app.traffic.ui.collectAsState()
    val sessions by produceState<List<SessionRow>?>(null, version, live.running) {
        value = withContext(Dispatchers.IO) { runCatching { app.db.sessions() }.getOrDefault(emptyList()) }
    }
    var confirmClear by remember { mutableStateOf(false) }
    val list = sessions
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.mint) }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        if (list.isEmpty()) {
            item {
                Empty(
                    "No counts yet",
                    "Set up the lines on the Traffic tab, then tap Start counting. Each counting session is saved here, with its charts, a CSV and a report for the council.",
                )
            }
        }
        items(list, key = { it.id }) { s ->
            val counting = live.running && live.sessionId == s.id
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp))
                    .clickable { onOpen(s.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.site.ifEmpty { "Untitled site" }, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val mins = maxOf(1L, Math.round((s.ended - s.started) / 60000.0))
                    val dur = if (mins >= 60) "${mins / 60} h ${(mins % 60).toString().padStart(2, '0')}" else "$mins min"
                    Text("${Report.whenText(s.started)} · $dur${if (counting) " · counting now" else ""}", color = if (counting) C.mint else C.muted, fontSize = 12.5.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Report.fmt(s.vehicles), color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, style = TNUM)
                    Text("vehicles", color = C.muted, fontSize = 11.sp)
                }
            }
        }
        if (list.isNotEmpty()) {
            item {
                TextButton(onClick = { confirmClear = true }, enabled = !live.running, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Delete all counts", color = C.red)
                }
            }
        }
    }
    if (confirmClear) {
        Confirm("Delete every counting session?", "Their counts, charts and reports go too. This can’t be undone.", "Delete all", onDismiss = { confirmClear = false }) {
            app.io.execute {
                runCatching { app.db.clearSessions() }
                app.dataChanged()
            }
            confirmClear = false
        }
    }
}

private class Loaded(val session: SessionRow, val events: List<Event>, val summary: Summary)

@Composable
private fun SessionDetail(id: Long, onBack: () -> Unit) {
    val app = App.instance
    val version by app.dataVersion.collectAsState()
    val live by app.traffic.ui.collectAsState()
    val counting = live.running && live.sessionId == id
    val data by produceState<Loaded?>(null, id, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val s = app.db.session(id) ?: return@runCatching null
                val events = app.db.events(id)
                // A session still counting runs to now.
                val to = if (app.traffic.ui.value.running && app.traffic.ui.value.sessionId == id) System.currentTimeMillis() else s.ended
                Loaded(s, events, Stats.summarize(events, s.started, to, s.limit))
            }.getOrNull()
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("All sessions", color = C.text, fontSize = 14.sp)
        }
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.mint) }
        } else {
            SessionBody(d, counting, onExport = { export = it }, onDelete = { confirmDelete = true })
        }
    }

    if (confirmDelete) {
        Confirm("Delete this session and its counts?", "This can’t be undone.", "Delete", onDismiss = { confirmDelete = false }) {
            app.io.execute {
                runCatching { app.db.deleteSession(id) }
                app.dataChanged()
            }
            confirmDelete = false
            onBack()
        }
    }
    export?.let { (name, bytes) ->
        val pdf = name.endsWith(".pdf")
        ExportDialog(
            title = if (pdf) "Report for the council" else "Traffic counts (CSV)",
            text = if (pdf) "A one-page PDF: counts by type and direction, vehicles per hour, speeds, and how they were measured."
            else "One row per road user counted: when, what, which way, speed and length.",
            name = name, mime = if (pdf) "application/pdf" else "text/csv", bytes = bytes, onDismiss = { export = null },
        )
    }
}

/** The figures, charts and tables of one counting session, and its exports. */
@Composable
private fun SessionBody(d: Loaded, counting: Boolean, onExport: (Pair<String, ByteArray>) -> Unit, onDelete: () -> Unit) {
    val s = d.session
    var showHours by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val sum = d.summary
    val names = listOf(s.dir1, s.dir2)
    val sp = sum.speedsAll
    Text(s.site.ifEmpty { "Untitled site" }, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    Text("${Report.period(sum.from, sum.to)} · lines ${fmtDistance(s.distanceM)} m apart", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))

    val tiles = listOf(
        Triple("Motor vehicles", Report.fmt(sum.motor), Report.rate(sum)),
        Triple("Busiest hour", sum.peak?.let { Report.fmt(it.motor) } ?: "–", sum.peak?.let { Report.hourRange(it.start) } ?: ""),
        Triple("85% at or under", if (sp.n > 0) "${Math.round(sp.p85)} km/h" else "–", if (sp.n > 0) "${Report.fmt(sp.n)} speeds" else "no speeds yet"),
        Triple("Over ${s.limit} km/h", if (sp.n > 0) "${Stats.fmt1(sp.overPct)}%" else "–", if (sp.n > 0) "${Report.fmt(sp.over)} vehicles" else ""),
    )
    for (pair in tiles.chunked(2)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value, sub) in pair) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text(label, color = C.muted, fontSize = 12.sp)
                    Text(value, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, style = TNUM)
                    Text(sub, color = C.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    Card {
        Text("Motor vehicles per hour", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        Legend(listOf(C.dir1 to s.dir1, C.dir2 to s.dir2))
        HourChart(sum.perHour, names)
        TextButton(onClick = { showHours = !showHours }) { Text(if (showHours) "Hide the table" else "Show as a table", color = C.mint) }
        if (showHours) {
            NumRow(listOf("Hour", s.dir1, s.dir2, "All road users"), header = true)
            for (h in sum.perHour) NumRow(listOf("${Report.hh(h.start)}:00", "${h.dirs[1]}", "${h.dirs[2]}", "${h.all}"))
        }
    }
    Spacer(Modifier.height(10.dp))
    Card {
        Text("Speeds of motor vehicles", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        Legend(listOf(C.dir1 to "Within ${s.limit} km/h", C.over to "Over the limit"))
        SpeedChart(sum)
        Spacer(Modifier.height(8.dp))
        NumRow(listOf("", "Both ways", s.dir1, s.dir2), header = true)
        val all = listOf(sum.speedsAll, sum.speeds1, sum.speeds2)
        val rows: List<Pair<String, (SpeedStats) -> String>> = listOf(
            "Measured" to { x: SpeedStats -> Report.fmt(x.n) },
            "Average" to { x: SpeedStats -> if (x.n > 0) Stats.fmt1(x.mean) else "–" },
            "85th percentile" to { x: SpeedStats -> if (x.n > 0) Stats.fmt1(x.p85) else "–" },
            "Fastest" to { x: SpeedStats -> if (x.n > 0) Stats.fmt1(x.max) else "–" },
            "Over ${s.limit}" to { x: SpeedStats -> if (x.n > 0) "${x.over} (${Stats.fmt1(x.overPct)}%)" else "–" },
        )
        for ((label, f) in rows) NumRow(listOf(label) + all.map(f))
    }
    Spacer(Modifier.height(10.dp))
    Card {
        Text("Everything counted", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        NumRow(listOf("", s.dir1, s.dir2, "Total"), header = true)
        val kinds = Kinds.ORDER.filter { (sum.totals[it]?.get(0) ?: 0) > 0 }.ifEmpty { listOf("car") }
        for (k in kinds) {
            val t = sum.totals[k] ?: IntArray(3)
            NumRow(listOf(Kinds[k]!!.label, "${t[1]}", "${t[2]}", "${t[0]}"))
        }
        NumRow(listOf("Long vehicles (${Stats.fmt1(LONG_VEHICLE_M)} m+)", "", "", "${sum.long}"), muted = true)
    }
    Spacer(Modifier.height(14.dp))
    val base = Report.baseName(s.site, s.started)
    Button(
        onClick = {
            scope.launch {
                val bytes = withContext(Dispatchers.Default) {
                    Report.council(
                        ReportSession(s.site, s.distanceM, s.limit, names), sum,
                        appName = "RoadSight", release = BuildConfig.VERSION_NAME, build = BuildConfig.VERSION_CODE.toString(),
                    )
                }
                onExport("$base.pdf" to bytes)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = C.mint, contentColor = C.bg),
    ) { Text("Report for the council (PDF)", fontWeight = FontWeight.Bold) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onExport("$base.csv" to Stats.toCSV(d.events, names, s.site).toByteArray()) }, modifier = Modifier.weight(1f)) { Text("Export CSV") }
        OutlinedButton(
            onClick = onDelete, enabled = !counting, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red),
        ) { Text("Delete") }
    }
    Text(
        "Speeds are estimates from the time between the two lines. Counts are a minimum: road users hidden behind others can be missed.",
        color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp),
    )
}

@Composable
private fun NumRow(cells: List<String>, header: Boolean = false, muted: Boolean = false) {
    val color = if (header || muted) C.muted else C.text
    val size = if (header) 11.5.sp else 13.5.sp
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, c ->
            Text(
                c, color = color, fontSize = size, style = TNUM, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                fontWeight = if (i == cells.size - 1 && !header) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(if (i == 0) 1.5f else 1f),
            )
        }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
private fun Empty(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(text, color = C.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Confirm(title: String, text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { Text(title, color = C.text) },
        text = { Text(text, color = C.muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = C.red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.text) } },
    )
}

/** Share, save or open an exported file. */
@Composable
fun ExportDialog(title: String, text: String, name: String, mime: String, bytes: ByteArray, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) Share.save(context, uri, bytes)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { Text(title, color = C.text) },
        text = { Text("$text\n\n$name", color = C.muted) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (mime == "application/pdf") {
                    TextButton(onClick = {
                        if (!Share.open(context, name, bytes, mime)) Toast.makeText(context, "No app here opens PDFs. Try Share or Save.", Toast.LENGTH_LONG).show()
                    }) { Text("Open", color = C.mint) }
                }
                TextButton(onClick = { saver.launch(name) }) { Text("Save", color = C.mint) }
                TextButton(onClick = {
                    Share.send(context, name, bytes, mime, title)
                    onDismiss()
                }) { Text("Share", color = C.mint) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = C.muted) } },
    )
}
