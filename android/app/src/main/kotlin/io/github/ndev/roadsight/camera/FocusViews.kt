package io.github.ndev.roadsight.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.ui.C
import kotlinx.coroutines.delay

/** Where the picture was last tapped to focus, shown as a ring for a moment. */
class FocusTap(val at: Offset, val time: Long = System.currentTimeMillis())

@Composable
fun FocusRing(tap: FocusTap?, modifier: Modifier) {
    var shown by remember { mutableStateOf<FocusTap?>(null) }
    LaunchedEffect(tap) {
        shown = tap
        if (tap != null) {
            delay(1200)
            shown = null
        }
    }
    val t = shown ?: return
    Canvas(modifier) {
        drawCircle(C.amber, 34.dp.toPx(), t.at, style = Stroke(2.dp.toPx()))
        drawCircle(C.amber.copy(alpha = 0.6f), 4.dp.toPx(), t.at)
    }
}

/** "Focus held here · tap to let it refocus": shown while the focus is held on a tapped spot. */
@Composable
fun FocusHeldPill(modifier: Modifier = Modifier, onRelease: () -> Unit) {
    Text(
        "Focus held on the spot you tapped · tap here to let it refocus",
        color = C.bg, fontSize = 12.5.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(C.amber).clickable(onClick = onRelease)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
