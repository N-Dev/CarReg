package io.github.ndev.roadsight

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.ndev.roadsight.core.traffic.Lines
import io.github.ndev.roadsight.core.traffic.Report
import io.github.ndev.roadsight.core.traffic.ReportSession
import io.github.ndev.roadsight.core.traffic.Stats
import io.github.ndev.roadsight.data.Share
import io.github.ndev.roadsight.service.BackgroundService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/** The app's screens on a device: each tab, photo mode, and a counting session through to its report. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)).around(compose)

    private val app: App get() = App.instance

    private fun waitForText(text: String, timeoutMs: Long = 60_000, substring: Boolean = false) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settle(ms: Long = 1500) {
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    @Test
    fun everyTab() {
        // Plates: the live camera (the emulator's is a test pattern).
        settle(6000)
        Shots.take("01-plates")
        compose.onNodeWithTag("tab-traffic").performClick()
        waitForText("Start counting")
        settle(5000)
        Shots.take("02-traffic")
        compose.onNodeWithText("Set up").performClick()
        waitForText("Set up the counting lines")
        settle()
        Shots.take("03-traffic-setup")
        compose.onNodeWithText("Done").performClick()
        waitForText("Start counting")
        compose.onNodeWithTag("tab-history").performClick()
        waitForText("Traffic counts")
        settle()
        Shots.take("04-history")
        compose.onNodeWithTag("tab-settings").performClick()
        waitForText("READING PLATES")
        settle()
        Shots.take("05-settings")
    }

    /** Counting from the screen: the tabs hide, the clock runs, the screen dims, and Stop offers the results. */
    @Test
    fun countingFromTheTrafficScreen() {
        val p = app.prefs
        val dim = p.dimAfter
        p.dimAfter = 3
        try {
            compose.onNodeWithTag("tab-traffic").performClick()
            waitForText("Start counting")
            compose.waitUntil(60_000) { app.traffic.ui.value.ready }
            compose.onNodeWithText("Start counting").performClick()
            waitForText("Stop")
            settle(1000)
            Shots.take("09-counting")
            assertTrue("the tabs are hidden while counting", compose.onAllNodesWithTag("tab-plates").fetchSemanticsNodes().isEmpty())
            // Dims after 3 s without a touch (1 min normally); a tap wakes it.
            waitForText("tap to wake", timeoutMs = 15_000, substring = true)
            Shots.take("10-dimmed")
            compose.onNodeWithText("tap to wake", substring = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("tap to wake", substring = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Stop").performClick()
            waitForText("See results")
            Shots.take("11-stopped")
            compose.onNodeWithText("See results").performClick()
            waitForText("Report for the council (PDF)")
            settle()
            Shots.take("12-results")
        } finally {
            p.dimAfter = dim
        }
    }

    /** Counting carries on with the app in the background (or the screen off), with a notification. */
    @Test
    fun countingCarriesOnInTheBackground() {
        val p = app.prefs
        val bg = p.backgroundCounting
        p.backgroundCounting = true
        try {
            compose.onNodeWithTag("tab-traffic").performClick()
            waitForText("Start counting")
            compose.waitUntil(60_000) { app.traffic.ui.value.ready }
            compose.onNodeWithText("Start counting").performClick()
            waitForText("Stop")
            compose.waitUntil(10_000) { BackgroundService.mode.value == BackgroundService.TRAFFIC }
            val nm = app.getSystemService(NotificationManager::class.java)
            compose.waitUntil(10_000) { nm.activeNotifications.any { it.id == 7 } }
            // Leave the app: the camera and the counting go on.
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(3000)
            val before = app.traffic.ui.value.frames
            Thread.sleep(6000)
            val after = app.traffic.ui.value.frames
            Shots.log("background: ${after - before} frames analysed in 6 s with the app in the background")
            assertTrue("frames analysed in the background ($before to $after)", after > before)
            // Back to the app, and stop.
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            waitForText("Stop")
            settle()
            compose.onNodeWithText("Stop").performClick()
            waitForText("See results")
            compose.waitUntil(10_000) { BackgroundService.mode.value == null }
        } finally {
            if (app.traffic.ui.value.running) app.traffic.stopSession()
            p.backgroundCounting = bg
        }
    }

    /** Zoom buttons, landscape, and lines for a road running away from the camera. */
    @Test
    fun zoomLandscapeAndARoadRunningAway() {
        val p = app.prefs
        val orient = p.orientation
        val tOrient = p.trafficOrientation
        try {
            compose.waitUntil(30_000) { app.camera.zoom.value != null }
            val z = app.camera.zoom.value!!
            Shots.log("camera zoom: ${z.min}x to ${z.max}x")
            if (z.max >= 2f) {
                compose.onNodeWithText("2×").performClick()
                compose.waitUntil(10_000) { abs((app.camera.zoom.value?.ratio ?: 0f) - 2f) < 0.1f }
                settle()
                Shots.take("15-zoom-2x")
                compose.onNodeWithText("1×").performClick()
            }
            p.orientation = "landscape"
            compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            settle(4000)
            Shots.take("16-plates-landscape")
            p.trafficOrientation = "landscape"
            compose.onNodeWithTag("tab-traffic").performClick()
            waitForText("Start counting")
            settle(4000)
            Shots.take("17-traffic-landscape")
            compose.onNodeWithText("Set up").performClick()
            waitForText("Away from me")
            compose.onNodeWithText("Away from me").performClick()
            waitForText("Towards me")
            settle()
            Shots.take("18-road-away")
            compose.onNodeWithText("Done").performClick()
            waitForText("Start counting")
            settle(2000)
            Shots.take("19-road-away-lines")
            assertTrue("level lines saved", p.roadAway && p.lines.level(9.0 / 16))
            assertTrue("directions", p.dir1 == "Towards me" && p.dir2 == "Away from me")
        } finally {
            p.orientation = orient
            p.trafficOrientation = tOrient
            p.roadAway = false
            p.lines = Lines.default()
            p.dir1 = "Left to right"
            p.dir2 = "Right to left"
        }
    }

    @Test
    fun photoSharedToTheApp() {
        val ctx = app.applicationContext
        val bytes = ctx.assets.open("samples/two_cars.jpg").use { it.readBytes() }
        val uri = Share.stage(ctx, "two_cars.jpg", bytes)
        app.sharedPhoto.value = uri
        waitForText("plates found", timeoutMs = 180_000, substring = true)
        settle()
        Shots.take("06-photo")
        for (t in listOf("241-D-12345", "AB12 CDE")) {
            assertTrue("$t shown", compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty())
        }
    }

    /**
     * A synthetic road fed straight into the traffic counter (the emulator's camera has no traffic):
     * a car left to right at 40 km/h, then one right to left at 60 km/h, with the lines 20 m apart.
     */
    @Test
    fun countingSessionResultsAndReport() {
        val p = app.prefs
        p.lines = Lines(doubleArrayOf(0.35, 0.35, 0.35, 0.95), doubleArrayOf(0.65, 0.35, 0.65, 0.95))
        p.distanceM = 20.0
        p.speedLimit = 50
        p.site = "Test road"
        p.dir1 = "Left to right"
        p.dir2 = "Right to left"
        p.trafficModel = "nano"
        // Only the synthetic frames: the camera mustn't feed the counter too.
        val bg = p.backgroundCounting
        p.backgroundCounting = false
        val tc = app.traffic
        tc.reconfigure()
        tc.startSession()

        val carA = scaled(crop(Shots.sample("car_ie.jpg"), 140, 125, 1220, 690), 300, 170)
        val carB = scaled(crop(Shots.sample("two_cars.jpg"), 1690, 118, 1190, 690), 220, 128)
        val frame = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val c = Canvas(frame)
        val paint = Paint()
        val fps = 15
        for (i in 0 until 11 * fps) {
            val t = i.toDouble() / fps
            c.drawColor(Color.rgb(0x9a, 0xa7, 0xb4))
            paint.color = Color.rgb(0x3b, 0x3e, 0x44)
            c.drawRect(0f, 250f, 960f, 470f, paint)
            paint.color = Color.rgb(0xc9, 0xc4, 0xb8)
            c.drawRect(0f, 470f, 960f, 540f, paint)
            paint.color = Color.rgb(0xd8, 0xd9, 0xda)
            c.drawRect(0f, 357f, 960f, 362f, paint)
            if (t >= 5.2) c.drawBitmap(carB, (960 - 240 * (t - 5.2)).roundToInt().toFloat(), (350 - 128).toFloat(), null)
            c.drawBitmap(carA, (-300 + 160 * t).roundToInt().toFloat(), (462 - 170).toFloat(), null)
            if (i == 60) Shots.save("synthetic-frame.png", png(frame))
            tc.onFrame(frame, 1000 + t * 1000)
        }
        val id = tc.stopSession()
        p.backgroundCounting = bg
        assertNotNull(id)
        Thread.sleep(800) // saved on the background thread
        val events = app.db.events(id!!)
        Shots.log("synthetic road: ${events.map { "${it.kind} dir ${it.dir} ${it.speed} km/h ${it.length} m" }}")
        val east = events.firstOrNull { it.dir == 1 && it.speed != null }
        val west = events.firstOrNull { it.dir == 2 && it.speed != null }
        assertNotNull(events.toString(), east)
        assertNotNull(events.toString(), west)
        assertTrue("east ${east!!.speed}", abs(east.speed!! - 40) <= 6)
        assertTrue("west ${west!!.speed}", abs(west.speed!! - 60) <= 9)

        // The council report, as the History screen makes it.
        val s = app.db.session(id)!!
        val sum = Stats.summarize(events, s.started, s.ended, s.limit)
        Shots.save("report.pdf", Report.council(ReportSession(s.site, s.distanceM, s.limit, listOf(s.dir1, s.dir2)), sum, release = BuildConfig.VERSION_NAME, build = "test"))

        // Its results screen, and the export dialog.
        app.openSession.value = id
        waitForText("Report for the council (PDF)")
        settle()
        Shots.take("07-session")
        compose.onNodeWithText("Report for the council (PDF)").performScrollTo().performClick()
        waitForText("Share")
        settle()
        Shots.take("08-report-dialog")
        compose.onNodeWithText("Close").performClick()
    }

    private fun crop(b: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(b, x, y, minOf(w, b.width - x), minOf(h, b.height - y))

    private fun scaled(b: Bitmap, w: Int, h: Int): Bitmap = Bitmap.createScaledBitmap(b, w, h, true)

    private fun png(b: Bitmap): ByteArray = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
