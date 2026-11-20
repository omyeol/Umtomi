package xyz.sabok.umtomi.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import xyz.sabok.umtomi.R

/** Shared presentation for the Android screens ported from the iOS app. */
internal class PortStyle(private val context: Context) {
    val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val background = color(R.color.workspace_background)
    val barBackground = color(R.color.workspace_bar)
    val surface = color(R.color.workspace_surface)
    val foreground = color(R.color.workspace_text)
    val muted = color(R.color.workspace_muted)
    val accent = color(R.color.workspace_accent)
    val selected = color(R.color.workspace_selected)
    val divider = color(R.color.workspace_divider)
    val danger = if (isDark) 0xFFFF453A.toInt() else 0xFFD33B39.toInt()

    private fun color(res: Int) = ContextCompat.getColor(context, res)
    fun dp(size: Int) = (size * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (isDark && fill == surface) setStroke(maxOf(1, dp(1) / 2), divider)
    }

    fun ripple(fill: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(0x227F8EA4),
        rounded(fill, radius),
        rounded(Color.WHITE, radius),
    )

    fun stack() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

    fun text(value: CharSequence, size: Float, color: Int = foreground, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun action(label: CharSequence, prominent: Boolean = false, click: () -> Unit) =
        text(label, 15f, if (prominent) Color.WHITE else accent, true).apply {
            minHeight = dp(46)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ripple(if (prominent) accent else selected, 12)
            isFocusable = true
            setOnClickListener { click() }
        }

    fun applySystemBars(activity: Activity, anchor: View = activity.window.decorView, bottomColor: Int = background) {
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = bottomColor
        WindowInsetsControllerCompat(activity.window, anchor).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
