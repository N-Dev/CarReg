package io.github.ndev.roadsight.data

import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.BuildConfig
import io.github.ndev.roadsight.core.Json
import io.github.ndev.roadsight.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Looks for a newer RoadSight on GitHub (at most once a day, and only if allowed in Settings). Releases
 * are tagged v1.0.<build>, and the build number is the app's version code, so a higher one is newer.
 * Nothing is sent but the request itself.
 */
class Updates(private val app: App) {
    class Release(val version: String, val build: Int, val apk: String, val page: String)

    companion object {
        const val API = "https://api.github.com/repos/N-Dev/CarReg/releases/latest"
        const val APK = "https://github.com/N-Dev/CarReg/releases/latest/download/RoadSight.apk"
        const val PAGE = "https://github.com/N-Dev/CarReg/releases/latest"
        private const val DAY = 86_400_000L

        /** "v1.0.12" → 12 */
        fun buildOf(tag: String): Int? = tag.trim().removePrefix("v").split('.').lastOrNull()?.toIntOrNull()

        /** The latest release from GitHub's answer, or null if it doesn't look like one of ours. */
        fun parse(json: String): Release? {
            val m = Json.obj(json)
            val tag = m["tag_name"] as? String ?: return null
            val build = buildOf(tag) ?: return null
            val apk = (m["assets"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull { it["name"] == "RoadSight.apk" }?.get("browser_download_url") as? String
            return Release(tag.removePrefix("v"), build, apk ?: APK, m["html_url"] as? String ?: PAGE)
        }
    }

    /** A newer release than this one, if the last check found one. */
    val available = MutableStateFlow<Release?>(null)
    val checking = MutableStateFlow(false)

    init {
        // What the last check found, until the next one.
        runCatching {
            val saved = app.prefs.latestRelease
            if (saved.isNotEmpty()) {
                val m = Json.obj(saved)
                val r = Release(m["version"] as String, (m["build"] as Double).toInt(), m["apk"] as String, m["page"] as String)
                if (r.build > BuildConfig.VERSION_CODE) available.value = r
            }
        }
    }

    fun checkIfDue() {
        if (!app.prefs.updateCheck) return
        if (System.currentTimeMillis() - app.prefs.lastUpdateCheck < DAY) return
        check()
    }

    /**
     * Asks GitHub for the latest release, on a background thread. `done` is called on that thread with
     * the newer release (null if this is the latest), or a failure.
     */
    fun check(done: (Result<Release?>) -> Unit = {}) {
        if (checking.value) return
        checking.value = true
        thread(name = "update-check", isDaemon = true) {
            val r = runCatching { fetch() }
            r.onSuccess { rel ->
                app.prefs.lastUpdateCheck = System.currentTimeMillis()
                app.prefs.latestRelease = rel?.let { Json.write(mapOf("version" to it.version, "build" to it.build, "apk" to it.apk, "page" to it.page)) } ?: ""
                val newer = rel?.takeIf { it.build > BuildConfig.VERSION_CODE }
                available.value = newer
                DebugLog.add("update", if (newer != null) "RoadSight ${newer.version} is out (this is ${BuildConfig.VERSION_NAME})" else "Up to date (${rel?.version ?: "no releases"})")
            }.onFailure { DebugLog.error("update", "Couldn't check for updates", it) }
            checking.value = false
            done(r.map { rel -> rel?.takeIf { it.build > BuildConfig.VERSION_CODE } })
        }
    }

    private fun fetch(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "RoadSight/${BuildConfig.VERSION_NAME} (Android)")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode}")
            return parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
