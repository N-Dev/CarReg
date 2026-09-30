package io.github.ndev.roadsight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The web apps' palette.
object C {
    val bg = Color(0xFF07090D)
    val surface = Color(0xFF11151D)
    val surface2 = Color(0xFF171C27)
    val line = Color(0x14FFFFFF)
    val line2 = Color(0x24FFFFFF)
    val text = Color(0xFFEEF1F7)
    val muted = Color(0xFF8F98AD)
    val mint = Color(0xFF6EE7B7)
    val sky = Color(0xFF60A5FA)
    val amber = Color(0xFFFBBF24)
    val red = Color(0xFFF87171)

    // Chart colours, checked for colour-blind separation on the dark surface.
    val dir1 = Color(0xFF3987E5)
    val dir2 = Color(0xFFD95926)
    val over = Color(0xFFD03B3B)
}

@Composable
fun RoadSightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.mint,
            onPrimary = Color(0xFF06281E),
            secondary = C.sky,
            tertiary = C.amber,
            background = C.bg,
            onBackground = C.text,
            surface = C.surface,
            onSurface = C.text,
            surfaceVariant = C.surface2,
            onSurfaceVariant = C.muted,
            surfaceContainer = C.surface,
            surfaceContainerHigh = C.surface2,
            surfaceContainerLow = C.surface,
            outline = C.line2,
            outlineVariant = C.line,
            error = C.red,
            secondaryContainer = Color(0xFF1D3A33),
            onSecondaryContainer = C.mint,
        ),
        content = content,
    )
}

/** A few icons drawn here rather than pulling in the whole extended icon set. */
object Ic {
    private fun icon(name: String, stroke: Boolean = false, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (stroke) {
                path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = build)
            } else {
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = build)
            }
        }.build()

    val plate: ImageVector = icon("plate") {
        moveTo(4f, 7f); lineTo(20f, 7f); quadTo(22f, 7f, 22f, 9f); lineTo(22f, 15f); quadTo(22f, 17f, 20f, 17f)
        lineTo(4f, 17f); quadTo(2f, 17f, 2f, 15f); lineTo(2f, 9f); quadTo(2f, 7f, 4f, 7f); close()
        moveTo(4.5f, 9f); lineTo(6.5f, 9f); lineTo(6.5f, 15f); lineTo(4.5f, 15f); close()
        moveTo(8.5f, 11f); lineTo(19.5f, 11f); lineTo(19.5f, 13f); lineTo(8.5f, 13f); close()
    }

    val traffic: ImageVector = icon("traffic") {
        moveTo(2.5f, 4f); lineTo(4.5f, 4f); lineTo(4.5f, 20f); lineTo(2.5f, 20f); close()
        moveTo(19.5f, 4f); lineTo(21.5f, 4f); lineTo(21.5f, 20f); lineTo(19.5f, 20f); close()
        moveTo(9f, 7f); lineTo(15f, 7f); quadTo(16f, 7f, 16.4f, 8f); lineTo(17.5f, 11f); lineTo(17.5f, 15.5f)
        lineTo(6.5f, 15.5f); lineTo(6.5f, 11f); lineTo(7.6f, 8f); quadTo(8f, 7f, 9f, 7f); close()
        moveTo(9.2f, 9f); lineTo(8.3f, 11f); lineTo(15.7f, 11f); lineTo(14.8f, 9f); close()
        moveTo(7f, 15.5f); lineTo(9.5f, 15.5f); lineTo(9.5f, 17.5f); lineTo(7f, 17.5f); close()
        moveTo(14.5f, 15.5f); lineTo(17f, 15.5f); lineTo(17f, 17.5f); lineTo(14.5f, 17.5f); close()
    }

    val history: ImageVector = icon("history", stroke = true) {
        moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, true, 3.5f, 12f)
        moveTo(3.5f, 12f); lineTo(3.5f, 7.5f)
        moveTo(3.5f, 12f); lineTo(7f, 10.5f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }

    val chart: ImageVector = icon("chart") {
        moveTo(4f, 12f); lineTo(7f, 12f); lineTo(7f, 20f); lineTo(4f, 20f); close()
        moveTo(10.5f, 5f); lineTo(13.5f, 5f); lineTo(13.5f, 20f); lineTo(10.5f, 20f); close()
        moveTo(17f, 9f); lineTo(20f, 9f); lineTo(20f, 20f); lineTo(17f, 20f); close()
    }

    val torch: ImageVector = icon("torch") {
        moveTo(13f, 2f); lineTo(5f, 13.5f); lineTo(11f, 13.5f); lineTo(10f, 22f); lineTo(19f, 10f); lineTo(13f, 10f); close()
    }

    val photo: ImageVector = icon("photo", stroke = true) {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4.5f, 17f); lineTo(9.5f, 11.5f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(19.5f, 17f)
        moveTo(15.5f, 9.3f); arcTo(1.3f, 1.3f, 0f, true, true, 15.49f, 9.3f)
    }

    val pause: ImageVector = icon("pause") {
        moveTo(6.5f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6.5f, 19f); close()
        moveTo(14f, 5f); lineTo(17.5f, 5f); lineTo(17.5f, 19f); lineTo(14f, 19f); close()
    }

    val play: ImageVector = icon("play") {
        moveTo(7f, 4.5f); lineTo(19.5f, 12f); lineTo(7f, 19.5f); close()
    }

    val gauge: ImageVector = icon("gauge", stroke = true) {
        moveTo(4f, 17f); arcTo(8.5f, 8.5f, 0f, true, true, 20f, 17f)
        moveTo(12f, 15f); lineTo(16f, 9f)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.surface)
            .border(1.dp, C.line, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

/** A row of choices, one selected (like the web apps' segmented controls). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(C.surface2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) C.text else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) C.bg else C.muted, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** A setting: title, explanation, and a control underneath (or a switch at the side). */
@Composable
fun SettingRow(title: String, sub: String? = null, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        control()
    }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.mint, checkedThumbColor = C.bg))
    }
}

/** A pill-shaped label, like the status in the web apps' top bar. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, dot: Color? = null, color: Color = C.text, background: Color = Color(0xB3141821)) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, C.line, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(50)).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = color, fontSize = 12.5.sp, maxLines = 1)
    }
}

/** A plate drawn like a real one: blue band with the country code, black characters on white or yellow. */
@Composable
fun PlateGraphic(text: String, band: String, modifier: Modifier = Modifier, big: Boolean = false, yellow: Boolean = false) {
    val h = if (big) 56.dp else 30.dp
    Row(
        modifier
            .height(h)
            .clip(RoundedCornerShape(if (big) 8.dp else 5.dp))
            .background(if (yellow) Color(0xFFF8D548) else Color.White)
            .border(1.5.dp, Color(0xFF1B1B1B), RoundedCornerShape(if (big) 8.dp else 5.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (band.isNotEmpty()) {
            Box(Modifier.width(if (big) 30.dp else 17.dp).height(h).background(Color(0xFF1F4BB8)), contentAlignment = Alignment.BottomCenter) {
                Text(band, color = Color.White, fontSize = if (big) 11.sp else 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = if (big) 6.dp else 3.dp))
            }
        }
        Text(
            text,
            color = Color(0xFF111111),
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = if (big) 30.sp else 16.sp,
            letterSpacing = if (big) 1.5.sp else 0.5.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = if (big) 14.dp else 7.dp),
        )
    }
}
