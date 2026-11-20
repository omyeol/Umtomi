package xyz.sabok.umtomi.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** The controller owns the bitmap; detach it before the controller recycles it. */
internal class CapturePreviewDialog(
    context: Context, private val bitmap: Bitmap, private val initialFolder: String,
    private val onFile: () -> Unit,
    private val onFolder: ((String) -> Unit) -> Unit,
    private val onShelf: () -> Unit, private val onClosed: () -> Unit,
) : Dialog(context) {
    private val buttons = mutableListOf<Button>()
    private lateinit var heading: TextView
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val ink = Color.rgb(242, 244, 248)
    private val mint = Color.rgb(165, 232, 208)
    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.statusBarColor = Color.BLACK
        window?.navigationBarColor = Color.BLACK
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading = TextView(context).apply {
            text = "캡처 미리보기"; textSize = 23f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(heading, LinearLayout.LayoutParams(0, dp(56), 1f))
        top.addView(button("닫기") { dismiss() }, LinearLayout.LayoutParams(dp(64), dp(48)))
        root.addView(top)
        root.addView(TextView(context).apply {
            text = "${bitmap.width} × ${bitmap.height} px  ·  PNG"; textSize = 13f
            setTextColor(Color.rgb(166, 176, 191)); setPadding(0, 0, 0, dp(12))
        })
        val image = ImageView(context).apply {
            contentDescription = "캡처한 이미지 미리보기"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            setBackgroundColor(Color.rgb(10, 10, 12))
        }
        root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val folder = button("저장할 책장 변경 · $initialFolder") {}
        folder.contentDescription = "저장할 책장 변경"
        folder.setOnClickListener { onFolder { folder.text = "저장할 책장 변경 · $it" } }
        root.addView(folder, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16); bottomMargin = dp(12) })
        val row = LinearLayout(context)
        row.addView(button("파일로 저장", action = onFile), LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) })
        row.addView(button("책장에 추가", true, onShelf), LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(row)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom)
            insets
        }
        setOnDismissListener { image.setImageDrawable(null); onClosed() }
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
        setTextColor(if (primary) Color.rgb(12, 14, 18) else ink)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(if (primary) mint else Color.rgb(37, 42, 51))
        }
        setOnClickListener { action() }; buttons.add(this)
    }
    fun setSaving(saving: Boolean) {
        buttons.forEach { it.isEnabled = !saving; it.alpha = if (saving) .5f else 1f }
        setCancelable(!saving)
        heading.text = if (saving) "저장 중…" else "캡처 미리보기"
    }
    override fun show() {
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
