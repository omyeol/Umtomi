package xyz.sabok.umtomi.ui

import android.accounts.Account
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.ProgressBar
import androidx.core.widget.doAfterTextChanged
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.sync.BookshelfSyncNotice
import xyz.sabok.umtomi.sync.BookshelfSyncWorker
import xyz.sabok.umtomi.sync.CloudAccount
import xyz.sabok.umtomi.sync.shared.SharedShelfSync
import xyz.sabok.umtomi.sync.DriveAppDataClient
import xyz.sabok.umtomi.sync.UmtomiDriveSync

class DriveSyncActivity : BaseActivity() {
    private lateinit var status: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var connect: TextView
    private lateinit var sync: TextView
    private lateinit var disconnect: TextView
    private var busy = false
    private var syncAfterAuthorization = false
    private var syncJob: Job? = null
    private lateinit var cancel: TextView
    private lateinit var backgroundSwitch: androidx.appcompat.widget.SwitchCompat
    private var selectedAccount: String? = null
    private val prefs by lazy { CloudAccount.preferences(this) }
    private val authHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var authGeneration = 0
    private var awaitingResolution = false
    private var authTimeout: Runnable? = null
    private fun clearAuthTimeout() { authTimeout?.let { authHandler.removeCallbacks(it) }; authTimeout = null }
    internal fun expireAuthorization() {
        authGeneration++; awaitingResolution = false; clearAuthTimeout()
        selectedAccount = CloudAccount.account(this); setBusy(false)
        status.text = "Google 응답 시간이 초과되었습니다. 네트워크와 Google Play 서비스를 확인하고 다시 연결해 주세요."
    }
    override fun onDestroy() { authGeneration++; clearAuthTimeout(); super.onDestroy() }
    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed && !isDestroyed) status.setText(R.string.workspace_drive_allow_notifications)
    }
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && Build.VERSION.SDK_INT >= 26) {
            selectedAccount = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            authorize(syncNow = false)
        } else setBusy(false)
    }
    private val authorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (!awaitingResolution) return@registerForActivityResult
        awaitingResolution = false
        if (Build.VERSION.SDK_INT >= 26 && result.resultCode == Activity.RESULT_OK) {
            runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data) }
                .onSuccess { accepted(it) }.onFailure { failed() }
        } else { selectedAccount = CloudAccount.account(this); setBusy(false); status.text = "연결이 취소되었습니다. 기존 데이터는 유지됩니다." }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingResolution = savedInstanceState?.getBoolean("awaitingResolution") ?: false
        syncAfterAuthorization = savedInstanceState?.getBoolean("syncAfterAuthorization") ?: false
        selectedAccount = savedInstanceState?.getString("account") ?: CloudAccount.account(this)
        val style = PortStyle(this)
        val root = style.stack().apply {
            setBackgroundColor(style.background)
            setPadding(style.dp(16), style.dp(10), style.dp(16), style.dp(24))
        }
        val header = style.row()
        header.addView(style.text(getString(R.string.workspace_drive_sync), 27f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(style.action(getString(R.string.port_settings_done)) { onSupportNavigateUp() })
        root.addView(header, style.matchWrap())
        val account = style.stack().apply {
            background = style.rounded(style.surface, 14)
            setPadding(style.dp(12), style.dp(12), style.dp(12), style.dp(12))
        }
        root.addView(account, style.matchWrap().apply { topMargin = style.dp(14) })
        status = style.text(selectedAccount ?: getString(R.string.port_drive_unconnected), 15f, style.muted)
        account.addView(status, style.matchWrap())
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        account.addView(progressBar, style.matchWrap())
        val percentText = Regex("""(\d{1,3})%""")
        status.doAfterTextChanged { message ->
            val percent = percentText.find(message?.toString().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            progressBar.isIndeterminate = percent == null
            if (percent != null) progressBar.progress = percent.coerceIn(0, 100)
        }
        connect = style.action(getString(R.string.port_drive_connect)) {
            if (Build.VERSION.SDK_INT >= 26) {
                setBusy(true)
                picker.launch(AccountPicker.newChooseAccountIntent(AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(listOf("com.google")).setAlwaysShowAccountPicker(true).build()))
            }
        }
        sync = style.action(getString(R.string.port_drive_sync_now), prominent = true) {
            if (Build.VERSION.SDK_INT >= 26) authorize(syncNow = true)
        }
        disconnect = style.action(getString(R.string.port_drive_disconnect)) {
            CloudAccount.disconnect(this@DriveSyncActivity)
            selectedAccount = null
            BookshelfSyncWorker.updateSchedule(this@DriveSyncActivity)
            status.text = getString(R.string.port_drive_unconnected)
            setBusy(false)
        }
        cancel = style.action(getString(R.string.port_drive_cancel)) { cancelSync() }
        listOf(connect, sync, disconnect, cancel).forEach {
            account.addView(it, style.matchWrap().apply { topMargin = style.dp(7) })
        }
        val storage = getSharedPreferences("bookshelf_sync", MODE_PRIVATE)
        root.addView(style.action("") {
            val modes = arrayOf(getString(R.string.port_drive_local), getString(R.string.port_drive_on_demand))
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.port_drive_image_mode)
                .setSingleChoiceItems(modes, if (storage.getString("image_mode", "local") == "on_demand") 1 else 0) { dialog, index ->
                    storage.edit().putString("image_mode", if (index == 1) "on_demand" else "local").apply()
                    (root.getChildAt(2) as TextView).text = modes[index]
                    dialog.dismiss()
                }.show()
        }.apply {
            setText(if (storage.getString("image_mode", "local") == "on_demand") R.string.port_drive_on_demand else R.string.port_drive_local)
            contentDescription = getString(R.string.port_drive_image_mode)
        }, style.matchWrap().apply { topMargin = style.dp(16) })
        backgroundSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            setText(R.string.workspace_drive_background)
            setTextColor(style.foreground)
            isChecked = prefs.getBoolean(BookshelfSyncWorker.ENABLE_KEY, false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(BookshelfSyncWorker.ENABLE_KEY, enabled).apply()
                BookshelfSyncWorker.updateSchedule(this@DriveSyncActivity)
            }
        }
        root.addView(backgroundSwitch)
        root.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            setText(R.string.workspace_drive_notifications)
            setTextColor(style.foreground)
            isChecked = prefs.getBoolean(BookshelfSyncNotice.ENABLE_KEY, false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(BookshelfSyncNotice.ENABLE_KEY, enabled).apply()
                if (!enabled) BookshelfSyncNotice.clear(this@DriveSyncActivity)
                else if (Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(this@DriveSyncActivity, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        })
        val page = android.widget.ScrollView(this).apply {
            setBackgroundColor(style.background)
            addView(root)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(page)
        style.applySystemBars(this, page)
        setBusy(awaitingResolution)
        if (prefs.getBoolean("conflict", false)) status.text = "기존 책장과 클라우드 계정이 다릅니다. 사용할 Google 계정을 한 번 선택해 주세요. 기존 자료는 유지됩니다."
        if (Build.VERSION.SDK_INT < 26) status.text = "Google Drive 동기화는 Android 8 이상에서 사용할 수 있습니다."
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("awaitingResolution", awaitingResolution); outState.putString("account", selectedAccount); outState.putBoolean("syncAfterAuthorization", syncAfterAuthorization); super.onSaveInstanceState(outState) }
    override fun onSupportNavigateUp(): Boolean { if (busy) cancelSync() else finish(); return true }
    override fun onBackPressed() { if (busy) cancelSync() else super.onBackPressed() }
    private fun setBusy(value: Boolean) {
        busy = value
        if (!::connect.isInitialized) return
        progressBar.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        connect.isEnabled = !value && Build.VERSION.SDK_INT >= 26
        sync.isEnabled = !value && selectedAccount != null && Build.VERSION.SDK_INT >= 26
        disconnect.isEnabled = !value && selectedAccount != null
        if (::backgroundSwitch.isInitialized) {
            backgroundSwitch.isEnabled = !value && selectedAccount != null && Build.VERSION.SDK_INT >= 26
        }
        cancel.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        for (action in listOf(connect, sync, disconnect)) action.alpha = if (action.isEnabled) 1f else 0.42f
    }
    private fun failed() { clearAuthTimeout(); selectedAccount = CloudAccount.account(this); setBusy(false); status.text = "연결 또는 동기화에 실패했습니다. 네트워크, Google Drive 권한과 Umtomi의 Android OAuth 등록을 확인한 뒤 다시 시도해 주세요. 완료된 전송은 다음 시도에 이어집니다." }
    private fun cancelSync() {
        if (syncJob == null) {
            authGeneration++; awaitingResolution = false; clearAuthTimeout()
            selectedAccount = CloudAccount.account(this); setBusy(false)
            status.text = "연결을 중단했습니다. 기존 계정과 자료는 유지됩니다."
            return
        }
        status.text = "동기화를 중단하는 중입니다."
        syncJob?.cancel()
    }
    @RequiresApi(26)
    private fun authorize(syncNow: Boolean) {
        syncAfterAuthorization = syncNow
        val email = selectedAccount ?: run { setBusy(false); return }
        setBusy(true); status.text = "Google Drive 연결 확인 중"
        val request = AuthorizationRequest.builder().setAccount(Account(email, "com.google"))
            .setRequestedScopes(listOf(Scope(DriveAppDataClient.SCOPE))).setOptOutIncludingGrantedScopes(true).build()
        val generation = ++authGeneration
        clearAuthTimeout()
        authTimeout = Runnable { if (generation == authGeneration && !isDestroyed) expireAuthorization() }
        authHandler.postDelayed(requireNotNull(authTimeout), 30_000)
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (generation != authGeneration || isDestroyed) return@addOnSuccessListener
                clearAuthTimeout()
                if (result.hasResolution()) {
                    awaitingResolution = true
                    authorization.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent)).build())
                }
                else accepted(result)
            }.addOnFailureListener { if (generation == authGeneration && !isDestroyed) { clearAuthTimeout(); failed() } }
    }
    @RequiresApi(26)
    private fun accepted(result: AuthorizationResult) {
        val token = result.accessToken
        val account = selectedAccount
        if (token == null || account == null || DriveAppDataClient.SCOPE !in result.grantedScopes) { failed(); return }
        finishAuthorization(account, token)
    }
    @RequiresApi(26)
    internal fun finishAuthorization(account: String, token: String) {
        clearAuthTimeout()
        CloudAccount.connect(this, account)
        BookshelfSyncWorker.updateSchedule(this)
        selectedAccount = account
        if (!syncAfterAuthorization) {
            setBusy(false)
            status.text = "계정 연결 완료\n$account\n지금 동기화를 누르면 데이터를 동기화합니다."
            return
        }
        syncAfterAuthorization = false
        setBusy(true)
        syncJob = lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val notice = BookshelfSyncNotice(applicationContext, background = false)
            try {
                withTimeout(10 * 60 * 1000L) {
                    runInterruptible(Dispatchers.IO) {
                        val progress: (String) -> Unit = { message ->
                            notice.report(message)
                            runOnUiThread { if (syncJob?.isActive == true) status.text = message }
                        }
                        progress("즐겨찾기와 열람 기록 동기화 중")
                        UmtomiDriveSync.run(applicationContext, account, { token }, includeLegacyShelf = false, progress = progress)
                        progress("공통 책장 암호화 키 확인 중")
                        SharedShelfSync.run(applicationContext, account, { token }, progress = progress) {
                            !Thread.currentThread().isInterrupted && CloudAccount.account(applicationContext) == account
                        }
                    }
                }
                status.text = "동기화 완료\n$account\n책장·열람 기록·즐겨찾기를 반영했습니다."
                notice.report(getString(R.string.workspace_drive_complete), finished = true)
            } catch (error: TimeoutCancellationException) {
                notice.report(getString(R.string.workspace_drive_retry), finished = true)
                status.text = "동기화 대기 시간이 초과되었습니다. 네트워크를 확인하고 다시 시도해 주세요."
            } catch (error: CancellationException) {
                notice.clear()
                status.text = "동기화를 중단했습니다. 완료된 전송은 다음 시도에 이어집니다."
                throw error
            } catch (error: Exception) {
                notice.report(getString(R.string.workspace_drive_retry), finished = true)
                if (error.message == "Omyaru 연결 확인 필요") status.text = "즐겨찾기·기록 동기화 완료. 책장은 Omyaru Browser에서 같은 계정으로 책장 동기화를 먼저 켠 뒤 다시 시도해 주세요." else failed()
            }
            finally { syncJob = null; setBusy(false) }
        }
        setBusy(true)
        syncJob?.start()
    }
}

