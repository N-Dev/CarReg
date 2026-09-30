package io.github.ndev.roadsight.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Hands files (CSV exports, the council report) to other apps: share, open, or save where the user picks. */
object Share {
    private const val DAY = 86_400_000L

    /** Writes the bytes to the app's cache and returns a content:// address other apps can be given. */
    fun stage(context: Context, name: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Tidy exports from earlier days.
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > DAY) it.delete() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun send(context: Context, name: String, bytes: ByteArray, mime: String, title: String) {
        val uri = stage(context, name, bytes)
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(Intent.createChooser(i, title))
    }

    /** Opens the file in an app that can show it; false if there isn't one. */
    fun open(context: Context, name: String, bytes: ByteArray, mime: String): Boolean {
        val uri = stage(context, name, bytes)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Writes to a document the user created with the system's "Save to" screen. */
    fun save(context: Context, target: Uri, bytes: ByteArray) {
        try {
            context.contentResolver.openOutputStream(target)?.use { it.write(bytes) } ?: error("couldn’t open it")
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn’t save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
