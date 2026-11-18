package com.levkar.wobble.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.levkar.wobble.core.WblFile
import com.levkar.wobble.editor.EditorActivity
import com.levkar.wobble.library.Library
import com.levkar.wobble.viewer.ViewerActivity
import com.levkar.wobble.wallpaper.WallpaperSetter
import com.levkar.wobble.ui.FloatingBar
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.MenuItem
import com.levkar.wobble.ui.ToolButton
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.UiDialog
import com.levkar.wobble.ui.UiMenu
import com.levkar.wobble.ui.oneLine
import com.levkar.wobble.ui.uiText
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Home screen: the library as a small art gallery. Two-column masonry (true image shapes, each tile
 * goes into the shorter column), most recently touched first. Tap opens the viewer; long-press for
 * file actions. Controls at the bottom only.
 */
class GalleryActivity : Activity() {

    private class Item(val uri: Uri, val name: String, val modified: Long)

    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private lateinit var cols: Array<LinearLayout>
    private lateinit var colHeights: FloatArray
    /** Settings → Gallery, read when the screen starts (a change re-creates it on return). */
    private var nCols = 2
    private var showNames = true
    /** Inside a collection: its folder's document id and name (null = the main gallery). */
    private val dir: String? by lazy { intent.getStringExtra(EXTRA_DIR) }
    private val dirName: String? by lazy { intent.getStringExtra(EXTRA_DIR_NAME) }
    private var collectionsShown: List<Library.Collection> = emptyList()
    private lateinit var empty: TextView
    private var generation = 0
    @Volatile private var shown: String? = null
    private val d by lazy { resources.displayMetrics.density }
    private val gap by lazy { (4 * d).toInt() }
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        nCols = if (com.levkar.wobble.settings.Options.COLUMNS.get(this) == 1) 3 else 2
        showNames = com.levkar.wobble.settings.Options.NAMES.get(this)
        cols = Array(nCols) { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
        colHeights = FloatArray(nCols)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(gap, gap, gap, (100 * d).toInt())          // room under the last tiles for the floating bar
            for ((i, c) in cols.withIndex()) addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = gap / 2
                if (i < cols.lastIndex) marginEnd = gap / 2
            })
        }
        // Header: amber dot + wordmark, piece count on the right, hairline below. Scrolls with the tiles.
        countText = uiText("", 11f, Ui.mono, Ui.MUTED)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            val h = (12 * d).toInt(); setPadding(h, (40 * d).toInt(), h, h)
            addView(View(context).apply { background = Ui.box(context, Ui.ACCENT, 5f) },
                LinearLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt()).apply { marginEnd = (8 * d).toInt(); bottomMargin = (6 * d).toInt() })
            addView(uiText("wobble", 22f, Ui.uiBold).apply { letterSpacing = -0.02f })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(countText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (3 * d).toInt() })
            // ⋯ : Import and Change library folder (New is the floating button).
            addView(com.levkar.wobble.ui.IconButton(context, Icon.MORE).apply { elevation = 0f; setOnClickListener { showMore(this) } },
                LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply { marginStart = (10 * d).toInt() })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(if (dir != null) folderHeader() else header)
            addView(View(context).apply { setBackgroundColor(Ui.LINE_SOFT) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(grid)
        }
        empty = uiText("", 15f, Ui.ui, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
            setPadding(gap * 10, gap * 10, gap * 10, gap * 10)
        }
        // New: the one floating control. Long-press cycles corner circle → center circle → center pill.
        val newBtn = NewButton(this).apply { setOnClickListener { withLibrary { pickPhoto() } } }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(ScrollView(this@GalleryActivity).apply { addView(content); isVerticalScrollBarEnabled = false })
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(newBtn, FrameLayout.LayoutParams(1, 1))
            newBtn.applyStyle()
            // Inside a collection: an amber edge down the side, so it never reads as the main gallery.
            if (dir != null) addView(View(context).apply { setBackgroundColor(Ui.ACCENT) },
                FrameLayout.LayoutParams((3 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        })
        hideSystemBars()
        if (dir == null) handleIncoming(intent)
    }

    /** Collection screen header: back, "COLLECTION" over the name, count, ⋯ — on a raised panel. */
    private fun folderHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.PANEL)
            val h = (12 * d).toInt(); setPadding(h + (3 * d).toInt(), (34 * d).toInt(), h, h)
            addView(com.levkar.wobble.ui.IconButton(context, Icon.BACK).apply { setOnClickListener { finish() } },
                LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val s = (12 * d).toInt()
                    addView(ImageView(context).apply { setImageDrawable(Icon.FOLDER.drawable(Ui.ACCENT, 2f)) }, LinearLayout.LayoutParams(s, s).apply { marginEnd = (4 * d).toInt() })
                    addView(uiText("COLLECTION", 10f, Ui.monoMedium, Ui.ACCENT).apply { letterSpacing = 0.12f })
                })
                addView(uiText(dirName ?: "Collection", 19f, Ui.uiSemi).oneLine())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (12 * d).toInt() })
            (countText.parent as? ViewGroup)?.removeView(countText)          // it was built into the main header first
            addView(countText)
            addView(com.levkar.wobble.ui.IconButton(context, Icon.MORE).apply { elevation = 0f; setOnClickListener { showMore(this) } },
                LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply { marginStart = (8 * d).toInt() })
        })
        addView(View(context).apply { setBackgroundColor(Ui.accentA(0x55)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncoming(intent) }

    /** "Open with Wobble": a shared or opened .wbl is copied into the library (wobble data intact) and shown. */
    private fun handleIncoming(i: Intent?) {
        val src: Uri = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return
        i?.action = null                                         // once only (not again on rotation / return)
        withLibrary {
            io.execute {
                try {
                    val tree = Library.treeUri(this) ?: return@execute
                    val inLibrary = Library.importAny(this, tree, src)
                    runOnUiThread {
                        toast("Added to your library as ${Library.displayName(this, inLibrary)}")
                        refresh()
                        startActivity(Intent(this, ViewerActivity::class.java).setData(inLibrary))
                    }
                } catch (e: Exception) { toast("Couldn't open: ${e.message}") }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val o = com.levkar.wobble.settings.Options
        if ((if (o.COLUMNS.get(this) == 1) 3 else 2) != nCols || o.NAMES.get(this) != showNames) { recreate(); return }
        refresh()          // files may have changed in the editor, the viewer, or from outside
    }

    // ---- listing ----

    private fun refresh() {
        val tree = Library.treeUri(this)
        if (tree == null) {
            shown = null
            clear(); countText.text = ""
            empty.text = "Tap + to choose a folder for your wobbles\n(for example Pictures/Wobble)."
            empty.visibility = View.VISIBLE
            return
        }
        val gen = ++generation
        io.execute {
            val items = list(tree)
            val colls = if (dir == null) runCatching { Library.collections(this, tree) }.getOrDefault(emptyList()) else emptyList()
            val sig = colls.joinToString("|") { "${it.id}@${it.modified}" } + "#" + items.joinToString("|") { "${it.uri}@${it.modified}" } +
                "#" + com.levkar.wobble.settings.Options.SORT.get(this)
            if (sig == shown) return@execute                         // unchanged: keep tiles and scroll position
            shown = sig
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                clear()
                collectionsShown = colls
                val pieces = if (items.size == 1) "1 piece" else "${items.size} pieces"
                countText.text = if (colls.isEmpty()) pieces else "$pieces · ${colls.size} ${if (colls.size == 1) "collection" else "collections"}"
                empty.visibility = if (items.isEmpty() && colls.isEmpty()) View.VISIBLE else View.GONE
                empty.text = if (dir != null) "Empty collection. Tap + to add a new one here,\nor long-press a piece in the gallery → Move to…"
                             else "No wobbles yet. Tap + to make one from a photo."
            }
            // Collections first (as cards), then the pieces.
            for (col in colls) {
                if (gen != generation) return@execute
                val previews = runCatching { list(tree, col.id).take(4).mapNotNull { thumbnail(it) } }.getOrDefault(emptyList())
                val count = runCatching { list(tree, col.id).size }.getOrDefault(0)
                runOnUiThread { if (gen == generation) addCard(col, previews, count) }
            }
            // Tiles are added in order, each into the currently shorter column.
            for (it in items) {
                if (gen != generation) return@execute
                val bmp = thumbnail(it) ?: continue
                runOnUiThread { if (gen == generation) addTile(it, bmp) }
            }
        }
    }

    private fun list(tree: Uri, inDir: String? = dir): List<Item> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, inDir ?: DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Item>()
        contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!name.endsWith(".wbl", ignoreCase = true)) continue
                out += Item(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name.removeSuffix(".wbl").removeSuffix(".WBL"), c.getLong(2))
            }
        }
        return if (com.levkar.wobble.settings.Options.SORT.get(this) == 1) out.sortedBy { it.name.lowercase() }
               else out.sortedByDescending { it.modified }               // most recently touched first
    }

    private fun clear() {
        for (c in cols) c.removeAllViews()
        colHeights.fill(0f)
    }

    // ---- thumbnails: decoded at column width, cached in memory and on disk (keyed by file + modified time) ----

    private fun thumbnail(item: Item): Bitmap? {
        val key = sha1(item.uri.toString()) + "_" + item.modified
        thumbs.get(key)?.let { return it }
        val dir = File(cacheDir, "thumbs").apply { mkdirs() }
        val f = File(dir, "$key.jpg")
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { thumbs.put(key, it); return it }
        return try {
            val img = WblFile.parse(Library.read(this, item.uri)).imageBytes
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(img, 0, img.size, bounds)
            val target = resources.displayMetrics.widthPixels / nCols
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(img, 0, img.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scaled = if (bmp.width > target) Bitmap.createScaledBitmap(bmp, target, (bmp.height.toLong() * target / bmp.width).toInt(), true) else bmp
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            dir.listFiles()?.filter { it.name.startsWith(sha1(item.uri.toString())) && it.name != f.name }?.forEach { it.delete() }
            thumbs.put(key, scaled)
            scaled
        } catch (e: Exception) { null }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }   // same key as Thumbs

    // ---- tiles ----

    private fun addTile(item: Item, bmp: Bitmap) {
        val col = colHeights.indices.minByOrNull { colHeights[it] } ?: 0
        colHeights[col] += bmp.height.toFloat() / bmp.width
        val tile = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 3 * d)
            }
            addView(ImageView(context).apply {
                setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_XY
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Name strip: mono, graphite, hairline on top.
            if (showNames) addView(uiText(item.name, 10.5f, Ui.mono, 0xFFCFCFCB.toInt()).oneLine().apply {
                background = android.graphics.drawable.LayerDrawable(arrayOf(
                    android.graphics.drawable.ColorDrawable(0xD1101113.toInt()),
                    android.graphics.drawable.ColorDrawable(0x0FFFFFFF))).apply { setLayerHeight(1, 1) }
                val h = (7 * d).toInt(); setPadding(h, (5 * d).toInt(), h, (5 * d).toInt())
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java).setData(item.uri)) }
            setOnLongClickListener { tileMenu(this, item); true }
        }
        cols[col].addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
    }

    private fun tileMenu(anchor: View, item: Item) {
        UiMenu.show(anchor, listOf(
            MenuItem("Edit", Icon.EDIT) { startActivity(Intent(this, EditorActivity::class.java).setData(item.uri)) },
            MenuItem("Set as wallpaper", Icon.WALLPAPER) { WallpaperSetter.frame(this, item.uri) },
            MenuItem("Save image", Icon.EXPORT) { exportImage(item) },
            MenuItem("Share wobble…", Icon.SHARE) { shareWobble(item) },
            MenuItem("Details", Icon.INFO) { showDetails(item) },
            MenuItem("Make GIF…", Icon.GIF) {
                startActivity(Intent(this, ViewerActivity::class.java).setData(item.uri).putExtra(ViewerActivity.EXTRA_GIF, true))
            },
            MenuItem("Move to…", Icon.FOLDER) { moveMenu(anchor, item) },
            MenuItem("Rename…", Icon.RENAME) { rename(item) },
            MenuItem("Duplicate", Icon.DUPLICATE) {
                io.execute {
                    runCatching { Library.create(this, Library.treeUri(this)!!, item.name + " copy", Library.read(this, item.uri), dir) }
                        .onFailure { toast("Couldn't duplicate: ${it.message}") }
                    runOnUiThread { refresh() }
                }
            },
            MenuItem("Delete…", Icon.DELETE, danger = true) { delete(item) },
        ))
    }

    /** Credits + file facts (Lev, b53). Anything that can't be read shows "—". */
    private fun showDetails(item: Item) {
        io.execute {
            runCatching {
                val bytes = Library.read(this, item.uri)
                val doc = WblFile.parse(bytes).document
                val src = WblFile.sourceImage(bytes)
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                src?.first?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, o) }
                val bubbles = doc?.bubbles.orEmpty()
                com.levkar.wobble.ui.DetailsDialog.Info(item.name, doc?.author, doc?.authorAi == true, doc?.source,
                    doc?.created, item.modified.takeIf { it > 0 }, o.outWidth, o.outHeight,
                    (src?.second ?: "?").uppercase(), bytes.size.toLong(),
                    bubbles.count { !it.freeze }, bubbles.count { it.freeze })
            }.onSuccess { info -> runOnUiThread { com.levkar.wobble.ui.DetailsDialog.show(this, info) { editCredits(item) } } }
             .onFailure { toast("Couldn't read: ${it.message}") }
        }
    }

    /** Author / AI / source link, stored in the file (Lev, b52). */
    private fun editCredits(item: Item) {
        io.execute {
            runCatching {
                val bytes = Library.read(this, item.uri)
                val doc = com.levkar.wobble.core.WblFile.parse(bytes).document ?: com.levkar.wobble.core.WblDocument(null, emptyList())
                runOnUiThread {
                    com.levkar.wobble.ui.CreditsDialog.show(this, doc.author, doc.authorAi, doc.source) { a, ai, src ->
                        io.execute {
                            runCatching {
                                val img = WblFile.sourceImage(bytes)?.first ?: error("not a PNG or JPEG")
                                Library.write(this, item.uri, com.levkar.wobble.core.WblWriter.write(doc.withCredits(a, ai, src), img))
                            }.onSuccess { toast("Credits saved") }.onFailure { toast("Couldn't save: ${it.message}") }
                        }
                    }
                }
            }.onFailure { toast("Couldn't open: ${it.message}") }
        }
    }

    /** The source picture without wobble data, unchanged (PNG stays PNG, JPEG stays JPEG), to Pictures/Wobble. */
    private fun exportImage(item: Item) {
        if (android.os.Build.VERSION.SDK_INT < 29) { toast("Export needs Android 10 or newer"); return }
        io.execute {
            runCatching {
                val (bytes, ext) = WblFile.sourceImage(Library.read(this, item.uri)) ?: error("not a PNG or JPEG")
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${item.name}.$ext")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (ext == "png") "image/png" else "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Wobble")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no storage")
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }.onSuccess { toast("Saved to Pictures/Wobble") }
             .onFailure { toast("Couldn't save: ${it.message}") }
        }
    }

    /**
     * The .wbl itself, wobble data included (a plain picture can be shared from the phone's gallery after Save image).
     * Sent as a file, not an image, so chat apps pass it on untouched instead of recompressing it.
     */
    private fun shareWobble(item: Item) {
        io.execute {
            runCatching {
                val bytes = Library.read(this, item.uri)
                val dir = ShareProvider.dir(this)
                dir.listFiles()?.forEach { it.delete() }                  // only the latest share is kept
                val f = File(dir, "${item.name.replace('/', '_')}.wbl").apply { writeBytes(bytes) }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, ShareProvider.uri(this@GalleryActivity, f))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(send, null)) }
            }.onFailure { toast("Couldn't share: ${it.message}") }
        }
    }

    private fun rename(item: Item) {
        UiDialog.show(this, "Rename", input = item.name, action = "Rename") { text ->
            val name = text.trim()
            if (name.isEmpty() || name == item.name) return@show
            io.execute {
                runCatching { DocumentsContract.renameDocument(contentResolver, item.uri, "$name.wbl") }
                    .onFailure { toast("Couldn't rename: ${it.message}") }
                runOnUiThread { refresh() }
            }
        }
    }

    private fun delete(item: Item) {
        UiDialog.show(this, "Delete \"${item.name}\"?", "The file is removed from your library folder. This can't be undone.",
            action = "Delete", danger = true) {
            io.execute {
                runCatching { DocumentsContract.deleteDocument(contentResolver, item.uri) }
                    .onFailure { toast("Couldn't delete: ${it.message}") }
                runOnUiThread { refresh() }
            }
        }
    }

    // ---- collections ----

    /** A collection in the main gallery: up to four pieces in a grid, folder icon, name and count. */
    private fun addCard(col: Library.Collection, previews: List<Bitmap>, count: Int) {
        val ci = colHeights.indices.minByOrNull { colHeights[it] } ?: 0
        colHeights[ci] += 1.05f
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.pressable(context, Ui.box(context, Ui.PANEL, 10f, 0xFF33353A.toInt()), 10f)
            val p = (5 * d).toInt(); setPadding(p, p, p, 0)
            val grid = android.widget.GridLayout(context).apply { columnCount = 2; rowCount = 2 }
            val cell = ((resources.displayMetrics.widthPixels / nCols) - 16 * d).toInt() / 2
            for (i in 0 until 4) grid.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                previews.getOrNull(i)?.let { setImageBitmap(it) } ?: setBackgroundColor(0xFF25272B.toInt())
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 4 * d) }
            }, android.widget.GridLayout.LayoutParams().apply { width = cell; height = cell; setMargins((1 * d).toInt(), (1 * d).toInt(), (1 * d).toInt(), (1 * d).toInt()) })
            addView(grid)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                val h = (5 * d).toInt(); setPadding(h, (7 * d).toInt(), h, (8 * d).toInt())
                val s = (14 * d).toInt()
                addView(ImageView(context).apply { setImageDrawable(Icon.FOLDER.drawable(Ui.ACCENT, 2f)) }, LinearLayout.LayoutParams(s, s).apply { marginEnd = (6 * d).toInt() })
                addView(uiText(col.name, 12f, Ui.ui).oneLine(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(uiText(count.toString(), 10f, Ui.mono, Ui.MUTED))
            })
            isClickable = true
            setOnClickListener { openCollection(col) }
            setOnLongClickListener {
                UiMenu.show(this, listOf(
                    MenuItem("Rename…", Icon.RENAME) { renameCollection(col) },
                    MenuItem("Delete collection…", Icon.DELETE, danger = true) { deleteCollection(col, count) },
                )); true
            }
        }
        cols[ci].addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
    }

    private fun openCollection(col: Library.Collection) =
        startActivity(Intent(this, GalleryActivity::class.java).putExtra(EXTRA_DIR, col.id).putExtra(EXTRA_DIR_NAME, col.name))

    /** Long-press → Move to…: the collections, a new one, or back to the main gallery. */
    private fun moveMenu(anchor: View, item: Item) {
        val tree = Library.treeUri(this) ?: return
        io.execute {
            val colls = runCatching { Library.collections(this, tree) }.getOrDefault(emptyList())
            runOnUiThread {
                val items = ArrayList<MenuItem>()
                for (c in colls) if (c.id != dir) items += MenuItem(c.name, Icon.FOLDER) { moveTo(item, c.id) }
                items += MenuItem("New collection…", Icon.NEW) { newCollection(item) }
                if (dir != null) items += MenuItem("Main gallery", Icon.BACK) { moveTo(item, null) }
                UiMenu.show(anchor, items)
            }
        }
    }

    private fun moveTo(item: Item, to: String?) {
        val tree = Library.treeUri(this) ?: return
        io.execute {
            runCatching { Library.move(this, tree, item.uri, dir, to) }.onFailure { toast("Couldn't move: ${it.message}") }
            shown = null
            runOnUiThread { refresh() }
        }
    }

    /** ⋯ → New collection… (or from Move to…, then [moving] goes straight into it). */
    private fun newCollection(moving: Item?) {
        withLibrary {
            UiDialog.show(this, "New collection", input = "", action = "Create") { name ->
                val tree = Library.treeUri(this) ?: return@show
                io.execute {
                    runCatching {
                        val col = Library.createCollection(this, tree, name)
                        if (moving != null) Library.move(this, tree, moving.uri, dir, col.id)
                    }.onFailure { toast("Couldn't create: ${it.message}") }
                    shown = null
                    runOnUiThread { refresh() }
                }
            }
        }
    }

    private fun renameCollection(col: Library.Collection) {
        UiDialog.show(this, "Rename collection", input = col.name, action = "Rename") { name ->
            io.execute {
                runCatching { Library.renameCollection(this, col, name) }.onFailure { toast("Couldn't rename: ${it.message}") }
                shown = null
                runOnUiThread { refresh() }
            }
        }
    }

    /** From inside a collection: rename it, then reopen under the new name. */
    private fun renameThisCollection() {
        val tree = Library.treeUri(this) ?: return
        val id = dir ?: return
        UiDialog.show(this, "Rename collection", input = dirName ?: "", action = "Rename") { name ->
            io.execute {
                runCatching {
                    val col = Library.collections(this, tree).first { it.id == id }
                    val newUri = Library.renameCollection(this, col, name)
                    val newId = newUri?.let { DocumentsContract.getDocumentId(it) } ?: id
                    runOnUiThread {
                        startActivity(Intent(this, GalleryActivity::class.java).putExtra(EXTRA_DIR, newId).putExtra(EXTRA_DIR_NAME, name.trim()))
                        finish()
                    }
                }.onFailure { toast("Couldn't rename: ${it.message}") }
            }
        }
    }

    private fun deleteCollection(col: Library.Collection, count: Int) {
        val msg = if (count == 0) "It's empty." else "Its ${if (count == 1) "piece goes" else "$count pieces go"} back to the main gallery; nothing is lost."
        UiDialog.show(this, "Delete \"${col.name}\"?", msg, action = "Delete", danger = true) {
            val tree = Library.treeUri(this) ?: return@show
            io.execute {
                runCatching { Library.deleteCollection(this, tree, col) }.onFailure { toast("Couldn't delete: ${it.message}") }
                shown = null
                runOnUiThread { refresh() }
            }
        }
    }

    // ---- New / Import / folder ----

    private var afterLibrary: (() -> Unit)? = null

    private fun withLibrary(then: () -> Unit) {
        if (Library.treeUri(this) != null) { then(); return }
        afterLibrary = then
        toast("Pick or create a folder for your wobbles, e.g. Pictures/Wobble")
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    private fun pickPhoto() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_NEW)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun showMore(anchor: View) {
        if (dir != null) {
            UiMenu.show(anchor, listOf(
                MenuItem("Import…", Icon.IMPORT) { withLibrary { pickFile() } },
                MenuItem("Rename collection…", Icon.RENAME) { renameThisCollection() },
            ))
            return
        }
        UiMenu.show(anchor, listOf(
            MenuItem("New collection…", Icon.FOLDER) { newCollection(null) },
            MenuItem("Import…", Icon.IMPORT) { withLibrary { pickFile() } },
            MenuItem("Settings…", Icon.GEAR) { startActivity(Intent(this, com.levkar.wobble.settings.SettingsActivity::class.java)) },
            MenuItem("Change library folder…", Icon.FOLDER) {
                toast("Pick the new folder for your wobbles")
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    Library.treeUri(this@GalleryActivity)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, Library.folderDoc(it)) }
                }, REQ_TREE)
            },
        ))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { if (requestCode == REQ_TREE) afterLibrary = null; return }
        when (requestCode) {
            REQ_TREE -> {
                Library.setTree(this, uri)
                shown = null
                val then = afterLibrary; afterLibrary = null
                io.execute {
                    runCatching { Library.seedSamples(this, uri) }
                    runOnUiThread { refresh(); then?.invoke() }
                }
            }
            REQ_NEW, REQ_IMPORT -> io.execute {
                try {
                    val tree = Library.treeUri(this) ?: return@execute
                    val inLibrary = Library.importAny(this, tree, uri, dir)
                    runOnUiThread {
                        if (requestCode == REQ_NEW) {
                            // A new wobble has no bubbles yet: go straight to the editor.
                            startActivity(Intent(this, EditorActivity::class.java).setData(inLibrary))
                        } else {
                            toast("Added to your library as ${Library.displayName(this, inLibrary)}")
                            refresh()
                        }
                    }
                } catch (e: Exception) {
                    toast("Couldn't import: ${e.message}")
                }
            }
        }
    }

    // ---- misc ----

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    companion object {
        private const val REQ_TREE = 1
        private const val REQ_NEW = 2
        private const val REQ_IMPORT = 3
        const val EXTRA_DIR = "collection_id"
        const val EXTRA_DIR_NAME = "collection_name"
    }
}
