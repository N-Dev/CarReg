package io.github.ndev.roadsight

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ndev.roadsight.core.traffic.Lines
import io.github.ndev.roadsight.data.Share
import io.github.ndev.roadsight.video.VideoCountJob
import io.github.ndev.roadsight.video.VideoDecoder
import io.github.ndev.roadsight.video.VideoPlatesJob
import io.github.ndev.roadsight.video.VideoSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.math.abs

/**
 * Videos through the phone's decoder and the same AI as the camera (videos made by
 * android/ci/make-test-videos.py): frames upright, traffic counted with speeds, plates read.
 */
@RunWith(AndroidJUnit4::class)
class VideoTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()

    /** A test video, handed to the app as another app would (a content:// address). */
    private fun video(name: String): Uri {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("videos/$name").use { it.readBytes() }
        return Share.stage(app, name, bytes)
    }

    private fun png(b: Bitmap): ByteArray = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    @Test
    fun framesComeOutUpright() {
        val d = VideoDecoder(app, video("traffic.mp4"))
        val info = d.info()
        Shots.log("traffic.mp4: ${info.width}x${info.height}, ${info.durationMs} ms, ${info.fps} fps, ${info.mime}, recorded ${info.recorded?.let { Instant.ofEpochMilli(it) }}")
        assertEquals(960, info.width)
        assertEquals(540, info.height)
        assertTrue(abs(info.durationMs - 11_000) < 300)
        assertEquals(Instant.parse("2026-09-29T07:10:00Z").toEpochMilli(), info.recorded)
        var n = 0
        var last = 0.0
        val t0 = System.nanoTime()
        d.frames(1280, 1000.0 / 15) { b, t ->
            assertEquals(960, b.width)
            if (n == 90) Shots.save("video-traffic-frame.png", png(b))
            n++
            last = t
            true
        }
        Shots.log("traffic.mp4: $n frames at 15 a second, the last at ${last.toInt()} ms, decoded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue("frames: $n", n in 155..170)

        // Filmed with the phone upright: stored on its side, turned back upright.
        val p = VideoDecoder(app, video("plates-portrait.mp4"))
        val pi = p.info()
        assertEquals(90, pi.rotation)
        assertEquals(720, pi.width)
        assertEquals(1280, pi.height)
        val still = p.still(1280)
        assertNotNull(still)
        assertEquals(720, still!!.width)
        assertEquals(1280, still.height)
        Shots.save("video-portrait-still.png", png(still))
    }

    @Test
    fun countsTheTrafficInAVideo() {
        val p = app.prefs
        val model = p.trafficModel
        p.trafficModel = "nano"
        try {
            val uri = video("traffic.mp4")
            val info = VideoDecoder(app, uri).info()
            val setup = VideoSetup(
                Lines(doubleArrayOf(0.35, 0.35, 0.35, 0.95), doubleArrayOf(0.65, 0.35, 0.65, 0.95)),
                20.0, 50, "Left to right", "Right to left", "Video road", info.recorded ?: 0L,
            )
            val t0 = System.nanoTime()
            val id = VideoCountJob(app, uri, info, setup).run()
            assertNotNull(id)
            val s = app.db.session(id!!)!!
            val events = app.db.events(id)
            Shots.log("video count in ${(System.nanoTime() - t0) / 1_000_000} ms: ${events.map { "${it.kind} dir ${it.dir} ${it.speed} km/h ${it.length} m" }}")
            assertEquals("video", s.source)
            assertEquals(setup.start, s.started)
            val east = events.firstOrNull { it.dir == 1 && it.speed != null }
            val west = events.firstOrNull { it.dir == 2 && it.speed != null }
            assertNotNull(events.toString(), east)
            assertNotNull(events.toString(), west)
            assertTrue("east ${east!!.speed}", abs(east.speed!! - 40) <= 6)
            assertTrue("west ${west!!.speed}", abs(west.speed!! - 60) <= 9)
            // Dated by the video: the first car crosses line A about 4 seconds in.
            assertTrue("dated from the recording", east.t - setup.start in 2_000..8_000)
            app.db.deleteSession(id)
        } finally {
            p.trafficModel = model
        }
    }

    @Test
    fun readsThePlatesInVideos() {
        for (name in listOf("plates.mp4", "plates-portrait.mp4")) {
            val uri = video(name)
            val info = VideoDecoder(app, uri).info()
            val t0 = System.nanoTime()
            val found = VideoPlatesJob(app, uri, info).run()
            assertNotNull(found)
            Shots.log("$name: ${found!!.map { "${it.result.text} at ${it.atMs.toInt()} ms" }} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertTrue(name, found.any { it.result.key == "241D12345" })
        }
    }
}
