package xyz.sabok.umtomi.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.sabok.umtomi.BuildConfig
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.util.getApkUrl
import xyz.sabok.umtomi.util.findAndroidUpdate
import xyz.sabok.umtomi.util.isNewerVersion
import xyz.sabok.umtomi.util.installedVersion
import java.io.File

class UpdateActivity : BaseActivity() {
    private lateinit var headline: TextView
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var progress: ProgressBar
    private var releasePage = "https://github.com/omyeol/Umtomi/releases"
    private val state by lazy { getSharedPreferences("app-updater", Context.MODE_PRIVATE) }
    private val downloads by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private val target by lazy { File(getExternalFilesDir(null), "Umtomi-update.apk") }
    private var job: Job? = null
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) install()
        else status.setText(R.string.updater_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.updater_open)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        status = TextView(this).apply { textSize = 17f }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        action = Button(this)
        headline = TextView(this).apply {
            text = "Umtomi 업데이트"; textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 16, 0, 32)
        }
        content.addView(headline)
        status.setLineSpacing(8f, 1.1f)
        status.setPadding(0, 0, 0, 32)
        action.isAllCaps = false
        action.minHeight = (56 * resources.displayMetrics.density).toInt()
        action.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(165, 232, 208))
        action.setTextColor(android.graphics.Color.rgb(12, 14, 18))
        content.addView(status)
        content.addView(progress)
        content.addView(action)
        content.addView(Button(this).apply {
            setText(R.string.updater_releases)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasePage))) }
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        if (job?.isActive != true) {
            clearInstalledDownload()
            if (state.getLong("download_id", -1) != -1L) watchDownload() else check()
        }
    }

    override fun onStop() {
        job?.cancel()
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun check() {
        job?.cancel()
        headline.text = "Umtomi 업데이트"
        status.setText(R.string.updater_checking)
        progress.isIndeterminate = true
        progress.progress = 0
        action.isEnabled = false
        action.setText(R.string.updater_check)
        job = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { findAndroidUpdate(installedVersion(this@UpdateActivity)) }
            }
            progress.isIndeterminate = false
            action.isEnabled = true
            result.fold(onSuccess = { release ->
                if (release == null) {
                    headline.text = "최신 버전입니다"
                    status.text = "Umtomi ${installedVersion(this@UpdateActivity)}\n설치할 새 업데이트가 없습니다."
                    action.setOnClickListener { check() }
                } else {
                    val apkURL = requireNotNull(getApkUrl(release))
                    releasePage = apkURL.substringBefore("/download/")
                    val version = release["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    headline.text = "Umtomi $version"
                    status.text = "이 버전으로 업데이트합니다\n\n" + release["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    action.setText(R.string.updater_download)
                    action.setOnClickListener { download(apkURL, version) }
                }
            }, onFailure = { showError(it) })
        }
    }

    private fun download(url: String, version: String) {
        runCatching {
            if (!isNewerVersion(version, installedVersion(this))) { check(); return }
            clearDownload()
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Umtomi $version").setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(target))
            val id = downloads.enqueue(request)
            state.edit().putLong("download_id", id).putString("target_version", version).apply()
            watchDownload()
        }.onFailure { showError(it) }
    }

    private fun watchDownload() {
        job?.cancel()
        headline.text = "Umtomi ${state.getString("target_version", "업데이트")}"
        action.isEnabled = true
        action.setText(android.R.string.cancel)
        action.setOnClickListener {
            clearDownload()
            check()
        }
        job = lifecycleScope.launch {
            while (true) {
                val row = withContext(Dispatchers.IO) {
                    runCatching {
                        downloads.query(DownloadManager.Query().setFilterById(state.getLong("download_id", -1))).use {
                            if (!it.moveToFirst()) error(getString(R.string.updater_download_failed))
                            longArrayOf(
                                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            )
                        }
                    }
                }.getOrElse { showError(it); return@launch }
                when (row[0].toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (clearInstalledDownload()) { check(); return@launch }
                        val validation = withContext(Dispatchers.IO) { runCatching { validateApk() } }
                        validation.fold(onSuccess = {
                            val archiveVersion = packageManager.getPackageArchiveInfo(target.absolutePath, 0)?.versionName
                            headline.text = "Umtomi ${archiveVersion ?: state.getString("target_version", "업데이트")}"
                            progress.isIndeterminate = false
                            progress.progress = 100
                            status.setText(R.string.updater_ready)
                            action.setText(R.string.updater_install)
                            action.setOnClickListener { install() }
                        }, onFailure = { showError(it) })
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> { showError(IllegalStateException(getString(R.string.updater_download_failed))); return@launch }
                    else -> {
                        progress.isIndeterminate = row[2] <= 0
                        progress.progress = if (row[2] > 0) (row[1] * 100 / row[2]).toInt() else 0
                        status.text = getString(R.string.update_notification_description) + " ${row[1] / 1024} KB"
                    }
                }
                delay(750)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk() {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = packageManager.getPackageArchiveInfo(target.absolutePath, flags)
            ?: error(getString(R.string.updater_invalid))
        val installed = packageManager.getPackageInfo(packageName, flags)
        val newer = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode > installed.longVersionCode
            else archive.versionCode > installed.versionCode
        val apkSignatures = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val ourSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(archive.packageName == packageName && newer && !apkSignatures.isNullOrEmpty() &&
            apkSignatures.toSet() == ourSignatures?.toSet()) { getString(R.string.updater_invalid) }
    }

    private fun install() {
        if (clearInstalledDownload()) { check(); return }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            status.setText(R.string.updater_permission)
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        runCatching {
            validateApk()
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", target)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { showError(it) }
    }

    private fun clearDownload() {
        val id = state.getLong("download_id", -1)
        if (id != -1L) downloads.remove(id)
        state.edit().remove("download_id").remove("target_version").apply()
        target.delete()
    }

    @Suppress("DEPRECATION")
    private fun clearInstalledDownload(): Boolean {
        if (state.getLong("download_id", -1) == -1L) return false
        val installed = packageManager.getPackageInfo(packageName, 0)
        val archive = packageManager.getPackageArchiveInfo(target.absolutePath, 0)
        // Migrate old downloads without target_version using the actual APK code.
        val alreadyInstalled = if (archive?.packageName == packageName) {
            if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode <= installed.longVersionCode
            else archive.versionCode <= installed.versionCode
        } else {
            val version = state.getString("target_version", null)
            version != null && !isNewerVersion(version, installed.versionName.orEmpty())
        }
        if (alreadyInstalled) clearDownload()
        return alreadyInstalled
    }

    private fun showError(error: Throwable) {
        clearDownload()
        progress.isIndeterminate = false
        status.text = getString(R.string.updater_error, error.localizedMessage.orEmpty())
        action.isEnabled = true
        action.setText(R.string.updater_check)
        action.setOnClickListener { check() }
    }
}
