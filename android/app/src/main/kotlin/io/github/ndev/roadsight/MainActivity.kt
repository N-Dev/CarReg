package io.github.ndev.roadsight

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ndev.roadsight.history.HistoryScreen
import io.github.ndev.roadsight.plates.PlatesScreen
import io.github.ndev.roadsight.settings.SettingsScreen
import io.github.ndev.roadsight.traffic.TrafficScreen
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.Ic
import io.github.ndev.roadsight.ui.RoadSightTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent { RoadSightTheme { RoadSightApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) App.instance.sharedPhoto.value = uri
    }
}

enum class Tab(val label: String) { PLATES("Plates"), TRAFFIC("Traffic"), HISTORY("History"), SETTINGS("Settings") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoadSightApp() {
    val app = App.instance
    var tab by rememberSaveable { mutableStateOf(Tab.PLATES) }
    val counting by app.traffic.ui.collectAsState()
    val shared by app.sharedPhoto.collectAsState()
    val openSession by app.openSession.collectAsState()
    LaunchedEffect(shared) { if (shared != null) tab = Tab.PLATES }
    LaunchedEffect(openSession) { if (openSession != null) tab = Tab.HISTORY }
    // While counting, the traffic screen has the whole screen (and the tabs can't stop it by accident).
    val fullScreen = tab == Tab.TRAFFIC && counting.running

    Scaffold(
        containerColor = C.bg,
        // Each screen places itself around the status bar; the tab bar looks after the navigation bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!fullScreen) {
                NavigationBar(containerColor = C.surface, tonalElevation = 0.dp) {
                    for (t in Tab.entries) {
                        NavigationBarItem(
                            modifier = Modifier.testTag("tab-${t.name.lowercase()}"),
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(iconFor(t), contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = C.bg,
                                indicatorColor = C.mint,
                                selectedTextColor = C.text,
                                unselectedIconColor = C.muted,
                                unselectedTextColor = C.muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (fullScreen) Modifier.navigationBarsPadding() else Modifier)
                .background(C.bg),
        ) {
            when (tab) {
                Tab.PLATES -> CameraGate { PlatesScreen() }
                Tab.TRAFFIC -> CameraGate { TrafficScreen() }
                Tab.HISTORY -> HistoryScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

private fun iconFor(t: Tab): ImageVector = when (t) {
    Tab.PLATES -> Ic.plate
    Tab.TRAFFIC -> Ic.traffic
    Tab.HISTORY -> Ic.history
    Tab.SETTINGS -> Icons.Filled.Settings
}

/** Shows the screen once the camera is allowed; asks for it otherwise. */
@Composable
fun CameraGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted && !asked) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera needed", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "RoadSight reads plates and counts traffic from the camera. Everything is analysed on this phone and nothing is uploaded.",
                color = C.muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = C.mint, contentColor = C.bg),
            ) { Text("Allow the camera") }
        }
    }
}
