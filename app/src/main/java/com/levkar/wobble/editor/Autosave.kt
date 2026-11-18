package com.levkar.wobble.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.levkar.wobble.core.WblDocument
import com.levkar.wobble.core.WblWriter
import com.levkar.wobble.library.Library
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * "AndWobble almost never lost progress."
 * Every committed edit → (1) written at once to a private journal file, atomically (temp + rename);
 * (2) shortly after, and on [flush], copied into the library .wbl, then the journal is deleted.
 * A journal that still exists when the file is opened again means step 2 never happened: recover it.
 */
class Autosave(private val context: Context, private val uri: Uri, private val original: ByteArray) {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val journal = journalFile(context, uri)
    @Volatile private var generation = 0
    private val toLibrary = Runnable { val g = generation; io.execute { copyToLibrary(g) } }

    /** Main thread. Saves [doc] (journal now, library a moment later). */
    fun commit(doc: WblDocument) {
        val g = ++generation
        io.execute {
            val bytes = WblWriter.write(doc, original)
            val tmp = File(journal.parentFile, journal.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(journal)
            if (g == generation) { main.removeCallbacks(toLibrary); main.postDelayed(toLibrary, DELAY_MS) }
        }
    }

    /** Main thread. Pushes any pending change to the library right away (call from onPause). */
    fun flush() {
        main.removeCallbacks(toLibrary)
        val g = generation
        io.execute { copyToLibrary(g) }
    }

    private fun copyToLibrary(g: Int) {
        if (!journal.exists()) return
        try {
            Library.write(context, uri, journal.readBytes())
            if (g == generation) journal.delete()          // nothing newer arrived meanwhile
        } catch (_: Exception) {
            // Keep the journal; it will be recovered the next time this file opens.
        }
    }

    companion object {
        private const val DELAY_MS = 800L

        fun journalFile(c: Context, uri: Uri): File {
            val dir = File(c.filesDir, "journal").apply { mkdirs() }
            val hash = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return File(dir, "$hash.wbl")
        }

        /** Unsaved bytes for [uri] from a previous session, if any. */
        fun recover(c: Context, uri: Uri): ByteArray? = journalFile(c, uri).takeIf { it.exists() }?.readBytes()
    }
}
