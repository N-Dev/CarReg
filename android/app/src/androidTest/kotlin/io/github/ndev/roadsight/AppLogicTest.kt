package io.github.ndev.roadsight

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ndev.roadsight.core.Json
import io.github.ndev.roadsight.data.Updates
import io.github.ndev.roadsight.plates.Watchlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Small pieces of the app that need Android: update checks, watchlist keys, settings in a backup. */
@RunWith(AndroidJUnit4::class)
class AppLogicTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()

    @Test
    fun releasesFromGitHub() {
        assertEquals(15, Updates.buildOf("v1.0.15"))
        assertEquals(7, Updates.buildOf("1.0.7"))
        assertNull(Updates.buildOf("latest"))
        val r = Updates.parse(
            """{"tag_name":"v1.0.15","html_url":"https://github.com/N-Dev/CarReg/releases/tag/v1.0.15","body":"Fixes ’n’ things\r\n",""" +
                """"assets":[{"name":"RoadSight.apk","browser_download_url":"https://github.com/N-Dev/CarReg/releases/download/v1.0.15/RoadSight.apk"}]}""",
        )!!
        assertEquals(15, r.build)
        assertEquals("1.0.15", r.version)
        assertTrue(r.apk.endsWith("/v1.0.15/RoadSight.apk"))
        assertNull(Updates.parse("""{"message":"Not Found"}"""))
    }

    @Test
    fun watchlistKeysMatchTheReader() {
        // Typed with the reader's usual confusions and any spacing: matched the way the reader keys plates.
        assertEquals("241D12345", Watchlist.keyFor("241-d-12345"))
        assertEquals("241D12345", Watchlist.keyFor(" 241 D 12345 "))
        assertEquals("AB12CDE", Watchlist.keyFor("ab12 cde"))
    }

    @Test
    fun settingsGoIntoABackupAndComeBack() {
        val p = app.prefs
        val keep = p.site to p.dimAfter
        try {
            p.site = "Backup lane"
            p.dimAfter = 300
            val saved = Json.write(p.export())
            p.site = "Somewhere else"
            p.dimAfter = 0
            p.import(Json.obj(saved))
            assertEquals("Backup lane", p.site)
            assertEquals(300, p.dimAfter)
            assertTrue("phone-specific settings aren't in a backup", "tuned" !in p.export())
        } finally {
            p.site = keep.first
            p.dimAfter = keep.second
        }
    }
}
