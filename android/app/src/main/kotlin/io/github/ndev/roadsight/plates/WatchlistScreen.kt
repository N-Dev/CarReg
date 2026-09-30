package io.github.ndev.roadsight.plates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.data.WatchRow
import io.github.ndev.roadsight.history.dayLabel
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.PlateGraphic
import io.github.ndev.roadsight.ui.Segmented
import io.github.ndev.roadsight.ui.rememberNotificationRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** The watchlist (tell me when these plates are seen) and the ignore list (my cars: don't keep them). */
@Composable
fun WatchlistScreen(onClose: () -> Unit) {
    val app = App.instance
    val rows by app.watch.rows.collectAsState()
    var editing by remember { mutableStateOf<WatchRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationRequest()
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text) }
            Text("Watchlist and my cars", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = { adding = true },
                modifier = Modifier.padding(end = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.mint, contentColor = C.bg),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
        val watched = rows.filter { it.mode == Watchlist.WATCH }
        val ignored = rows.filter { it.mode == Watchlist.IGNORE }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Heading("Watching for", "A notification when one of these is read: live, with the screen off (Plates menu → Keep watching with the screen off), or in a photo or video.")
            }
            if (watched.isEmpty()) item { None("No plates yet. Add one, or tap a plate you've scanned and choose Watch for it.") }
            items(watched, key = { "w-" + it.key }) { r -> Entry(r) { editing = r } }
            item {
                Spacer(Modifier.height(10.dp))
                Heading("My cars (ignored)", "Never saved to history or shown in the tray, and no buzz.")
            }
            if (ignored.isEmpty()) item { None("None yet.") }
            items(ignored, key = { "i-" + it.key }) { r -> Entry(r) { editing = r } }
            item {
                Text(
                    "Only watch for plates you have a good reason to, like your own cars or a delivery you're expecting. The lists stay on this phone.",
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
                )
            }
        }
    }

    if (adding) {
        EditDialog(null, onDismiss = { adding = false }) { text, label, mode ->
            if (mode == Watchlist.WATCH) askNotifications()
            app.watch.set(text, label, mode)
            adding = false
        }
    }
    editing?.let { r ->
        EditDialog(r, onDismiss = { editing = null }, onRemove = {
            app.watch.remove(r.key)
            editing = null
        }) { text, label, mode ->
            if (Watchlist.keyFor(text) != r.key) app.watch.remove(r.key)
            app.watch.set(text, label, mode)
            editing = null
        }
    }
}

@Composable
private fun Heading(title: String, sub: String) {
    Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Text(sub, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
}

@Composable
private fun None(text: String) {
    Text(text, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Entry(r: WatchRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(C.surface)
            .border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlateGraphic(r.text, "")
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (r.label.isNotEmpty()) Text(r.label, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val seen = when {
                r.seen == 0 -> "Not seen yet"
                else -> "Seen ${if (r.seen == 1) "once" else "${r.seen} times"} · last ${dayLabel(r.lastSeen)} ${TIME.format(Instant.ofEpochMilli(r.lastSeen))}"
            }
            Text(if (r.mode == Watchlist.WATCH) seen else "Ignored", color = C.muted, fontSize = 12.sp)
        }
    }
}

/** Adding or changing a plate: the plate, a name for it, and which list. */
@Composable
private fun EditDialog(r: WatchRow?, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null, onSave: (String, String, String) -> Unit) {
    var text by remember { mutableStateOf(r?.text ?: "") }
    var label by remember { mutableStateOf(r?.label ?: "") }
    var mode by remember { mutableStateOf(r?.mode ?: Watchlist.WATCH) }
    val key = Watchlist.keyFor(text)
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = C.mint, unfocusedBorderColor = C.line2, cursorColor = C.mint, focusedTextColor = C.text, unfocusedTextColor = C.text,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { Text(if (r == null) "Add a plate" else "Change ${r.text}", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    text, { text = it.uppercase().take(14) }, singleLine = true, label = { Text("Plate") },
                    placeholder = { Text("e.g. 241-D-12345") }, colors = colors,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedTextField(label, { label = it.take(40) }, singleLine = true, label = { Text("Name (optional)") }, placeholder = { Text("e.g. Mum's car") }, colors = colors)
                Segmented(listOf(Watchlist.WATCH to "Watch for it", Watchlist.IGNORE to "My car: ignore"), mode) { mode = it }
                if (text.isNotBlank() && key != Formats.clean(text)) {
                    Text("Matched as $key", color = C.muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, label, mode) }, enabled = key.length >= 2) { Text("Save", color = C.mint) }
        },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = C.red) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = C.muted) }
            }
        },
    )
}

/** Watch / ignore buttons for a plate (in its details sheet). */
@Composable
fun WatchButtons(key: String, text: String) {
    val app = App.instance
    val rows by app.watch.rows.collectAsState()
    val row = rows.firstOrNull { it.key == key }
    val askNotifications = rememberNotificationRequest()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (row == null) {
            TextButton(onClick = {
                askNotifications()
                app.watch.set(text, "", Watchlist.WATCH)
            }) { Text("Watch for it", color = C.mint) }
            TextButton(onClick = { app.watch.set(text, "", Watchlist.IGNORE) }) { Text("My car: ignore", color = C.muted) }
        } else {
            Text(
                if (row.mode == Watchlist.WATCH) "On your watchlist" + (if (row.label.isNotEmpty()) " (${row.label})" else "") else "Ignored: one of your cars",
                color = if (row.mode == Watchlist.WATCH) C.mint else C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = { app.watch.remove(key) }) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = C.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Remove", color = C.muted)
            }
        }
    }
}
