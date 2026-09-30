package io.github.ndev.roadsight.plates

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.core.plate.PlateInfo
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.PlateGraphic
import java.text.DateFormat
import java.util.Date

/** What the details sheet shows about a plate, from the live tray, a photo or history. */
class PlateView(
    val text: String,
    val key: String,
    val region: String?,
    val profile: String,
    val format: String?,
    val valid: Boolean,
    val info: PlateInfo?,
    val score: Double,
    val thumb: ImageBitmap?,
    val count: Int? = null,
    val first: Long? = null,
    val last: Long? = null,
    val source: String? = null,
)

fun registered(profile: String, info: PlateInfo?): String {
    val y = info?.year ?: return ""
    return when (profile) {
        "IE" -> if (info.period != null) "${info.period} $y" else "$y"
        "UK" -> if (info.period == "Mar–Aug") "Mar–Aug $y" else "Sep $y – Feb ${y + 1}"
        else -> "$y"
    }
}

fun formatName(v: PlateView): String = formatName(v.valid, v.profile, v.format)

fun formatName(valid: Boolean, profile: String, format: String?): String = when {
    !valid -> ""
    profile == "IE" -> "Irish"
    format == "NI" -> "NI"
    profile == "UK" -> "UK"
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateSheet(v: PlateView, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlateGraphic(v.text, Formats.bandFor(v.region), big = true, yellow = v.profile == "UK")
            }
            Spacer(Modifier.height(10.dp))
            val flag = Formats.flagFor(v.region)
            val tags = listOfNotNull(
                v.region?.let { "$flag $it".trim() },
                formatName(v).takeIf { it.isNotEmpty() }?.let { "$it format" },
                "${Math.round(v.score * 100)}% sure",
            )
            Text(tags.joinToString(" · "), color = C.muted, fontSize = 13.5.sp)
            if (v.thumb != null) {
                Spacer(Modifier.height(14.dp))
                Image(
                    v.thumb, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            val rows = ArrayList<Pair<String, String>>()
            val i = v.info
            if (i?.county != null) rows.add("County" to (i.county + (i.countyGa?.takeIf { it != i.county }?.let { " · $it" } ?: "")))
            if (i?.area != null) rows.add("Registered in" to i.area!!)
            registered(v.profile, i).takeIf { it.isNotEmpty() }?.let { rows.add("Registered" to it) }
            v.count?.let { rows.add("Seen" to if (it == 1) "once" else "$it times") }
            val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            v.first?.let { rows.add("First seen" to df.format(Date(it))) }
            if (v.last != null && v.last != v.first) rows.add("Last seen" to df.format(Date(v.last)))
            v.source?.let { rows.add("From" to if (it == "photo") "a photo" else "the live camera") }
            for ((k, value) in rows) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(k, color = C.muted, fontSize = 14.sp, modifier = Modifier.width(120.dp))
                    Text(value, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, v.text) }) { Text("Copy") }
                OutlinedButton(onClick = { share(context, v) }) { Text("Share") }
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete, colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red)) { Text("Delete") }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Number plate", text))
    Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, v: PlateView) {
    val text = v.text + (v.region?.let { " ($it)" } ?: "")
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(i, "Share plate"))
}
