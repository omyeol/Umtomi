package xyz.sabok.umtomi.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.piasy.biv.view.BigImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import xyz.sabok.umtomi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Capture the visible reading viewport, including its current pan and zoom. */
internal class ReaderCapture(private val activity: ReaderActivity) : DefaultLifecycleObserver {
    private var pending: Bitmap? = null
    private var selector: MobileCaptureRegionDialog? = null
    private var destination: CapturePreviewDialog? = null
    private var shelfPicker: AlertDialog? = null
    private var busy = false
    private val saveFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = pending
        if (bitmap != null && uri != null) {
            save(bitmap) {
                activity.contentResolver.openOutputStream(uri, "wt")?.use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                } ?: error("파일을 열 수 없습니다")
                "이미지를 저장했습니다."
            }
        }
    }
    init { activity.lifecycle.addObserver(this) }

    fun start(view: ContinuousReaderView, width: Int, height: Int, galleryId: Int) {
        if (busy || selector?.isShowing == true || destination?.isShowing == true || pending != null) return
        if (width <= 0 || height <= 0 || view.adapter?.itemCount == 0) return
        try {
            val manager = view.layoutManager as LinearLayoutManager
            val originalPosition = manager.findFirstVisibleItemPosition()
            val originalTop = manager.findViewByPosition(originalPosition)?.top ?: 0
            var active = true
            var extension: Job? = null
            val bitmap = captureViewport(view, width, height)
            val extend: ((((Bitmap?, Int) -> Unit)) -> Unit)? = if (!view.continuousZoom) null else { done ->
                val beforePosition = manager.findFirstVisibleItemPosition()
                val beforeTop = manager.findViewByPosition(beforePosition)?.top ?: 0
                var moved = 0
                val listener = object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { moved += dy }
                }
                view.stopScroll()
                view.addOnScrollListener(listener)
                view.scrollBy(0, (height * .75f).toInt())
                view.removeOnScrollListener(listener)
                extension = activity.lifecycleScope.launch {
                    // Newly exposed tiles decode asynchronously. Never append a loading placeholder.
                    var ready = false
                    for (attempt in 0 until 80) {
                        delay(100)
                        if (!active) return@launch
                        ready = (0 until view.childCount).all { index ->
                            val child = view.getChildAt(index)
                            if (child.bottom <= 0 || child.top >= height) true else {
                                val image = child.findViewById<BigImageView>(R.id.image)?.mainView
                                if (image is SubsamplingScaleImageView) image.isReady
                                else image is com.facebook.drawee.view.SimpleDraweeView && image.controller != null
                            }
                        }
                        if (ready) break
                    }
                    if (!active) return@launch
                    if (!ready || moved !in 1..height || (view.parent as android.view.View).width != width) {
                        manager.scrollToPositionWithOffset(beforePosition, beforeTop)
                        done(null, 0)
                    } else {
                        try { done(captureViewport(view, width, height), moved) }
                        catch (error: Exception) { manager.scrollToPositionWithOffset(beforePosition, beforeTop); done(null, 0) }
                    }
                }
            }
            selector = MobileCaptureRegionDialog(activity, bitmap, emptyList(), "", false,
                onExtend = extend,
                onClosed = {
                    active = false
                    extension?.cancel()
                    manager.scrollToPositionWithOffset(originalPosition, originalTop)
                    selector = null
                }) { selection, _ -> chooseDestination(selection, galleryId) }
            selector?.show()
        } catch (error: Exception) { message("캡처하지 못했습니다. 다시 시도해 주세요.") }
    }
    private fun captureViewport(view: ContinuousReaderView, width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(android.graphics.Color.BLACK)
                translate(view.translationX, view.translationY)
                view.draw(this)
            }
        }
    }
    private fun chooseDestination(bitmap: Bitmap, galleryId: Int) {
        pending = bitmap
        var folder = BookshelfStore(activity).activeFolder
        destination = CapturePreviewDialog(activity, bitmap, folder,
            onFile = { saveFile.launch("Umtomi-capture-${System.currentTimeMillis()}.png") },
            onFolder = { update ->
                val current = BookshelfStore(activity)
                if (current.loadError != null) message("책장을 읽지 못했습니다.")
                else shelfPicker = AlertDialog.Builder(activity).setTitle("저장할 책장")
                    .setSingleChoiceItems(current.folders.toTypedArray(), current.folders.indexOf(folder)) { dialog, index ->
                        folder = current.folders[index]
                        update(folder)
                        dialog.dismiss()
                    }.setPositiveButton("새 책장 만들기") { _, _ ->
                        createShelf { created -> folder = created; update(created) }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            },
            onShelf = {
                save(bitmap) {
                    val current = BookshelfStore(activity)
                    check(current.loadError == null && folder in current.folders) { "책장을 다시 선택해 주세요." }
                    current.addImage(bitmap, "https://hitomi.la/galleries/$galleryId.html", folder)
                    "‘$folder’ 책장에 추가했습니다."
                }
            },
            onClosed = { if (!busy) discard(); destination = null })
        destination?.show()
    }
    private fun createShelf(onSelected: (String) -> Unit) {
        val input = android.widget.EditText(activity).apply {
            hint = "책장 이름"
            contentDescription = "새 책장 이름"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            doAfterTextChanged { error = null }
        }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("새 책장 만들기")
            .setView(container).setPositiveButton("만들기", null)
            .setNegativeButton(android.R.string.cancel, null).create()
        shelfPicker = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) { input.error = "책장 이름을 입력해 주세요."; return@setOnClickListener }
                runCatching {
                    val store = BookshelfStore(activity)
                    check(store.loadError == null) { "책장을 읽지 못했습니다." }
                    // Use the store's normalization and duplicate-name behavior.
                    store.addFolder(name)
                }.onSuccess { folder ->
                    onSelected(folder)
                    dialog.dismiss()
                }.onFailure { input.error = "책장을 만들지 못했습니다. 다시 시도해 주세요." }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }
    private fun save(bitmap: Bitmap, action: () -> String) {
        if (busy) return
        busy = true
        destination?.setSaving(true)
        activity.lifecycleScope.launch {
            try {
                message(withContext(Dispatchers.IO) { action() })
                destination?.dismiss()
                discard()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message("저장하지 못했습니다. 저장 공간과 책장을 확인한 후 다시 시도해 주세요.")
            } finally {
                busy = false
                destination?.setSaving(false)
                if (activity.isDestroyed) discard()
            }
        }
    }
    private fun discard() { pending?.recycle(); pending = null }
    private fun message(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    override fun onDestroy(owner: LifecycleOwner) {
        selector?.dismiss(); shelfPicker?.dismiss(); destination?.dismiss(); if (!busy) discard()
    }
}
