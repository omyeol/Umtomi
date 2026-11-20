package xyz.sabok.umtomi.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sabok.umtomi.BuildConfig
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.histories
import xyz.sabok.umtomi.searchHistory
import xyz.sabok.umtomi.services.DownloadService
import xyz.sabok.umtomi.util.Preferences
import xyz.sabok.umtomi.util.FavoritesFiles
import xyz.sabok.umtomi.util.LastReadPages
import xyz.sabok.umtomi.util.checkUpdate
import xyz.sabok.umtomi.util.downloader.DownloadManager

class SettingsActivity : BaseActivity() {
    private val style by lazy { PortStyle(this) }
    private lateinit var form: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var navigation: WorkspaceNavigation
    private val exportFavorites = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) favoriteFileOperation {
            val json = FavoritesFiles.export(this)
            checkNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(json) }
        }
    }
    private val importFavorites = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) favoriteFileOperation {
            val json = checkNotNull(contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
            FavoritesFiles.restore(this, json)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = style.stack().apply { setBackgroundColor(style.background) }
        val top = style.row().apply {
            setPadding(style.dp(16), style.dp(5), style.dp(16), style.dp(8))
            addView(style.text(getString(R.string.settings_title), 33f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(style.action(getString(R.string.port_settings_done)) { finish() })
        }
        root.addView(top, style.matchWrap())
        form = style.stack().apply { setPadding(style.dp(16), 0, style.dp(16), style.dp(28)) }
        scroll = ScrollView(this).apply { addView(form) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation = WorkspaceNavigation(this, style.row()) {}
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        style.applySystemBars(this, root)
        render()
        state?.getInt("settings_scroll")?.let { offset -> scroll.post { scroll.scrollTo(0, offset) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("settings_scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        form.removeAllViews()
        section(R.string.port_settings_app) {
            row(R.string.port_settings_language, languageLabels()[languageIndex()]) { chooseLanguage() }
            toggle(R.string.settings_dark_mode_title, Preferences["dark_mode", false]) { enabled ->
                Preferences["dark_mode"] = enabled
                AppCompatDelegate.setDefaultNightMode(if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
        section { row(R.string.workspace_drive_sync) { open(DriveSyncActivity::class.java) } }
        section(R.string.workspace_search) {
            editable(R.string.port_settings_query, "default_query")
            stepper(R.string.settings_galleries_per_page, (Preferences["per_page", "25"].toIntOrNull() ?: 25), 10..100) {
                Preferences["per_page"] = it.toString()
            }
            editable(R.string.port_settings_include, "port_included_tags")
            editable(R.string.port_settings_exclude, "port_excluded_tags")
        }
        note(R.string.port_settings_filter_hint)
        section(R.string.workspace_recommendations) {
            stepper(R.string.port_settings_recommendation_count, Preferences["port_recommendation_count", 30], 5..100) {
                Preferences["port_recommendation_count"] = it
            }
        }
        section(R.string.port_settings_backup) {
            row(R.string.settings_backup_title) { exportFavorites.launch("UmtomiFavorites.json") }
            row(R.string.settings_restore_title) { importFavorites.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        }
        section(R.string.workspace_tab_settings) {
            row(R.string.workspace_tab_settings) { navigation.showSettings() }
        }
        section(R.string.port_reader) {
            val paged = Preferences["port_reader_paged", false]
            row(R.string.port_settings_reader_mode, getString(if (paged) R.string.port_settings_paged else R.string.port_settings_vertical)) {
                AlertDialog.Builder(this@SettingsActivity).setTitle(R.string.port_settings_reader_mode)
                    .setSingleChoiceItems(intArrayOf(R.string.port_settings_vertical, R.string.port_settings_paged)
                        .map { getString(it) }.toTypedArray(), if (paged) 1 else 0) { dialog, which ->
                        Preferences["port_reader_paged"] = which == 1
                        dialog.dismiss()
                        render()
                    }.show()
            }
            val aroundResume = Preferences["port_resume_prefetch", "around_resume_page"] != "from_beginning"
            row(R.string.port_settings_resume_preload, getString(if (aroundResume) R.string.port_settings_preload_around else R.string.port_settings_preload_beginning)) {
                AlertDialog.Builder(this@SettingsActivity).setTitle(R.string.port_settings_resume_preload)
                    .setSingleChoiceItems(intArrayOf(R.string.port_settings_preload_beginning, R.string.port_settings_preload_around)
                        .map { getString(it) }.toTypedArray(), if (aroundResume) 1 else 0) { dialog, which ->
                        Preferences["port_resume_prefetch"] = if (which == 0) "from_beginning" else "around_resume_page"
                        dialog.dismiss()
                        render()
                    }.show()
            }
            toggle(R.string.settings_rtl, Preferences["rtl", false], paged) { Preferences["rtl"] = it }
            toggle(R.string.port_settings_volume, Preferences["reader_volume_navigation", false]) { Preferences["reader_volume_navigation"] = it }
        }
        if (!Preferences["port_reader_paged", false]) note(R.string.port_settings_rtl_vertical_note)
        section(R.string.port_settings_privacy) {
            row(R.string.settings_app_lock) { open(PortLockSettingsActivity::class.java) }
            toggle(R.string.port_settings_hide_app, Preferences["security_mode", false]) {
                Preferences["security_mode"] = it
                if (it) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        section(R.string.port_settings_data) {
            row(R.string.settings_clear_downloads, danger = true) {
                confirm(R.string.settings_clear_downloads, R.string.settings_clear_downloads_alert_message) {
                    lifecycleScope.launch {
                        val ids = withContext(Dispatchers.IO) { DownloadManager.getInstance(this@SettingsActivity).downloadFolderMap.keys.toList() }
                        ids.forEach { DownloadService.delete(this@SettingsActivity, it) }
                    }
                }
            }
            row(R.string.settings_clear_history, danger = true) {
                confirm(R.string.settings_clear_history, R.string.settings_clear_history_alert_message) {
                    histories.clear()
                    LastReadPages.clearAll(this@SettingsActivity)
                }
            }
            row(R.string.port_settings_clear_searches, danger = true) {
                confirm(R.string.port_settings_clear_searches, R.string.port_settings_clear_searches_confirm) { searchHistory.clear() }
            }
        }
        section(R.string.port_support) {
            row(R.string.port_settings_update, BuildConfig.VERSION_NAME) { checkUpdate(this@SettingsActivity, true) }
            row(R.string.settings_download_folder) {
                xyz.sabok.umtomi.ui.dialog.DownloadLocationDialogFragment().show(supportFragmentManager, "download_location")
            }
        }
    }

    private fun section(title: Int? = null, add: SettingsRows.() -> Unit) {
        if (title != null) {
            form.addView(style.text(getString(title), 14f, style.muted, bold = true).apply {
                ViewCompat.setAccessibilityHeading(this, true)
                setPadding(style.dp(12), style.dp(18), 0, style.dp(5))
            })
        }
        val box = style.stack().apply { background = style.rounded(style.surface, 14) }
        SettingsRows(box).add()
        form.addView(box, style.matchWrap().apply { bottomMargin = style.dp(10) })
    }

    private fun note(text: Int) = form.addView(style.text(getString(text), 13f, style.muted).apply {
        setPadding(style.dp(12), 0, style.dp(12), style.dp(6))
    })

    private inner class SettingsRows(private val box: LinearLayout) {
        private fun append(view: View) {
            if (box.childCount > 0) box.addView(View(this@SettingsActivity).apply {
                setBackgroundColor(style.divider)
            }, LinearLayout.LayoutParams(-1, style.dp(1)).apply { marginStart = style.dp(13) })
            box.addView(view, style.matchWrap())
        }

        private fun line() = style.row().apply {
            minimumHeight = style.dp(50)
            setPadding(style.dp(13), style.dp(8), style.dp(13), style.dp(8))
        }

        fun row(title: Int, value: String = "", danger: Boolean = false, action: () -> Unit) {
            append(line().apply {
                addView(style.text(getString(title), 15.5f, if (danger) style.danger else style.foreground),
                    LinearLayout.LayoutParams(0, -2, 1f))
                if (value.isNotBlank()) addView(style.text(value.take(28), 13.5f, style.muted).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = style.dp(8) })
                if (!danger) addView(style.text("›", 20f, style.muted), LinearLayout.LayoutParams(-2, -2).apply { marginStart = style.dp(8) })
                background = style.ripple(android.graphics.Color.TRANSPARENT, 13)
                isFocusable = true
                setOnClickListener { action() }
            })
        }

        fun toggle(title: Int, selected: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
            append(SwitchCompat(this@SettingsActivity).apply {
                setText(title)
                setTextColor(style.foreground)
                setPadding(style.dp(13), style.dp(5), style.dp(13), style.dp(5))
                minHeight = style.dp(50)
                isChecked = selected
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.42f
                setOnCheckedChangeListener { _, value -> change(value) }
            })
        }

        fun editable(title: Int, key: String) = row(title, Preferences[key, ""]) {
            val field = EditText(this@SettingsActivity).apply {
                setSingleLine()
                setText(Preferences[key, ""])
            }
            AlertDialog.Builder(this@SettingsActivity).setTitle(title).setView(field)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Preferences[key] = field.text.toString().trim().take(512)
                    render()
                }.show()
        }

        fun stepper(title: Int, initial: Int, limits: IntRange, change: (Int) -> Unit) {
            var value = initial.coerceIn(limits)
            val label = style.text(getString(title) + " · " + value, 16f)
            val controls = line()
            controls.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            fun adjust(delta: Int) {
                value = (value + delta).coerceIn(limits)
                change(value)
                label.text = getString(title) + " · " + value
            }
            controls.addView(style.action("−") { adjust(-5) }.apply { contentDescription = getString(R.string.port_settings_decrease, getString(title)) })
            controls.addView(style.action("+") { adjust(5) }.apply { contentDescription = getString(R.string.port_settings_increase, getString(title)) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = style.dp(6) })
            append(controls)
        }
    }

    private val languages = listOf("", "en", "ko", "ja", "zh")
    private fun languageLabels() = listOf(getString(R.string.port_settings_system), "English", "한국어", "日本語", "中文")
    private fun languageIndex() = languages.indexOf(Preferences["port_app_locale", ""]).coerceAtLeast(0)
    private fun chooseLanguage() {
        AlertDialog.Builder(this).setTitle(R.string.port_settings_language)
            .setSingleChoiceItems(languageLabels().toTypedArray(), languageIndex()) { dialog, which ->
                dialog.dismiss()
                Preferences["port_app_locale"] = languages[which]
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languages[which]))
            }.show()
    }

    private fun open(activity: Class<*>) = startActivity(Intent(this, activity))
    private fun favoriteFileOperation(operation: () -> Unit) = lifecycleScope.launch {
        val success = withContext(Dispatchers.IO) { runCatching(operation).isSuccess }
        android.widget.Toast.makeText(this@SettingsActivity,
            if (success) R.string.port_settings_done else R.string.settings_restore_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun confirm(title: Int, message: Int, action: () -> Unit) =
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> action() }.show()
}
