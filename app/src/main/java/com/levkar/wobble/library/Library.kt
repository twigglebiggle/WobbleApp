package com.levkar.wobble.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.levkar.wobble.core.WblDocument
import com.levkar.wobble.core.WblFile
import com.levkar.wobble.core.WblWriter
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * The wobble library: a visible folder the user picks once (e.g. Pictures/Wobble).
 * Collections will be its subfolders. Everything goes through Android's document APIs,
 * so no storage permissions are needed. Call from a background thread except where noted.
 */
object Library {
    private const val PREFS = "library"
    private const val KEY_TREE = "tree_uri"
    private const val MIME = "application/octet-stream"
    const val MAX_SIDE = 4096

    // ---- the folder ----

    fun treeUri(c: Context): Uri? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_TREE, null)?.let(Uri::parse)
        ?.takeIf { t -> c.contentResolver.persistedUriPermissions.any { it.uri == t && it.isWritePermission } }

    /** Main thread OK. Stores the folder picked with ACTION_OPEN_DOCUMENT_TREE. */
    fun setTree(c: Context, tree: Uri) {
        // Let go of the previous folder's access so it doesn't linger in the system's grant list.
        treeUri(c)?.takeIf { it != tree }?.let { old ->
            runCatching { c.contentResolver.releasePersistableUriPermission(old,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        c.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply()
    }

    /** The folder itself as a document Uri (for EXTRA_INITIAL_URI in pickers). */
    fun folderDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** True if [uri] is a document inside the library folder. */
    fun contains(c: Context, uri: Uri): Boolean {
        val tree = treeUri(c) ?: return false
        if (uri.authority != tree.authority || !DocumentsContract.isDocumentUri(c, uri)) return false
        return DocumentsContract.getDocumentId(uri).startsWith(DocumentsContract.getTreeDocumentId(tree))
    }

    /** Display names of the files directly in the library folder (or in collection [dir], a document id). */
    fun names(c: Context, tree: Uri, dir: String? = null): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir ?: DocumentsContract.getTreeDocumentId(tree))
        val out = HashSet<String>()
        c.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) out += it.getString(0)
        }
        return out
    }

    /** Creates "<base>.wbl" (or "<base> (2).wbl", …) in the library (or collection [dir]) and writes [bytes]. Returns its Uri. */
    fun create(c: Context, tree: Uri, base: String, bytes: ByteArray, dir: String? = null): Uri {
        val taken = names(c, tree, dir)
        var name = "$base.wbl"; var n = 2
        while (name in taken) name = "$base (${n++}).wbl"
        val parent = dir?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) } ?: folderDoc(tree)
        val uri = DocumentsContract.createDocument(c.contentResolver, parent, MIME, name)
            ?: throw IllegalStateException("Couldn't create $name")
        write(c, uri, stampCreated(bytes))
        return uri
    }

    /** A new library piece (new, import, duplicate) gets "created = now" (field 33) unless it already has one. */
    private fun stampCreated(bytes: ByteArray): ByteArray = runCatching {
        val doc = WblFile.parse(bytes).document ?: return bytes
        if (doc.created != null) return bytes
        val img = WblFile.sourceImage(bytes)?.first ?: return bytes
        WblWriter.write(doc.withCreated(System.currentTimeMillis()), img)
    }.getOrDefault(bytes)

    fun write(c: Context, uri: Uri, bytes: ByteArray) {
        c.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    fun read(c: Context, uri: Uri): ByteArray = c.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    /** First run: put the bundled samples into an empty library so it isn't empty. */
    fun seedSamples(c: Context, tree: Uri) {
        val existing = names(c, tree)
        if (existing.any { it.endsWith(".wbl", ignoreCase = true) }) return
        for (f in c.assets.list("samples").orEmpty()) {
            create(c, tree, f.substringBeforeLast('.'), c.assets.open("samples/$f").use { it.readBytes() })
        }
    }

    // ---- collections: subfolders of the library, one level deep ----

    class Collection(val id: String, val uri: Uri, val name: String, val modified: Long)

    fun collections(c: Context, tree: Uri): List<Collection> {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val out = ArrayList<Collection>()
        c.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, root), arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
            while (it.moveToNext()) {
                if (it.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = it.getString(1) ?: continue
                if (name.startsWith(".")) continue
                out += Collection(it.getString(0), DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)), name, it.getLong(3))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun createCollection(c: Context, tree: Uri, name: String): Collection {
        val clean = name.trim().replace('/', '_').ifEmpty { "Collection" }
        val uri = DocumentsContract.createDocument(c.contentResolver, folderDoc(tree), DocumentsContract.Document.MIME_TYPE_DIR, clean)
            ?: throw IllegalStateException("Couldn't create $clean")
        return Collection(DocumentsContract.getDocumentId(uri), uri, clean, System.currentTimeMillis())
    }

    fun renameCollection(c: Context, col: Collection, name: String): Uri? =
        DocumentsContract.renameDocument(c.contentResolver, col.uri, name.trim().replace('/', '_').ifEmpty { col.name })

    /**
     * Moves a piece between the library and its collections ([from]/[to] = folder document ids, null = the
     * library itself). Uses the provider's move when it has one; otherwise copies, then deletes the original.
     */
    fun move(c: Context, tree: Uri, piece: Uri, from: String?, to: String?): Uri {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val src = DocumentsContract.buildDocumentUriUsingTree(tree, from ?: root)
        val dst = DocumentsContract.buildDocumentUriUsingTree(tree, to ?: root)
        runCatching { DocumentsContract.moveDocument(c.contentResolver, piece, src, dst) }.getOrNull()?.let { return it }
        val base = displayName(c, piece).removeSuffix(".wbl").removeSuffix(".WBL")
        val copy = create(c, tree, base, read(c, piece), to)
        DocumentsContract.deleteDocument(c.contentResolver, piece)
        return copy
    }

    /** Deletes a collection; its pieces go back to the library first (nothing is lost). */
    fun deleteCollection(c: Context, tree: Uri, col: Collection) {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, col.id)
        val ids = ArrayList<String>()
        c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { while (it.moveToNext()) ids += it.getString(0) }
        for (id in ids) move(c, tree, DocumentsContract.buildDocumentUriUsingTree(tree, id), col.id, null)
        DocumentsContract.deleteDocument(c.contentResolver, col.uri)
    }

    // ---- import ----

    fun displayName(c: Context, uri: Uri): String {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let { n -> return n }
        }
        return uri.lastPathSegment ?: "wobble"
    }

    /**
     * Brings any picked file into the library and returns the library copy.
     * - Already inside the library: returned as-is.
     * - A .wbl (image with wobble data): copied byte for byte.
     * - A plain image: becomes a new .wbl with no bubbles (camera rotation applied, capped at [MAX_SIDE]).
     */
    fun importAny(c: Context, tree: Uri, src: Uri, dir: String? = null): Uri {
        if (contains(c, src)) return src
        val bytes = read(c, src)
        val base = displayName(c, src).substringBeforeLast('.').ifBlank { "wobble" }
        val parsed = WblFile.parse(bytes)
        if (parsed.document != null) return create(c, tree, base, bytes, dir)
        return create(c, tree, base, WblWriter.write(WblDocument(null, emptyList()), prepareImage(bytes)), dir)
    }

    /**
     * Returns PNG/JPEG bytes ready to embed. Untouched if the image is already upright, within
     * [MAX_SIDE] and PNG or JPEG; otherwise decoded, rotated per EXIF, scaled and re-encoded
     * (PNG stays PNG, everything else becomes JPEG q95).
     */
    fun prepareImage(bytes: ByteArray): ByteArray {
        val isPng = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val isJpeg = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longSide = max(bounds.outWidth, bounds.outHeight)
        val upright = orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED
        if ((isPng || isJpeg) && upright && longSide in 1..MAX_SIDE) return bytes

        var sample = 1
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Not an image Android can read")
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val k = minOf(1f, MAX_SIDE.toFloat() / max(bmp.width, bmp.height))
        if (k < 1f) m.postScale(k, k)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream()
        if (isPng) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        return out.toByteArray()
    }
}
