package xyz.sabok.umtomi.ui

import xyz.sabok.umtomi.R

import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt

class BookshelfActivity : BaseActivity() {
    private var sharedSyncJob: kotlinx.coroutines.Job? = null
    @Volatile private var shelfActive = false
    @Volatile private var importingImages = false
    private enum class PdfPageOrientation { PORTRAIT, LANDSCAPE }

    private lateinit var noteInput: EditText
    private var changingNote = false
    private lateinit var store: BookshelfStore
    private lateinit var folderRow: LinearLayout
    private lateinit var gallery: FrameLayout
    private lateinit var columnValue: TextView
    private lateinit var decreaseColumns: Button
    private lateinit var increaseColumns: Button
    private val portStyle by lazy { PortStyle(this) }
    private lateinit var rootLayout: LinearLayout
    private lateinit var headerSection: LinearLayout
    private lateinit var actionsSection: LinearLayout
    private lateinit var foldersSection: LinearLayout
    private lateinit var syncProgressRow: LinearLayout
    private lateinit var syncProgressLabel: TextView
    private lateinit var syncProgressBar: ProgressBar
    private val loadedBitmaps = mutableListOf<Bitmap>()
    private val pdfExecutor = Executors.newSingleThreadExecutor()
    private var galleryExecutor = Executors.newFixedThreadPool(2)
    private var galleryGeneration = 0
    private var isFullscreen = false
    private var pendingPdfOrientation = PdfPageOrientation.PORTRAIT
    private val createPdfDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> uri?.let(::exportCurrentFolderPdf) }
    private val selectImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) importImages(uris) }

    private fun reloadSharedShelf() {
        if (!::gallery.isInitialized || !SharedBookshelfStorage(this).enabled) return
        val fresh = BookshelfStore(this)
        if (fresh.loadError != null) {
            startActivity(android.content.Intent(this, BookshelfStorageActivity::class.java)); finish(); return
        }
        if (fresh.syncSnapshot().toString() != store.syncSnapshot().toString() || fresh.items != store.items) {
            store = fresh; renderFolders(); updateColumnControls(); renderGallery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BookshelfStore(this)
        store.loadError?.let { error ->
            AlertDialog.Builder(this).setTitle(R.string.bookshelf)
                .setMessage(error).setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }.show()
            return
        }
        intent.getStringExtra("port_shelf_folder")?.takeIf { it in store.folders && savedInstanceState == null }?.let(store::selectFolder)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(portStyle.background)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        rootLayout = root
        val header = portStyle.row().apply {
            addView(portStyle.action("‹") { leaveBookshelf() }.apply {
                contentDescription = getString(R.string.port_back)
            })
            addView(portStyle.text(getString(R.string.bookshelf), 25f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(12)
            })
            addView(portStyle.action("☁") {
                startActivity(android.content.Intent(this@BookshelfActivity, DriveSyncActivity::class.java))
            }.apply { contentDescription = getString(R.string.workspace_drive_sync) })
            addView(portStyle.action("•••") { showShelfMenu(getChildAt(3)) }.apply {
                contentDescription = getString(R.string.workspace_actions)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }
        headerSection = header
        root.addView(header, LinearLayout.LayoutParams(-1, dp(60)))
        syncProgressRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        syncProgressLabel = TextView(this).apply {
            setTextColor(androidx.core.content.ContextCompat.getColor(this@BookshelfActivity, R.color.browser_on_surface))
            textSize = 12f
        }
        syncProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        syncProgressRow.addView(syncProgressLabel)
        syncProgressRow.addView(syncProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        root.addView(syncProgressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))

        folderRow = portStyle.row()
        foldersSection = portStyle.stack().apply {
            addView(folderRow, portStyle.matchWrap())
        }
        root.addView(foldersSection, portStyle.matchWrap())

        val actions = portStyle.row()
        actionsSection = actions
        columnValue = portStyle.text("", 15f, portStyle.foreground, true)
        actions.addView(columnValue, LinearLayout.LayoutParams(0, -2, 1f))
        decreaseColumns = columnButton("−") { changeColumnCount(-1) }
        increaseColumns = columnButton("＋") { changeColumnCount(1) }
        actions.addView(decreaseColumns, LinearLayout.LayoutParams(dp(48), dp(44)))
        actions.addView(increaseColumns, LinearLayout.LayoutParams(dp(48), dp(44)))
                noteInput = EditText(this).apply {
            hint = getString(R.string.bookshelf_note)
            maxLines = 3
            setTextColor(androidx.core.content.ContextCompat.getColor(this@BookshelfActivity, R.color.browser_on_surface))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!changingNote) store.setNote(s.toString())
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        noteInput.background = portStyle.rounded(portStyle.surface, 12)
        noteInput.setPadding(dp(12), dp(8), dp(12), dp(8))
        root.addView(noteInput, portStyle.matchWrap().apply { topMargin = dp(8); bottomMargin = dp(8) })
        root.addView(actions, portStyle.matchWrap().apply { bottomMargin = dp(8) })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(if (isFullscreen) 0 else dp(12), if (isFullscreen) 0 else bars.top + dp(10),
                if (isFullscreen) 0 else dp(12), if (isFullscreen) 0 else bars.bottom + dp(10))
            insets
        }
        gallery = FrameLayout(this)
        root.addView(ScrollView(this).apply {
            clipToPadding = false
            addView(gallery)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        portStyle.applySystemBars(this, root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveBookshelf()
        })
        renderFolders()
        updateColumnControls()
        gallery.post { renderGallery() }
    }

    private var promptedSyncFolderInSession = false

    override fun onResume() {
        super.onResume()
        shelfActive = true
        val account = xyz.sabok.umtomi.sync.CloudAccount.account(this)
        if (account != null && SharedBookshelfStorage(this).needsInitialFolderSetup && !promptedSyncFolderInSession) {
            promptedSyncFolderInSession = true
            AlertDialog.Builder(this)
                .setTitle("책장 동기화 공용 폴더 지정")
                .setMessage("Omyaru 브라우저와 Umtomi에서 같은 폴더를 지정하면 책장 원본이 2배로 저장되지 않고 계정별 하위 폴더로 안전하게 공유됩니다.\n\n지금 동기화 폴더를 지정할까요?")
                .setNegativeButton("나중에") { _, _ -> startSharedSync() }
                .setPositiveButton("폴더 지정하기") { _, _ ->
                    startActivity(android.content.Intent(this, BookshelfStorageActivity::class.java))
                }
                .show()
        } else {
            startSharedSync()
        }
        if (::gallery.isInitialized) {
            store = BookshelfStore(this)
            if (store.loadError == null) { renderFolders(); updateColumnControls(); gallery.post { renderGallery() } }
        }
    }

    override fun onPause() {
        shelfActive = false
        if (::syncProgressRow.isInitialized) syncProgressRow.visibility = View.GONE
        super.onPause()
    }

    private fun startSharedSync() {
        if (Build.VERSION.SDK_INT < 26) return
        if (sharedSyncJob?.isActive == true) return
        sharedSyncJob = lifecycleScope.launch {
            while (shelfActive) {
                reloadSharedShelf()
                val prefs = getSharedPreferences("shared-bookshelf", MODE_PRIVATE)
                val account = xyz.sabok.umtomi.sync.CloudAccount.account(this@BookshelfActivity)
                if (!importingImages && account != null && prefs.getBoolean("enabled", false)) {
                    showSyncProgress("업로드 0% (0/${store.items.size})")
                    try {
                        kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                            val request = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
                                .setAccount(android.accounts.Account(account, "com.google"))
                                .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(xyz.sabok.umtomi.sync.shared.DriveAppDataClient.SCOPE)))
                                .setOptOutIncludingGrantedScopes(true).build()
                            val auth = com.google.android.gms.tasks.Tasks.await(
                                com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(applicationContext).authorize(request),
                                25, java.util.concurrent.TimeUnit.SECONDS)
                            check(!auth.hasResolution())
                            val token = requireNotNull(auth.accessToken)
                            xyz.sabok.umtomi.sync.shared.SharedShelfSync.run(applicationContext, account, { token }, applied = {
                                store = BookshelfStore(this@BookshelfActivity)
                                renderFolders(); updateColumnControls(); renderGallery()
                            }, progress = { message -> runOnUiThread { if (!isDestroyed) showSyncProgress(message) } })
                            { !isDestroyed && !importingImages && prefs.getBoolean("enabled", false) && xyz.sabok.umtomi.sync.CloudAccount.account(this@BookshelfActivity) == account }
                        }
                        prefs.edit().putString("status", "자동 동기화 완료").apply()
                        showSyncProgress("완료 100%")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        val status = "동기화 대기 · 공통 책장 연결과 네트워크를 확인해 주세요."
                        prefs.edit().putString("status", status).apply()
                        showSyncProgress(status)
                    }
                    finally {
                        syncProgressRow.postDelayed({ if (!isDestroyed && !isFinishing) syncProgressRow.visibility = View.GONE }, 5000)
                    }
                }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    private fun showSyncProgress(message: String) {
        if (!::syncProgressRow.isInitialized) return
        val percent = Regex("(\\d+)%").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        syncProgressLabel.text = "책장 $message"
        syncProgressBar.progress = percent.coerceIn(0, 100)
        syncProgressRow.visibility = if (isFullscreen) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        galleryGeneration++
        galleryExecutor.shutdownNow()
        recycleBitmaps()
        pdfExecutor.shutdown()
        super.onDestroy()
    }

    private fun leaveBookshelf() {
        if (isFullscreen) toggleFullscreen() else finish()
    }

    @Suppress("DEPRECATION")
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                if (isFullscreen) {
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (isFullscreen) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else View.SYSTEM_UI_FLAG_VISIBLE
        }
        val chromeVisibility = if (isFullscreen) View.GONE else View.VISIBLE
        headerSection.visibility = chromeVisibility
        actionsSection.visibility = chromeVisibility
        foldersSection.visibility = chromeVisibility
        noteInput.visibility = chromeVisibility
        androidx.core.view.ViewCompat.requestApplyInsets(rootLayout)
        rootLayout.setPadding(
            if (isFullscreen) 0 else dp(12),
            if (isFullscreen) 0 else dp(10),
            if (isFullscreen) 0 else dp(12),
            if (isFullscreen) 0 else dp(10),
        )
        gallery.post { renderGallery() }
    }

    private val expandedTreeFolders = mutableSetOf<String>()

    private fun renderFolders() {
        changingNote = true
        noteInput.setText(store.note())
        changingNote = false
        folderRow.removeAllViews()

        val folder = portStyle.action(store.activeFolder.replace("/", "  ›  ") + "  ▾") { showFolderTreeDialog() }
        folder.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        folder.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        folder.maxLines = 1
        folder.setOnLongClickListener { showFolderActions(store.activeFolder); true }
        folderRow.addView(folder, portStyle.matchWrap())
    }

    private fun showShelfMenu(anchor: View) {
        val actions = listOf(
            R.string.bookshelf_files to { selectImages.launch(arrayOf("image/*")) },
            R.string.bookshelf_paste to {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
                if (uris.isEmpty()) Toast.makeText(this, R.string.bookshelf_clipboard_empty, Toast.LENGTH_SHORT).show()
                else importImages(uris)
            },
            R.string.new_bookshelf_folder to { promptChildFolder("") },
            R.string.port_shelf_actions to { showFolderActions(store.activeFolder) },
            R.string.bookshelf_fullscreen to { toggleFullscreen() },
            R.string.bookshelf_export_pdf to { requestPdfExport() },
            R.string.port_shelf_storage to {
                startActivity(android.content.Intent(this, BookshelfStorageActivity::class.java))
            },
        )
        androidx.appcompat.widget.PopupMenu(this, anchor).apply {
            actions.forEachIndexed { index, item -> menu.add(0, index, index, item.first) }
            setOnMenuItemClickListener { item -> actions[item.itemId].second(); true }
        }.show()
    }
    private fun renderGallery() {
        val scroll = gallery.parent as? ScrollView
        val previousY = scroll?.scrollY ?: 0
        val generation = ++galleryGeneration
        galleryExecutor.shutdownNow()
        galleryExecutor = Executors.newFixedThreadPool(2)
        recycleBitmaps()
        gallery.removeAllViews()
        val totalWidth = gallery.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels - dp(24)
        val columnCount = store.columnCount()
        val columnWidths = IntArray(columnCount) { column ->
            (totalWidth / columnCount + if (column < totalWidth % columnCount) 1 else 0).coerceAtLeast(1)
        }
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        val galleryColumns = List(columnCount) { index ->
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { column ->
                columns.addView(column, LinearLayout.LayoutParams(columnWidths[index], ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val columnHeights = IntArray(columnCount)
        store.visibleItems()
            .forEach { item ->
                val targetColumn = columnHeights.indices.minByOrNull { columnHeights[it] } ?: 0
                val columnWidth = columnWidths[targetColumn]
                val targetHeight = (columnWidth * item.height.toFloat() / item.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
                val image = ImageView(this).apply {
                    background = null
                    clipToOutline = false
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = item.sourceUrl
                    setOnLongClickListener {
                        confirmDeleteImage(item)
                        true
                    }
                }
                val params = LinearLayout.LayoutParams(columnWidth, targetHeight)
                galleryColumns[targetColumn].addView(image, params)
                columnHeights[targetColumn] += targetHeight
                galleryExecutor.execute {
                    val bitmap = decodeScaled(item.filePath, columnWidth) ?: return@execute
                    runOnUiThread {
                        if (generation != galleryGeneration || isFinishing || image.parent == null) {
                            bitmap.recycle()
                        } else {
                            loadedBitmaps += bitmap
                            image.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        gallery.addView(columns, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gallery.post { if (generation == galleryGeneration) scroll?.scrollTo(0, previousY) }
    }



    private fun showFolderActions(folder: String) {
        AlertDialog.Builder(this).setTitle("책장/$folder")
            .setItems(arrayOf("하위 폴더 만들기", "이름 변경", "다른 폴더로 이동", "위로 이동", "아래로 이동", "삭제")) { _, which ->
                when (which) {
                    0 -> promptChildFolder(folder)
                    1 -> promptRenameFolder(folder)
                    2 -> showFolderTreeDialog(moving = folder)
                    3 -> { store.moveFolder(folder, -1); refreshShelfFolders() }
                    4 -> { store.moveFolder(folder, 1); refreshShelfFolders() }
                    5 -> if (store.folders.any { it.startsWith("$folder/", true) })
                        Toast.makeText(this, "하위 폴더를 먼저 이동하거나 삭제해 주세요.", Toast.LENGTH_LONG).show()
                    else confirmDeleteFolder(folder)
                }
            }.show()
    }

    private fun showFolderTreeDialog(moving: String? = null) {
        val displayFolders = mutableListOf<String>()
        val seenFolders = mutableSetOf<String>()
        for (folder in store.folders) {
            val ancestors = mutableListOf<String>()
            var p = folder
            while (p.isNotEmpty()) {
                ancestors += p
                p = BookshelfPath.parent(p)
            }
            ancestors.reverse()
            for (path in ancestors) {
                if (seenFolders.add(path)) displayFolders += path
            }
        }
        if (expandedTreeFolders.isEmpty()) {
            var curr = store.activeFolder
            while (curr.isNotEmpty()) {
                expandedTreeFolders += curr
                curr = BookshelfPath.parent(curr)
            }
        }

        var dialog: AlertDialog? = null
        val context = this
        val onSurfaceColor = androidx.core.content.ContextCompat.getColor(context, R.color.browser_on_surface)
        val overviewColor = androidx.core.content.ContextCompat.getColor(context, R.color.browser_overview)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(overviewColor)
        }

        val headerRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        val titleView = TextView(context).apply {
            text = if (moving != null) "폴더 이동 위치 선택" else "책장 폴더 트리"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurfaceColor)
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (moving == null) {
            val addRootBtn = Button(context).apply {
                text = "＋ 새 폴더"
                textSize = 12f
                isAllCaps = false
                minHeight = 0
                minWidth = 0
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = getDrawable(R.drawable.tab_overview_pill_background)
                setTextColor(onSurfaceColor)
                setOnClickListener {
                    dialog?.dismiss()
                    promptChildFolder("")
                }
            }
            headerRow.addView(addRootBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val moveRootBtn = Button(context).apply {
                text = "최상위로 이동"
                textSize = 12f
                isAllCaps = false
                minHeight = 0
                minWidth = 0
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = getDrawable(R.drawable.tab_overview_pill_background)
                setTextColor(onSurfaceColor)
                setOnClickListener {
                    val leaf = BookshelfPath.leaf(moving)
                    if (!store.renameFolder(moving, leaf)) {
                        Toast.makeText(context, "같은 이름의 폴더가 있거나 이동할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                    refreshShelfFolders()
                    dialog?.dismiss()
                }
            }
            headerRow.addView(moveRootBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        container.addView(headerRow)

        val treeContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(context).apply {
            clipToPadding = false
            addView(treeContent, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        container.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))

        fun rebuildTree() {
            treeContent.removeAllViews()
            val available = displayFolders.filter { path ->
                moving == null || (!path.equals(moving, ignoreCase = true) && !path.startsWith("$moving/", ignoreCase = true))
            }

            fun isVisible(path: String): Boolean {
                var p = BookshelfPath.parent(path)
                while (p.isNotEmpty()) {
                    if (p !in expandedTreeFolders) return false
                    p = BookshelfPath.parent(p)
                }
                return true
            }

            for (path in available) {
                if (!isVisible(path)) continue
                val level = path.count { it == '/' }
                val leaf = BookshelfPath.leaf(path)
                val children = available.filter { BookshelfPath.parent(it).equals(path, ignoreCase = true) }
                val hasChildren = children.isNotEmpty()
                val isExpanded = path in expandedTreeFolders
                val isActive = path.equals(store.activeFolder, ignoreCase = true)
                val count = store.visibleItems(path).size

                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(8 + level * 20), dp(8), dp(8), dp(8))
                    background = getDrawable(
                        if (isActive && moving == null) R.drawable.tab_overview_card_active_background
                        else R.drawable.tab_overview_pill_background
                    )
                }
                val rowParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }

                val chevronView = TextView(context).apply {
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(onSurfaceColor)
                    text = if (hasChildren) (if (isExpanded) "⌵" else "›") else "  "
                    if (hasChildren) {
                        setOnClickListener {
                            if (isExpanded) expandedTreeFolders.remove(path) else expandedTreeFolders.add(path)
                            rebuildTree()
                        }
                    }
                }
                row.addView(chevronView, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))

                val nameView = TextView(context).apply {
                    text = "📁 $leaf"
                    textSize = 14f
                    typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(onSurfaceColor)
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4)
                })

                val countView = TextView(context).apply {
                    text = "($count)"
                    textSize = 12f
                    setTextColor(Color.GRAY)
                    setPadding(dp(4), 0, dp(4), 0)
                }
                row.addView(countView)

                if (moving != null) {
                    val moveHereBtn = Button(context).apply {
                        text = "이동"
                        textSize = 12f
                        isAllCaps = false
                        minHeight = 0
                        minWidth = 0
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = getDrawable(R.drawable.tab_overview_card_active_background)
                        setTextColor(onSurfaceColor)
                        setOnClickListener {
                            val leafMoving = BookshelfPath.leaf(moving)
                            val target = "$path/$leafMoving"
                            if (!store.renameFolder(moving, target)) {
                                Toast.makeText(context, "같은 이름의 폴더가 있거나 이동할 수 없는 위치입니다.", Toast.LENGTH_SHORT).show()
                            }
                            refreshShelfFolders()
                            dialog?.dismiss()
                        }
                    }
                    row.addView(moveHereBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                } else {
                    val moreBtn = TextView(context).apply {
                        text = "⋮"
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setTextColor(onSurfaceColor)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener {
                            dialog?.dismiss()
                            showFolderActions(path)
                        }
                    }
                    row.addView(moreBtn)

                    row.setOnClickListener {
                        if (path !in store.folders) store.addFolder(path) else store.selectFolder(path)
                        refreshShelfFolders()
                        dialog?.dismiss()
                    }
                    row.setOnLongClickListener {
                        dialog?.dismiss()
                        showFolderActions(path)
                        true
                    }
                }

                treeContent.addView(row, rowParams)
            }
        }

        rebuildTree()

        dialog = AlertDialog.Builder(context)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun chooseShelfParent(moving: String? = null, parent: String = "") {
        showFolderTreeDialog(moving)
    }

    private fun refreshShelfFolders() { renderFolders(); updateColumnControls(); renderGallery() }

    private fun promptChildFolder(parent: String) {
        val input = EditText(this).apply { hint = "폴더 이름"; isSingleLine = true }
        val dialog = AlertDialog.Builder(this).setTitle("책장/$parent · 새 폴더").setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton("만들기", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val leaf = input.text.toString().trim()
            val path = if (parent.isEmpty()) leaf else "$parent/$leaf"
            if (leaf.contains('/') || BookshelfPath.normalize(path) == null) input.error = "사용할 수 없는 폴더 이름입니다."
            else { store.addFolder(path); refreshShelfFolders(); dialog.dismiss() }
        } }
        dialog.show()
    }

    private fun promptRenameFolder(folder: String) {
        val input = EditText(this).apply {
            setText(BookshelfPath.leaf(folder))
            selectAll()
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_rename_folder)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (!store.renameFolder(folder, BookshelfPath.parent(folder).let { if (it.isEmpty()) input.text.toString() else "$it/${input.text}" }))
                    Toast.makeText(this, R.string.bookshelf_folder_name_invalid, Toast.LENGTH_SHORT).show()
                renderFolders()
                updateColumnControls()
                renderGallery()
            }
            .show()
    }

    private fun confirmDeleteFolder(folder: String) {
        val count = store.items.count { it.folder.equals(folder, ignoreCase = true) }
        AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_delete_folder)
            .setMessage(getString(R.string.bookshelf_delete_folder_message, folder, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.deleteFolder(folder)
                renderFolders()
                updateColumnControls()
                renderGallery()
            }
            .show()
    }

    private fun importImages(uris: List<Uri>) {
        if (importingImages) return
        importingImages = true
        val folder = store.activeFolder
        Toast.makeText(this, R.string.bookshelf_adding_images, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
            var added = 0
            for (uri in uris) {
                try {
                    val item = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.prepareImport(uri, folder) }
                    store.commitImport(item)
                    added++
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(this@BookshelfActivity, error.localizedMessage, Toast.LENGTH_LONG).show()
                }
            }
            renderFolders()
            renderGallery()
            Toast.makeText(this@BookshelfActivity, getString(R.string.bookshelf_images_added, added), Toast.LENGTH_SHORT).show()
            } finally { importingImages = false }
        }
    }

    private fun confirmDeleteImage(item: BookshelfItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_delete_image)
            .setMessage(R.string.bookshelf_delete_image_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                recycleBitmaps()
                store.removeItem(item)
                renderFolders()
                renderGallery()
            }
            .show()
    }

    private fun requestPdfExport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_pdf_orientation_title)
            .setItems(
                arrayOf(
                    getString(R.string.bookshelf_pdf_portrait),
                    getString(R.string.bookshelf_pdf_landscape),
                ),
            ) { _, selected ->
                pendingPdfOrientation = if (selected == 0) {
                    PdfPageOrientation.PORTRAIT
                } else {
                    PdfPageOrientation.LANDSCAPE
                }
                launchPdfDestination()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchPdfDestination() {
        val safeFolder = store.activeFolder
            .map { character -> if (character in "\\/:*?\"<>|") '_' else character }
            .joinToString("")
            .trim()
            .ifEmpty { "bookshelf" }
        createPdfDocument.launch("Umtomi-$safeFolder.pdf")
    }

    private data class PdfPlacement(
        val item: BookshelfItem,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private fun exportCurrentFolderPdf(uri: Uri) {
        val folder = store.activeFolder
        val columns = store.columnCount(folder)
        val orientation = pendingPdfOrientation
        val items = store.visibleItems(folder).filter { File(it.filePath).exists() }
        Toast.makeText(this, R.string.bookshelf_pdf_creating, Toast.LENGTH_SHORT).show()
        pdfExecutor.execute {
            val result = runCatching { writePdf(uri, folder, columns, items, orientation) }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result.isSuccess) R.string.bookshelf_pdf_saved else R.string.bookshelf_pdf_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun writePdf(
        uri: Uri,
        folder: String,
        columnCount: Int,
        items: List<BookshelfItem>,
        orientation: PdfPageOrientation,
    ) {
        val pageWidth = if (orientation == PdfPageOrientation.PORTRAIT) 1080 else 1920
        val pageHeight = if (orientation == PdfPageOrientation.PORTRAIT) 1920 else 1080
        val margin = 0
        val contentTop = 0
        val contentBottom = 0
        val gap = 0
        val contentWidth = pageWidth - margin * 2
        val columnWidth = (contentWidth - gap * (columnCount - 1)) / columnCount
        val heights = IntArray(columnCount)
        val placements = items.map { item ->
            val targetColumn = heights.indices.minByOrNull { heights[it] } ?: 0
            val height = (columnWidth * item.height.toFloat() / item.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
            PdfPlacement(
                item,
                margin + targetColumn * (columnWidth + gap),
                heights[targetColumn],
                columnWidth,
                height,
            ).also { heights[targetColumn] += height + gap }
        }
        val contentHeight = pageHeight - contentTop - contentBottom
        val galleryHeight = (heights.maxOrNull() ?: 0).minus(gap).coerceAtLeast(0)
        val pageCount = ceil(galleryHeight / contentHeight.toDouble()).toInt().coerceAtLeast(1)
        val document = PdfDocument()
        try {
            repeat(pageCount) { pageIndex ->
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val pageStart = pageIndex * contentHeight
                val pageEnd = pageStart + contentHeight
                val clipSave = canvas.save()
                canvas.clipRect(margin, contentTop, pageWidth - margin, pageHeight - contentBottom)
                placements.forEach { placement ->
                    if (placement.y + placement.height <= pageStart || placement.y >= pageEnd) return@forEach
                    val bitmap = decodeScaled(placement.item.filePath, placement.width) ?: return@forEach
                    try {
                        val target = RectF(
                            placement.x.toFloat(),
                            (contentTop + placement.y - pageStart).toFloat(),
                            (placement.x + placement.width).toFloat(),
                            (contentTop + placement.y - pageStart + placement.height).toFloat(),
                        )
                        canvas.drawBitmap(bitmap, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    } finally {
                        bitmap.recycle()
                    }
                }
                canvas.restoreToCount(clipSave)
                document.finishPage(page)
            }
            contentResolver.openOutputStream(uri, "w")?.use(document::writeTo)
                ?: error("Unable to open PDF destination")
        } finally {
            document.close()
        }
    }

    private fun decodeScaled(path: String, targetWidth: Int): Bitmap? {
        if (CloudShelfMarker.read(File(path)) != null)
            return runCatching { CloudShelfMarker.decode(this, File(path), targetWidth, ::decodeLocalScaled) }.getOrNull()
        return decodeLocalScaled(path, targetWidth)
    }

    private fun decodeLocalScaled(path: String, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > targetWidth * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { androidx.exifinterface.media.ExifInterface(path).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> { setRotate(180f); postScale(-1f, 1f) }
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun recycleBitmaps() {
        loadedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        loadedBitmaps.clear()
    }

    private fun columnButton(label: String, action: () -> Unit): Button = Button(this).apply {
        background = getDrawable(R.drawable.tab_overview_pill_background)
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        text = label
        textSize = 18f
        setTextColor(androidx.core.content.ContextCompat.getColor(this@BookshelfActivity, R.color.browser_on_surface))
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, description: String, action: () -> Unit): Button = Button(this).apply {
        background = getDrawable(R.drawable.tab_overview_pill_background)
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        text = label
        contentDescription = description
        textSize = 13f
        setTextColor(androidx.core.content.ContextCompat.getColor(this@BookshelfActivity, R.color.browser_on_surface))
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    private fun changeColumnCount(delta: Int) {
        val updated = (store.columnCount() + delta)
            .coerceIn(BookshelfStore.MIN_COLUMN_COUNT, BookshelfStore.MAX_COLUMN_COUNT)
        if (updated == store.columnCount()) return
        store.setColumnCount(updated)
        updateColumnControls()
        renderGallery()
    }

    private fun updateColumnControls() {
        val count = store.columnCount()
        columnValue.text = getString(R.string.bookshelf_column_value, count)
        decreaseColumns.isEnabled = count > BookshelfStore.MIN_COLUMN_COUNT
        increaseColumns.isEnabled = count < BookshelfStore.MAX_COLUMN_COUNT
        decreaseColumns.alpha = if (decreaseColumns.isEnabled) 1f else 0.4f
        increaseColumns.alpha = if (increaseColumns.isEnabled) 1f else 0.4f
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

