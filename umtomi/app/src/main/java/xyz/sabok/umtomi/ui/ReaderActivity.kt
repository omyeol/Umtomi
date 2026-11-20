/*
 *     Umtomi, Hitomi.la viewer for Android
 *     Copyright (C) 2019  tom5079
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package xyz.sabok.umtomi.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.view.animation.Animation
import android.view.animation.AnticipateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.TranslateAnimation
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.material.snackbar.Snackbar
import xyz.sabok.umtomi.util.LocalDiagnostics
import com.google.mlkit.vision.face.Face
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.adapters.ReaderAdapter
import xyz.sabok.umtomi.databinding.NumberpickerDialogBinding
import xyz.sabok.umtomi.databinding.ReaderActivityBinding
import xyz.sabok.umtomi.favorites
import xyz.sabok.umtomi.services.DownloadService
import xyz.sabok.umtomi.util.LastReadPages
import xyz.sabok.umtomi.util.Preferences
import xyz.sabok.umtomi.util.ReaderTabStore
import xyz.sabok.umtomi.util.camera
import xyz.sabok.umtomi.util.checkNotificationEnabled
import xyz.sabok.umtomi.util.closeCamera
import xyz.sabok.umtomi.util.downloader.Cache
import xyz.sabok.umtomi.util.downloader.DownloadManager
import xyz.sabok.umtomi.util.requestNotificationPermission
import xyz.sabok.umtomi.util.showNotificationPermissionExplanationDialog
import xyz.sabok.umtomi.util.startCamera

class ReaderActivity : BaseActivity() {

    private val regionCapture = ReaderCapture(this)
    private var galleryID = 0
    private var currentPage = 0
    private lateinit var tabs: ReaderTabStore
    private var chromeHidden = false
    private var seekingPage = false
    private var awaitingResumeDecision = false
    private var pendingResumeTarget: Int? = null
    private var resumePromptDialog: AlertDialog? = null

    private var isScroll = true
    private var isFullscreen = false

    private lateinit var cache: Cache
    var downloader: DownloadService? = null
    private fun aroundResumePrefetch() =
        Preferences["port_resume_prefetch", "around_resume_page"] != "from_beginning"

    private val conn = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            downloader = (service as DownloadService.Binder).service.also {
                it.priority = 0

                if (!it.progress.containsKey(galleryID)) {
                    val anchor = if (aroundResumePrefetch()) (pendingResumeTarget ?: LastReadPages.get(this@ReaderActivity, galleryID) ?: 0) else 0
                    DownloadService.download(this@ReaderActivity, galleryID, true, anchorPage = anchor)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloader = null
        }
    }

    private val snapHelper = PagerSnapHelper()
    private var menu: Menu? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted)
            toggleCamera()
        else
            AlertDialog.Builder(this)
                .setTitle(R.string.error)
                .setMessage(R.string.camera_denied)
                .setPositiveButton(android.R.string.ok) { _, _ ->}
                .show()
    }

    enum class Eye {
        LEFT,
        RIGHT
    }

    private var cameraEnabled = false
    private var eyeType: Eye? = null
    private var eyeTime: Long = 0L

    private lateinit var binding: ReaderActivityBinding

    private val requestNotificationPermssionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            showNotificationPermissionExplanationDialog(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = readerColor(R.color.reader_surface)
        window.navigationBarColor = readerColor(R.color.reader_surface)
        androidx.core.view.WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setSupportActionBar(binding.readerToolbar)
        binding.readerToolbar.setNavigationOnClickListener { finish() }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        title = getString(R.string.reader_loading)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tabs = ReaderTabStore(this)
        handleIntent(intent)
        if (galleryID == 0) galleryID = tabs.selectedID
        if (galleryID > 0) tabs.open(galleryID)
        cache = Cache.getInstance(this, galleryID)
        LocalDiagnostics.setCustomKey("GalleryID", galleryID)

        if (galleryID == 0) {
            onBackPressed()
            return
        }

        initDownloadListener()
        initView()
        openTab(galleryID)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val previousID = galleryID
        handleIntent(intent)
        val requestedID = galleryID
        galleryID = previousID
        if (requestedID > 0) openTab(requestedID)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            val lastPathSegment = uri?.lastPathSegment
            if (uri != null && lastPathSegment != null) {
                galleryID = if (uri.host?.endsWith("hasha.in") == true) {
                    lastPathSegment?.toInt() ?: 0
                } else when (uri.host) {
                    "hitomi.la" ->
                        Regex("([0-9]+).html").find(lastPathSegment)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    "e-hentai.org" -> uri.pathSegments[1].toInt()
                    else -> 0
                }
            }
        } else {
            galleryID = intent.getIntExtra("galleryID", 0)
        }
    }

    private fun openTab(id: Int) {
        if (id <= 0) return
        resumePromptDialog?.dismiss()
        resumePromptDialog = null
        awaitingResumeDecision = false
        pendingResumeTarget = null

        val wasAlreadyOpen = tabs.all().any { it.galleryID == id }
        val openTabPage = tabs.page(id)
        val persistedLastPage = LastReadPages.get(this, id)
        val shouldPromptResume = !wasAlreadyOpen && persistedLastPage != null && persistedLastPage > 0
        val savedPage = if (wasAlreadyOpen) openTabPage else 0

        if (id != galleryID) {
            tabs.setPage(galleryID, (currentPage - 1).coerceAtLeast(0))
            snapHelper.attachToRecyclerView(null)
            galleryID = id
            currentPage = 0
            cache = Cache.getInstance(this, id)
            title = getString(R.string.reader_loading)
            binding.downloadProgressbar.visibility = View.VISIBLE
            binding.recyclerview.adapter = readerAdapter(id)
            binding.recyclerview.layoutManager = LinearLayoutManager(this)
            isScroll = true
            binding.recyclerview.continuousZoom = true
            isFullscreen = false
            animateDownloadFAB(DownloadManager.getInstance(this).getDownloadFolder(id) != null)
            menu?.findItem(R.id.reader_menu_favorite)?.icon = AnimatedVectorDrawableCompat.create(this, R.drawable.avd_star)
            LocalDiagnostics.setCustomKey("GalleryID", id)
            val anchor = if (aroundResumePrefetch()) (persistedLastPage ?: openTabPage) else 0
            DownloadService.download(this, id, true, anchorPage = anchor)
        }
        tabs.open(id)
        val info = cache.metadata.galleryInfo
        if (info != null) {
            (binding.recyclerview.adapter as ReaderAdapter).galleryInfo = info
            binding.recyclerview.adapter?.notifyDataSetChanged()
            title = info.title
        }
        currentPage = savedPage + 1
        isScroll = !Preferences["port_reader_paged", false]
        scrollMode(isScroll)
        (binding.recyclerview.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(currentPage - 1, 0)
        fullscreen(false)
        chromeHidden = false
        showChrome()
        showTabStrip()
        invalidateOptionsMenu()
        updatePageControls()

        if (shouldPromptResume && persistedLastPage != null) {
            promptResume(id, persistedLastPage)
        }
    }

    private fun promptResume(id: Int, savedPage: Int) {
        awaitingResumeDecision = true
        resumePromptDialog = AlertDialog.Builder(this)
            .setTitle(R.string.port_resume_title)
            .setMessage(getString(R.string.port_resume_message, savedPage + 1))
            .setCancelable(false)
            .setNegativeButton(R.string.port_no) { _, _ ->
                awaitingResumeDecision = false
                pendingResumeTarget = null
                LastReadPages.clear(this, id)
                tabs.setPage(id, 0)
                if (galleryID == id) {
                    currentPage = 1
                    jumpToPage(0)
                    updatePageControls()
                }
            }
            .setPositiveButton(R.string.port_yes) { _, _ ->
                awaitingResumeDecision = false
                if (galleryID != id) return@setPositiveButton
                val count = binding.recyclerview.adapter?.itemCount ?: 0
                if (count > savedPage) {
                    currentPage = savedPage + 1
                    tabs.setPage(id, savedPage)
                    jumpToPage(savedPage)
                    updatePageControls()
                } else {
                    pendingResumeTarget = savedPage
                }
                if (aroundResumePrefetch()) {
                    DownloadService.reprioritize(this, id, savedPage)
                }
            }
            .show()
    }

    private fun readerAdapter(id: Int) = ReaderAdapter(this, id).apply {
        onItemClickListener = { toggleChrome() }
        onItemLongClickListener = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun readerColor(id: Int) = ContextCompat.getColor(this, id)

    private fun showTabStrip() {
        binding.readerTabs.removeAllViews()
        var selectedChip: View? = null
        tabs.all().forEach { tab ->
            val label = Cache.getInstance(this, tab.galleryID).metadata.galleryInfo?.title ?: "#${tab.galleryID}"
            val selected = tab.galleryID == galleryID
            val chip = Chip(this).apply {
                text = label
                setTextColor(readerColor(if (selected) R.color.reader_text else R.color.reader_muted))
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxWidth = dp(248)
                ellipsize = TextUtils.TruncateAt.END
                setSingleLine(true)
                chipBackgroundColor = ColorStateList.valueOf(readerColor(if (selected) R.color.reader_selected else R.color.reader_raised))
                chipStrokeColor = ColorStateList.valueOf(readerColor(if (selected) R.color.reader_outline else R.color.reader_raised))
                chipStrokeWidth = dp(1).toFloat()
                chipCornerRadius = dp(14).toFloat()
                chipMinHeight = dp(40).toFloat()
                chipStartPadding = dp(12).toFloat()
                textEndPadding = dp(8).toFloat()
                closeIcon = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.reader_close)
                closeIconTint = ColorStateList.valueOf(readerColor(if (selected) R.color.reader_text else R.color.reader_muted))
                closeIconSize = dp(18).toFloat()
                closeIconEndPadding = dp(10).toFloat()
                isCloseIconVisible = true
                closeIconContentDescription = getString(R.string.reader_close_tab, label)
                isSelected = selected
                ensureAccessibleTouchTarget(dp(48))
                setOnClickListener { openTab(tab.galleryID) }
                setOnCloseIconClickListener { closeTab(tab.galleryID) }
            }
            binding.readerTabs.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
            if (selected) selectedChip = chip
        }
        binding.readerTabs.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            icon = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.reader_add)
            iconTint = ColorStateList.valueOf(readerColor(R.color.reader_accent))
            iconPadding = 0
            insetTop = 0; insetBottom = 0
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minWidth = dp(48)
            cornerRadius = dp(14)
            contentDescription = getString(R.string.reader_new_tab)
            setOnClickListener { promptNewTab() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        selectedChip?.let { chip -> binding.readerTabScroll.post { binding.readerTabScroll.smoothScrollTo((chip.left - dp(12)).coerceAtLeast(0), 0) } }
        binding.readerTabScroll.visibility = if (chromeHidden || isFullscreen) View.GONE else View.VISIBLE
    }

    private fun promptNewTab() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.reader_gallery_id)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        AlertDialog.Builder(this).setTitle(R.string.reader_new_tab).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> input.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let(::openTab) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun updatePageControls() {
        val count = binding.recyclerview.adapter?.itemCount ?: 0
        val page = currentPage.coerceIn(if (count > 0) 1 else 0, count)
        if (!seekingPage) {
            binding.readerPageSlider.max = (count - 1).coerceAtLeast(0)
            binding.readerPageSlider.progress = (page - 1).coerceAtLeast(0)
            binding.readerPageLabel.text = if (count > 0) "$page / $count" else "— / —"
        }
        binding.readerPageSlider.isEnabled = count > 1
        binding.readerPrevious.isEnabled = page > 1
        binding.readerNext.isEnabled = page < count
        binding.readerPrevious.alpha = if (page > 1) 1f else 0.3f
        binding.readerNext.alpha = if (page < count) 1f else 0.3f
    }

    private fun jumpToPage(index: Int) {
        val count = binding.recyclerview.adapter?.itemCount ?: 0
        if (count == 0) return
        (binding.recyclerview.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index.coerceIn(0, count - 1), 0)
    }

    private fun showReaderTools() {
        val dialog = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(readerColor(R.color.reader_surface))
                cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        content.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(readerColor(R.color.reader_muted)); cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20) })
        content.addView(TextView(this).apply {
            setText(R.string.reader_tools); textSize = 18f
            setTextColor(readerColor(R.color.reader_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, dp(12), dp(16))
        })
        listOf(
            Triple(binding.downloadFab.labelText, binding.downloadFab, R.drawable.ic_download),
            Triple(getString(R.string.reader_fab_retry), binding.retryFab, R.drawable.refresh),
            Triple(binding.autoFab.labelText, binding.autoFab, R.drawable.eye_white),
            Triple(getString(R.string.reader_fab_fullscreen), binding.fullscreenFab, R.drawable.ic_fullscreen)
        ).forEach { (label, target, iconID) ->
            content.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = label; isAllCaps = false; textSize = 16f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(readerColor(R.color.reader_text))
                icon = ContextCompat.getDrawable(this@ReaderActivity, iconID)
                iconTint = ColorStateList.valueOf(readerColor(R.color.reader_accent))
                iconPadding = dp(16)
                setOnClickListener { dialog.dismiss(); target.performClick() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        }
        dialog.setContentView(content)
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun closeTab(id: Int) {
        if (id == galleryID) tabs.setPage(id, (currentPage - 1).coerceAtLeast(0))
        val next = tabs.close(id)
        if (next == 0) {
            finish()
        } else if (id == galleryID) {
            galleryID = 0
            openTab(next)
        } else showTabStrip()
    }

    private fun toggleChrome() {
        chromeHidden = !chromeHidden
        if (chromeHidden) {
            supportActionBar?.hide()
            binding.readerTabScroll.visibility = View.GONE
            binding.readerDock.visibility = View.GONE
        } else {
            if (isFullscreen) { isFullscreen = false; fullscreen(false) }
            showChrome()
        }
    }

    private fun showChrome() {
        supportActionBar?.show()
        binding.readerTabScroll.visibility = View.VISIBLE
        binding.readerDock.visibility = View.VISIBLE
    }

    private fun toggleSinglePage() {
        if (!isScroll) { returnToContinuousReader(); return }
        isScroll = false
        isFullscreen = false
        chromeHidden = false
        scrollMode(false)
        fullscreen(false)
        showChrome()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)

        with(menu.findItem(R.id.reader_menu_favorite)) {
            if (favorites.contains(galleryID))
                (icon as Animatable).start()
        }

        menu.findItem(R.id.reader_menu_favorite).icon?.setTint(readerColor(R.color.reader_accent))
        menu.findItem(R.id.reader_menu_capture).icon?.setTint(readerColor(R.color.reader_accent))
        menu.findItem(R.id.reader_menu_tools).icon?.setTint(readerColor(R.color.reader_accent))
        this.menu = menu
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.reader_menu_capture -> regionCapture.start(binding.recyclerview, binding.scroller.width, binding.scroller.height, galleryID)
            R.id.reader_menu_tools -> showReaderTools()
            R.id.reader_menu_page_indicator -> {
                // TODO: Switch to DialogFragment
                val binding = NumberpickerDialogBinding.inflate(layoutInflater, binding.root, false)

                with(binding.numberPicker) {
                    minValue = 1
                    maxValue = (cache.metadata.galleryInfo?.files?.size ?: 1).coerceAtLeast(1)
                    value = currentPage.coerceIn(1, maxValue)
                }
                val dialog = AlertDialog.Builder(this).apply {
                    setView(binding.root)
                }.create()
                binding.okButton.setOnClickListener {
                    (this@ReaderActivity.binding.recyclerview.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(binding.numberPicker.value-1, 0)
                    dialog.dismiss()
                }

                dialog.show()
            }
            R.id.reader_menu_favorite -> {
                val id = galleryID
                val favorite = menu?.findItem(R.id.reader_menu_favorite) ?: return true

                if (favorites.contains(id)) {
                    favorites.remove(id)
                    favorite.icon = AnimatedVectorDrawableCompat.create(this, R.drawable.avd_star)?.apply { setTint(readerColor(R.color.reader_accent)) }
                } else {
                    favorites.add(id)
                    (favorite.icon as Animatable).start()
                }
            }
        }

        return true
    }

    override fun onResume() {
        super.onResume()

        bindService(Intent(this, DownloadService::class.java), conn, BIND_AUTO_CREATE)

        if (cameraEnabled)
            startCamera(this, cameraCallback)
    }

    override fun onPause() {
        super.onPause()
        closeCamera()

        if (downloader != null)
            unbindService(conn)

        downloader?.priority = galleryID
    }

    override fun onDestroy() {
        resumePromptDialog?.dismiss()
        resumePromptDialog = null
        super.onDestroy()

        update = false

        if (!DownloadManager.getInstance(this).isDownloading(galleryID))
            DownloadService.cancel(this, galleryID)
    }

    private fun returnToContinuousReader() {
        isScroll = true
        isFullscreen = false
        scrollMode(true)
        chromeHidden = false
        fullscreen(false)
        showChrome()
    }

    override fun onBackPressed() {
        if (!isScroll && !Preferences["port_reader_paged", false]) {
            returnToContinuousReader()
        } else if (isFullscreen || chromeHidden) {
            isFullscreen = false
            chromeHidden = false
            fullscreen(false)
            showChrome()
        } else super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!Preferences["reader_volume_navigation", false]) return super.onKeyDown(keyCode, event)
        //currentPage is 1-based
        return when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                jumpToPage(currentPage - 2)

                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                jumpToPage(currentPage)

                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (Preferences["reader_volume_navigation", false] && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    private var update = true
    private val failedGalleryIDs = mutableSetOf<Int>()
    private fun initDownloadListener() {
        CoroutineScope(Dispatchers.Main).launch {
            while (update) {
                delay(1000)

                val downloader = downloader ?: continue

                if (!downloader.progress.containsKey(galleryID))  //loading
                    continue

                if (downloader.progress[galleryID]?.isEmpty() == true) {      //Gallery not found
                    if (failedGalleryIDs.add(galleryID)) {
                        Snackbar
                            .make(binding.root, R.string.reader_failed_to_find_gallery, Snackbar.LENGTH_LONG)
                            .show()
                    }
                    continue
                }

                binding.downloadProgressbar.max = binding.recyclerview.adapter?.itemCount ?: 0
                binding.downloadProgressbar.progress =
                    downloader.progress[galleryID]?.count { it.isInfinite() } ?: 0

                if (title == getString(R.string.reader_loading)) {
                    val galleryInfo = cache.metadata.galleryInfo

                    if (galleryInfo != null) {
                        with(binding.recyclerview.adapter as ReaderAdapter) {
                            this.galleryInfo = galleryInfo
                            notifyDataSetChanged()
                        }

                        title = galleryInfo.title
                        pendingResumeTarget?.takeIf { it < galleryInfo.files.size }?.let { target ->
                            pendingResumeTarget = null
                            currentPage = target + 1
                            tabs.setPage(galleryID, target)
                            jumpToPage(target)
                        }
                        updatePageControls()
                        showTabStrip()
                        menu?.findItem(R.id.reader_menu_page_indicator)?.title =
                            "$currentPage/${galleryInfo.files.size}"

                        menu?.findItem(R.id.reader_type)?.icon = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.hitomi)
                    }
                }

                if (downloader.isCompleted(galleryID)) {   //Download finished
                    binding.downloadProgressbar.visibility = View.GONE

                    animateDownloadFAB(false)
                }
            }
        }
    }

    private fun initView() {
        binding.readerPrevious.setOnClickListener { jumpToPage(currentPage - 2) }
        binding.readerNext.setOnClickListener { jumpToPage(currentPage) }
        binding.readerSinglePage.setOnClickListener { toggleSinglePage() }
        binding.readerPageLabel.setOnClickListener {
            if ((binding.recyclerview.adapter?.itemCount ?: 0) > 0) menu?.findItem(R.id.reader_menu_page_indicator)?.let { onOptionsItemSelected(it) }
        }
        binding.readerPageSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { seekingPage = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.readerPageLabel.text = "${progress + 1} / ${binding.recyclerview.adapter?.itemCount ?: 0}"
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekingPage = false
                jumpToPage(seekBar.progress)
            }
        })
        with(binding.recyclerview) {
            adapter = readerAdapter(galleryID)

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)


                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager

                    if (layoutManager.findFirstVisibleItemPosition() == -1)
                        return
                    currentPage = layoutManager.findFirstVisibleItemPosition()+1
                    if (!awaitingResumeDecision && pendingResumeTarget == null) {
                        tabs.setPage(galleryID, currentPage - 1)
                        LastReadPages.set(this@ReaderActivity, galleryID, currentPage - 1)
                    }
                    updatePageControls()
                    menu?.findItem(R.id.reader_menu_page_indicator)?.title = "$currentPage/${recyclerView.adapter!!.itemCount}"
                }
            })
        }

        with(binding.downloadFab) {
            animateDownloadFAB(DownloadManager.getInstance(this@ReaderActivity).getDownloadFolder(galleryID) != null) //If download in progress, animate button

            setOnClickListener {
                requestNotificationPermission(
                    this@ReaderActivity,
                    requestNotificationPermssionLauncher
                ) {
                    val downloadManager = DownloadManager.getInstance(this@ReaderActivity)

                    if (downloadManager.isDownloading(galleryID)) {
                        downloadManager.deleteDownloadFolder(galleryID)
                        animateDownloadFAB(false)
                    } else {
                        downloadManager.addDownloadFolder(galleryID)
                        DownloadService.download(context, galleryID, true)
                        animateDownloadFAB(true)
                    }
                }
            }
        }

        with(binding.retryFab) {
            setImageResource(R.drawable.refresh)
            setOnClickListener {
                failedGalleryIDs.remove(galleryID)
                DownloadService.download(context, galleryID)
            }
        }

        with(binding.autoFab) {
            setImageResource(R.drawable.eye_white)
            setOnClickListener {
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                        toggleCamera()
                    }
                    Build.VERSION.SDK_INT >= 23 && shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                        AlertDialog.Builder(this@ReaderActivity)
                            .setTitle(R.string.warning)
                            .setMessage(R.string.camera_denied)
                            .setPositiveButton(android.R.string.ok) { _, _ ->}
                            .show()
                    }
                    else ->
                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        with(binding.fullscreenFab) {
            setImageResource(R.drawable.ic_fullscreen)
            setOnClickListener {
                isFullscreen = true
                fullscreen(isFullscreen)

                binding.fab.close(true)
            }
        }
    }

    private fun fullscreen(isFullscreen: Boolean) {
        if (isFullscreen) chromeHidden = true
        with(window.attributes) {
            if (isFullscreen) {
                flags = flags or WindowManager.LayoutParams.FLAG_FULLSCREEN
                supportActionBar?.hide()
                binding.readerTabScroll.visibility = View.GONE
                binding.readerDock.visibility = View.GONE
                binding.scroller.let {
                    it.handleWidth = resources.getDimensionPixelSize(R.dimen.thumb_height)
                    it.handleHeight = resources.getDimensionPixelSize(R.dimen.thumb_width)
                    it.handleDrawable = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.thumb_horizontal)
                    it.fastScrollDirection = RecyclerViewFastScroller.FastScrollDirection.HORIZONTAL
                }
            } else {
                flags = flags and WindowManager.LayoutParams.FLAG_FULLSCREEN.inv()
                if (!chromeHidden) {
                    supportActionBar?.show()
                    binding.readerTabScroll.visibility = View.VISIBLE
                    binding.readerDock.visibility = View.VISIBLE
                }
                binding.scroller.let {
                    it.handleWidth = resources.getDimensionPixelSize(R.dimen.thumb_width)
                    it.handleHeight = resources.getDimensionPixelSize(R.dimen.thumb_height)
                    it.handleDrawable = ContextCompat.getDrawable(this@ReaderActivity, R.drawable.thumb)
                    it.fastScrollDirection = RecyclerViewFastScroller.FastScrollDirection.VERTICAL
                }
            }

            window.attributes = this
        }

        binding.scroller.apply {
            handleWidth = resources.getDimensionPixelSize(if (isScroll) R.dimen.thumb_width else R.dimen.thumb_height)
            handleHeight = resources.getDimensionPixelSize(if (isScroll) R.dimen.thumb_height else R.dimen.thumb_width)
            handleDrawable = ContextCompat.getDrawable(this@ReaderActivity, if (isScroll) R.drawable.thumb else R.drawable.thumb_horizontal)
            fastScrollDirection = if (isScroll) RecyclerViewFastScroller.FastScrollDirection.VERTICAL else RecyclerViewFastScroller.FastScrollDirection.HORIZONTAL
        }
        binding.recyclerview.adapter = binding.recyclerview.adapter   // Force to redraw
    }

    private fun scrollMode(isScroll: Boolean) {
        binding.readerSinglePage.apply {
            visibility = if (Preferences["port_reader_paged", false]) View.GONE else View.VISIBLE
            text = if (isScroll) "한 컷" else "연속"
            contentDescription = if (isScroll) "한 컷 보기로 전환" else "연속 보기로 전환"
            isSelected = !isScroll
        }
        binding.recyclerview.continuousZoom = isScroll
        (binding.recyclerview.adapter as? ReaderAdapter)?.let { it.isFullScreen = !isScroll; it.notifyDataSetChanged() }
        if (isScroll) {
            snapHelper.attachToRecyclerView(null)
            binding.recyclerview.layoutManager = LinearLayoutManager(this)
        } else {
            snapHelper.attachToRecyclerView(binding.recyclerview)
            binding.recyclerview.layoutManager = object: LinearLayoutManager(this, HORIZONTAL, Preferences["rtl", false]) {
                override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
                    extraLayoutSpace.fill(600)
                }
            }
        }

        (binding.recyclerview.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(currentPage-1, 0)
    }

    private fun animateDownloadFAB(animate: Boolean) {
        binding.downloadFab.setImageResource(R.drawable.ic_download)
        binding.downloadFab.labelText = getString(if (animate) R.string.reader_fab_download_cancel else R.string.reader_fab_download)
    }

    val cameraCallback: (List<Face>) -> Unit = callback@{ faces ->
        binding.eyeCard.dot.let {
            it.visibility = View.VISIBLE
            CoroutineScope(Dispatchers.Main).launch {
                delay(50)
                it.visibility = View.INVISIBLE
            }
        }

        if (faces.size != 1)
            ContextCompat.getDrawable(this, R.drawable.eye_off).let {
                with(binding.eyeCard) {
                    leftEye.setImageDrawable(it)
                    rightEye.setImageDrawable(it)
                }

                return@callback
            }

        val (left, right) = Pair(
            faces[0].rightEyeOpenProbability?.let { it > 0.4 } == true,
            faces[0].leftEyeOpenProbability?.let { it > 0.4 } == true
        )

        with(binding.eyeCard) {
            leftEye.setImageDrawable(
                ContextCompat.getDrawable(
                    leftEye.context,
                    if (left) R.drawable.eye else R.drawable.eye_closed
                )
            )
            rightEye.setImageDrawable(
                ContextCompat.getDrawable(
                    rightEye.context,
                    if (right) R.drawable.eye else R.drawable.eye_closed
                )
            )
        }

        when {
            // Both closed / opened
            !left.xor(right) -> {
                eyeType = null
                eyeTime = 0L
            }
            !left -> {
                if (eyeType != Eye.LEFT) {
                    eyeType = Eye.LEFT
                    eyeTime = System.currentTimeMillis()
                }
            }
            !right -> {
                if (eyeType != Eye.RIGHT) {
                    eyeType = Eye.RIGHT
                    eyeTime = System.currentTimeMillis()
                }
            }
        }

        if (eyeType != null && System.currentTimeMillis() - eyeTime > 100) {
            (binding.recyclerview.layoutManager as LinearLayoutManager).let {
                it.scrollToPositionWithOffset(when(eyeType!!) {
                    Eye.RIGHT -> {
                        if (it.reverseLayout) currentPage - 2 else currentPage
                    }
                    Eye.LEFT -> {
                        if (it.reverseLayout) currentPage else currentPage - 2
                    }
                }, 0)
            }

            eyeTime = System.currentTimeMillis() + 500
        }
    }

    private fun toggleCamera() {
        val eyes = binding.eyeCard.root
        when (camera) {
            null -> {
                binding.autoFab.labelText = getString(R.string.reader_fab_auto_cancel)
                binding.autoFab.setImageResource(R.drawable.eye_off_white)
                eyes.apply {
                    visibility = View.VISIBLE
                    TranslateAnimation(0F, 0F, -100F, 0F).apply {
                        duration = 500
                        fillAfter = false
                        interpolator = OvershootInterpolator()
                    }.let { startAnimation(it) }
                }
                startCamera(this, cameraCallback)
                cameraEnabled = true
            }
            else -> {
                binding.autoFab.labelText = getString(R.string.reader_fab_auto)
                binding.autoFab.setImageResource(R.drawable.eye_white)
                eyes.apply {
                    TranslateAnimation(0F, 0F, 0F, -100F).apply {
                        duration = 500
                        fillAfter = false
                        interpolator = AnticipateInterpolator()
                        setAnimationListener(object: Animation.AnimationListener {
                            override fun onAnimationStart(p0: Animation?) {}
                            override fun onAnimationRepeat(p0: Animation?) {}

                            override fun onAnimationEnd(p0: Animation?) {
                                eyes.visibility = View.GONE
                            }
                        })
                    }.let { startAnimation(it) }
                }
                closeCamera()
                cameraEnabled = false
            }
        }
    }
}

