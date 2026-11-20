package xyz.sabok.umtomi.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.favorites
import xyz.sabok.umtomi.util.FavoriteFolders
import xyz.sabok.umtomi.util.downloader.Cache

/** Android port of the iOS FavoriteFolderEditorView. */
class PortFavoriteFolderEditorActivity : BaseActivity() {
    private val style by lazy { PortStyle(this) }
    private lateinit var nameInput: EditText
    private lateinit var ruleInput: EditText
    private val selectedIDs = linkedSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folderID = intent.getStringExtra("folder_id")
        val parentID = intent.getStringExtra("parent_id")
        val library = FavoriteFolders(this)
        val existing = folderID?.let { id -> library.folders.firstOrNull { it.id == id } }

        if (savedInstanceState != null) {
            selectedIDs += savedInstanceState.getIntArray("selected_ids")?.toList().orEmpty()
        } else {
            selectedIDs += existing?.manualGalleryIDs.orEmpty()
        }

        val root = style.stack().apply { setBackgroundColor(style.background) }
        val top = style.row().apply {
            setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8))
            addView(style.action(getString(android.R.string.cancel)) { finish() })
            addView(style.text(
                getString(if (existing == null) R.string.port_fav_new_folder else R.string.port_fav_edit_folder),
                19f,
                bold = true,
            ).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(style.action(getString(R.string.port_fav_save), prominent = true) {
                saveFolder(folderID, parentID)
            })
        }
        root.addView(top, style.matchWrap())

        val formHeader = style.stack().apply {
            setPadding(style.dp(16), style.dp(4), style.dp(16), style.dp(8))
        }
        formHeader.addView(style.text(getString(R.string.port_fav_folder_name), 13f, style.muted, bold = true).apply {
            setPadding(style.dp(4), style.dp(6), 0, style.dp(6))
        })
        nameInput = EditText(this).apply {
            hint = getString(R.string.port_fav_folder_name)
            setText(savedInstanceState?.getString("name") ?: existing?.name.orEmpty())
            setTextColor(style.foreground)
            setHintTextColor(style.muted)
            setSingleLine()
            textSize = 16f
            background = style.rounded(style.surface, 13)
            setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12))
        }
        formHeader.addView(nameInput, style.matchWrap())

        formHeader.addView(style.text(getString(R.string.workspace_favorite_rule), 13f, style.muted, bold = true).apply {
            setPadding(style.dp(4), style.dp(14), 0, style.dp(6))
        })
        ruleInput = EditText(this).apply {
            hint = getString(R.string.port_fav_auto_rule_hint)
            setText(savedInstanceState?.getString("rule") ?: existing?.autoRuleQuery.orEmpty())
            setTextColor(style.foreground)
            setHintTextColor(style.muted)
            minLines = 2
            maxLines = 4
            textSize = 15f
            background = style.rounded(style.surface, 13)
            setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12))
        }
        formHeader.addView(ruleInput, style.matchWrap())
        formHeader.addView(style.text(getString(R.string.port_fav_auto_rule_note), 12f, style.muted).apply {
            setPadding(style.dp(4), style.dp(6), style.dp(4), style.dp(4))
        })

        formHeader.addView(style.text(getString(R.string.port_fav_manual_section), 13f, style.muted, bold = true).apply {
            setPadding(style.dp(4), style.dp(12), 0, style.dp(4))
        })
        root.addView(formHeader, style.matchWrap())

        val favoriteIDs = synchronized(favorites) { favorites.toList().asReversed() }
        if (favoriteIDs.isEmpty()) {
            root.addView(style.text(getString(R.string.port_fav_manual_empty), 14f, style.muted).apply {
                setPadding(style.dp(20), style.dp(12), style.dp(20), style.dp(12))
            })
        } else {
            val list = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@PortFavoriteFolderEditorActivity)
                adapter = ManualFavoritesAdapter(favoriteIDs)
                itemAnimator = null
                setPadding(style.dp(16), 0, style.dp(16), style.dp(24))
                clipToPadding = false
            }
            root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        style.applySystemBars(this, root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("name", nameInput.text.toString())
        outState.putString("rule", ruleInput.text.toString())
        outState.putIntArray("selected_ids", selectedIDs.toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun saveFolder(folderID: String?, parentID: String?) {
        runCatching {
            FavoriteFolders(this).saveFolder(
                id = folderID,
                parentID = parentID,
                name = nameInput.text.toString(),
                autoRuleQuery = ruleInput.text.toString(),
                manualGalleryIDs = selectedIDs.toList(),
            )
        }.onSuccess {
            setResult(RESULT_OK)
            finish()
        }.onFailure { error ->
            Toast.makeText(this, error.localizedMessage ?: getString(R.string.error), Toast.LENGTH_LONG).show()
        }
    }

    private inner class ManualFavoritesAdapter(private val ids: List<Int>) :
        RecyclerView.Adapter<ManualFavoritesAdapter.Holder>() {

        inner class Holder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            private val image = ImageView(this@PortFavoriteFolderEditorActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = style.rounded(style.background, 10)
                clipToOutline = true
                setPadding(style.dp(2), style.dp(2), style.dp(2), style.dp(2))
            }
            private val title = style.text("", 15f, style.foreground, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            private val subtitle = style.text("", 13f, style.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            private val check = style.text("", 18f, style.accent, bold = true).apply {
                gravity = Gravity.CENTER
            }
            private var job: Job? = null

            init {
                row.gravity = Gravity.CENTER_VERTICAL
                row.background = style.ripple(style.surface, 12)
                row.setPadding(style.dp(10), style.dp(8), style.dp(12), style.dp(8))
                row.addView(image, LinearLayout.LayoutParams(style.dp(50), style.dp(68)))
                row.addView(style.stack().apply {
                    setPadding(style.dp(10), 0, style.dp(8), 0)
                    addView(title)
                    addView(subtitle, style.matchWrap().apply { topMargin = style.dp(2) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(check, LinearLayout.LayoutParams(style.dp(30), style.dp(30)))
            }

            fun bind(id: Int) {
                clear()
                title.text = getString(R.string.port_gallery_id, id)
                subtitle.text = ""
                updateCheck(id)
                row.setOnClickListener {
                    if (id in selectedIDs) selectedIDs.remove(id) else selectedIDs.add(id)
                    updateCheck(id)
                }
                job = lifecycleScope.launch {
                    val cache = try {
                        withContext(Dispatchers.IO) { Cache.getInstance(this@PortFavoriteFolderEditorActivity, id) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@launch
                    }
                    cache.getGalleryInfo()?.let { info ->
                        title.text = info.title
                        subtitle.text = info.artists.orEmpty().joinToString { it.artist }.ifBlank { info.language.orEmpty() }
                    }
                    runCatching { loadPortCover(this@PortFavoriteFolderEditorActivity, cache.getThumbnail(), 140) }
                        .getOrNull()?.let(image::setImageBitmap)
                }
            }

            private fun updateCheck(id: Int) {
                val active = id in selectedIDs
                check.text = if (active) "✓" else "○"
                check.setTextColor(if (active) style.accent else style.muted)
            }

            fun clear() {
                job?.cancel()
                image.setImageDrawable(null)
            }
        }

        override fun getItemCount() = ids.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(style.row().apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = style.dp(6) }
        })
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(ids[position])
        override fun onViewRecycled(holder: Holder) = holder.clear()
    }
}
