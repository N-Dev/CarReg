package io.github.ndev.roadsight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Screenshots and results the CI job pulls off the emulator and publishes (android-ci branch). */
object Shots {
    val dir: File
        // The app's own files: the CI job copies them off with `run-as` (the debug app allows it).
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ci").apply { mkdirs() }

    fun take(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log("screenshot $name")
    }

    fun log(line: String) {
        Log.i("RoadSightTest", line)
        File(dir, "results.txt").appendText(line + "\n")
    }

    fun save(name: String, bytes: ByteArray) {
        File(dir, name).writeBytes(bytes)
    }

    fun sample(name: String): Bitmap =
        InstrumentationRegistry.getInstrumentation().targetContext.assets.open("samples/$name").use { BitmapFactory.decodeStream(it) }
}
