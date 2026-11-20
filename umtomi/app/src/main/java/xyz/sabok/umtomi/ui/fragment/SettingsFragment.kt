/*
 *     Umtomi, Hitomi.la viewer for Android
 *     Copyright (C) 2020  tom5079
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

package xyz.sabok.umtomi.ui.fragment

import android.app.Activity
import android.content.*
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.*
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import xyz.sabok.umtomi.util.LocalDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import okhttp3.Dispatcher
import xyz.quaver.io.FileX
import xyz.quaver.io.util.getChild
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.client
import xyz.sabok.umtomi.clientBuilder
import xyz.sabok.umtomi.clientHolder
import xyz.sabok.umtomi.types.SendLogException
import xyz.sabok.umtomi.ui.LockActivity
import xyz.sabok.umtomi.ui.SettingsActivity
import xyz.sabok.umtomi.ui.TransferActivity
import xyz.sabok.umtomi.ui.dialog.*
import xyz.sabok.umtomi.util.*
import xyz.sabok.umtomi.util.downloader.DownloadManager
import java.util.*
import java.util.concurrent.Executors

class SettingsFragment :
    PreferenceFragmentCompat(),
    Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private fun downloadFolderSummary(): String {
        val location = Preferences.get<String>("download_folder")
        if (location.isBlank()) return requireContext().getExternalFilesDir(null)?.absolutePath.orEmpty()
        return runCatching { FileX(requireContext(), location).canonicalPath }.getOrDefault(location)
    }

    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.settings, LockSettingsFragment())
                .addToBackStack("Lock")
                .commitAllowingStateLoss()
        }
    }

    override fun onResume() {
        super.onResume()

        val lockManager = LockManager(requireContext())

        findPreference<Preference>("app_lock")?.summary = if (lockManager.locks.isNullOrEmpty()) {
            getString(R.string.settings_lock_none)
        } else {
            lockManager.locks?.joinToString(", ") {
                when(it.type) {
                    Lock.Type.PATTERN -> getString(R.string.settings_lock_pattern)
                    Lock.Type.PIN -> getString(R.string.settings_lock_pin)
                    Lock.Type.PASSWORD -> getString(R.string.settings_lock_password)
                }
            }
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        with (preference) {
            when (key) {
                "drive_sync" -> startActivity(Intent(requireContext(), xyz.sabok.umtomi.ui.DriveSyncActivity::class.java))
                "app_version" -> {
                    checkUpdate(activity as SettingsActivity, true)
                }
                "download_folder" -> {
                    DownloadLocationDialogFragment().show(parentFragmentManager, "Download Location Dialog")
                }
                "default_query" -> {
                    DefaultQueryDialog().apply {
                        onPositiveButtonClickListener = { newTags ->
                            Preferences["default_query"] = newTags.toString()
                            summary = newTags.toString()
                        }
                    }.show(parentFragmentManager, "Default Query Dialog")
                }
                "app_lock" -> {
                    val intent = Intent(requireContext(), LockActivity::class.java).apply {
                        putExtra("force", true)
                    }
                    lockLauncher.launch(intent)
                }
                "proxy" -> {
                    ProxyDialogFragment().show(parentFragmentManager, "Proxy Dialog")
                }
                "user_id" -> {
                    LocalDiagnostics.recordException(SendLogException())
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                        ClipData.newPlainText("user_id", Preferences.get<String>("user_id"))
                    )
                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
                "transfer_data" -> {
                    activity?.startActivity(Intent(activity, TransferActivity::class.java))
                }
                else -> return false
            }
        }

        return true
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        with (preference) {
            when (key) {
                "tag_translation" -> {
                    updateTranslations()
                }
                "nomedia" -> {
                    val create = (newValue as? Boolean) ?: return false

                    return kotlin.runCatching {
                        val nomedia = DownloadManager.getInstance(context).downloadFolder.getChild(".nomedia")

                        if (create)
                            nomedia.createNewFile()
                        else
                            nomedia.delete()
                    }.getOrDefault(false)
                }
                "dark_mode" -> {
                    val enabled = newValue as Boolean
                    Preferences["dark_mode"] = enabled
                    (preference as SwitchPreferenceCompat).isChecked = enabled
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val requested = if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                        if (AppCompatDelegate.getDefaultNightMode() != requested)
                            AppCompatDelegate.setDefaultNightMode(requested)
                    }
                    return false
                }
                else -> return false
            }
        }

        return true
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        key ?: return

        with(findPreference<Preference>(key)) {
            this ?: return

            when (key) {
                "proxy" -> {
                    summary = context.let { getProxyInfo().type.name }
                }
                "download_folder" -> {
                    summary = downloadFolderSummary()
                }
                "download_folder_name" -> {
                    summary = Preferences["download_folder_name", "[-id-] -title-"]
                }
                "max_concurrent_download" -> {
                    val newValue = Preferences.get<String>(key).toIntOrNull() ?: 0

                    if (newValue == 0)
                        clientBuilder.dispatcher(Dispatcher())
                    else
                        clientBuilder.dispatcher((Dispatcher(Executors.newFixedThreadPool(newValue))))

                    clientHolder = null
                    client
                }
                else -> return
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        Preferences.registerOnSharedPreferenceChangeListener(this)

        initPreferences()
    }

    override fun onDestroy() {
        Preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    private fun initPreferences() {
        for (i in 0 until preferenceScreen.preferenceCount) {

            preferenceScreen.getPreference(i).run {
                if (this is PreferenceCategory)
                    (0 until preferenceCount).map { getPreference(it) }
                else
                    listOf(this)
            }.forEach { preference ->
                with (preference) with@{

                    when (key) {
                        "drive_sync" -> { onPreferenceClickListener = this@SettingsFragment }
                        "app_version" -> {
                            val manager = requireContext().packageManager
                            val info = manager.getPackageInfo(requireContext().packageName, 0)
                            summary = requireContext().getString(R.string.settings_app_version_description, info.versionName)

                            onPreferenceClickListener = this@SettingsFragment
                        }
                        "download_folder_name" -> {
                            summary = Preferences["download_folder_name", "[-id-] -title-"]

                            setOnPreferenceClickListener {
                                DownloadFolderNameDialogFragment().show(requireActivity().supportFragmentManager, "Download Location Dialog")

                                true
                            }
                        }
                        "download_folder" -> {
                            summary = downloadFolderSummary()

                            onPreferenceClickListener = this@SettingsFragment
                        }
                        "nomedia" -> {
                            (this as SwitchPreferenceCompat).isChecked = kotlin.runCatching {
                                DownloadManager.getInstance(context).downloadFolder.getChild(".nomedia").exists()
                            }.getOrDefault(false)

                            onPreferenceChangeListener = this@SettingsFragment
                        }
                        "default_query" -> {
                            summary = Preferences.get<String>("default_query")

                            onPreferenceClickListener = this@SettingsFragment
                        }
                        "app_lock" -> {
                            val lockManager = LockManager(requireContext())
                            summary =
                                if (lockManager.locks.isNullOrEmpty()) {
                                    getString(R.string.settings_lock_none)
                                } else {
                                    lockManager.locks?.joinToString(", ") {
                                        when (it.type) {
                                            Lock.Type.PATTERN -> getString(R.string.settings_lock_pattern)
                                            Lock.Type.PIN -> getString(R.string.settings_lock_pin)
                                            Lock.Type.PASSWORD -> getString(R.string.settings_lock_password)
                                        }
                                    }
                                }

                            onPreferenceClickListener = this@SettingsFragment
                        }
                        "proxy" -> {
                            summary = getProxyInfo().type.name

                            onPreferenceClickListener = this@SettingsFragment
                        }
                        "tag_translation" -> {
                            this as ListPreference

                            isEnabled = false

                            lifecycleScope.launch {
                                val languages = withContext(Dispatchers.IO) {
                                    getAvailableLanguages().distinct().toTypedArray()
                                }
                                entries = languages.map { Locale(it).let { loc -> loc.getDisplayLanguage(loc) } }.toTypedArray()
                                entryValues = languages
                                isEnabled = true
                            }

                            onPreferenceChangeListener = this@SettingsFragment

                        }
                        "dark_mode" -> {
                            onPreferenceChangeListener = this@SettingsFragment
                        }
                        "old_import_galleries" -> {
                            onPreferenceClickListener = this@SettingsFragment
                        }
                        "user_id" -> {
                            summary = Preferences.get<String>("user_id")
                            onPreferenceClickListener = this@SettingsFragment
                        }
                        "oss" -> {
                            setOnPreferenceClickListener {
                                context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
                                true
                            }
                        }
                        "transfer_data" -> {
                            onPreferenceClickListener = this@SettingsFragment
                        }
                    }

                }
            }
        }
    }
}
