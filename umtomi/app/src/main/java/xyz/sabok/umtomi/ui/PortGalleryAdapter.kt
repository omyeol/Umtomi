package xyz.sabok.umtomi.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.util.downloader.Cache

internal class PortGalleryAdapter(
    private val activity: MainActivity,
    private val style: PortStyle,
    private val scope: CoroutineScope,
    private val card: Boolean,
    private val open: (Int) -> Unit,
    private val actions: (Int) -> Unit,
) : ListAdapter<Int, PortGalleryAdapter.Holder>(object : DiffUtil.ItemCallback<Int>() {
    override fun areItemsTheSame(oldItem: Int, newItem: Int) = oldItem == newItem
    override fun areContentsTheSame(oldItem: Int, newItem: Int) = oldItem == newItem
}) {
    inner class Holder(private val wrapper: LinearLayout) : RecyclerView.ViewHolder(wrapper) {
        private val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = style.rounded(style.background, 14)
            clipToOutline = true
            setPadding(style.dp(4), style.dp(4), style.dp(4), style.dp(4))
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = style.text("", 16f, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val subtitle = style.text("", 14f, style.muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val metadata = style.text("", 12f, style.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private var job: Job? = null

        init {
            val inset = style.dp(if (card) 10 else 12)
            wrapper.orientation = if (card) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            wrapper.gravity = if (card) Gravity.TOP else Gravity.CENTER_VERTICAL
            wrapper.setPadding(inset, inset, inset, inset)
            wrapper.background = style.ripple(style.surface, if (card) 18 else 12)
            wrapper.isFocusable = true
            wrapper.addView(image, LinearLayout.LayoutParams(
                if (card) -1 else style.dp(66),
                style.dp(if (card) 222 else 90)
            ))
            wrapper.addView(style.stack().apply {
                setPadding(if (card) 0 else style.dp(12), if (card) style.dp(9) else 0, 0, 0)
                addView(title)
                addView(subtitle)
                addView(metadata, style.matchWrap().apply { topMargin = style.dp(4) })
            }, if (card) style.matchWrap() else LinearLayout.LayoutParams(0, -2, 1f))
        }

        fun bind(id: Int) {
            clear()
            title.text = activity.getString(R.string.port_gallery_id, id)
            subtitle.text = ""
            metadata.text = ""
            wrapper.setOnClickListener { open(id) }
            wrapper.setOnLongClickListener { actions(id); true }
            job = scope.launch {
                val cache = try {
                    withContext(Dispatchers.IO) { Cache.getInstance(activity, id) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@launch
                }
                val info = cache.getGalleryInfo()
                if (info != null) {
                    title.text = info.title
                    subtitle.text = info.artists.orEmpty().joinToString { it.artist }
                        .ifBlank { info.parodys.orEmpty().joinToString { it.parody } }
                        .ifBlank { info.groups.orEmpty().joinToString { it.group } }
                        .ifBlank { info.language.orEmpty() }
                    metadata.text = activity.getString(R.string.port_gallery_meta, info.type, info.files.size)
                }
                val cover = try {
                    loadPortCover(activity, cache.getThumbnail(), if (card) 420 else 160)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                image.setImageBitmap(cover)
            }
        }

        fun clear() {
            job?.cancel()
            image.setImageDrawable(null)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(style.stack().apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
            val margin = style.dp(if (card) 5 else 3)
            setMargins(margin, margin, margin, margin)
        }
    })

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    override fun onViewRecycled(holder: Holder) = holder.clear()
}

internal suspend fun loadPortCover(context: android.content.Context, uri: Uri, edge: Int) =
    withContext(Dispatchers.IO) {
        if (uri == Uri.EMPTY) return@withContext null
        runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val largerEdge = maxOf(options.outWidth, options.outHeight)
            options.inSampleSize = 1
            while (largerEdge / (options.inSampleSize * 2) >= edge) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }
