package xyz.sabok.umtomi.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.concurrent.Executors
import xyz.sabok.umtomi.sync.CloudAccount

class BookshelfStorageActivity : ComponentActivity() {
    companion object {
        const val EXTRA_RETURN_OK_ON_COMPLETE = "return_ok_on_complete"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var accountPreview: TextView
    private lateinit var pickFolder: Button
    private lateinit var useDefaultFolder: Button
    private lateinit var usePrivateFolder: Button
    private lateinit var medium: Button
    private lateinit var imageMode: Button
    private var busy = false
    private var pendingActionAfterPermission: String? = null
    private var returnOkOnComplete = false

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val resolved = SharedBookshelfStorage.resolveTreeUriToDirectory(this, uri)
        if (resolved == null) {
            status.text = "선택한 폴더의 실제 저장 경로를 확인할 수 없습니다. 내부 저장소 또는 SD 카드의 폴더를 선택해 주세요."
            return@registerForActivityResult
        }
        activateSelectedBaseFolder(resolved)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returnOkOnComplete = intent.getBooleanExtra(EXTRA_RETURN_OK_ON_COMPLETE, false)
        val spacing = (20 * resources.displayMetrics.density).toInt()
        val smallGap = (10 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing * 2, spacing, spacing)
        }
        root.addView(TextView(this).apply { text = "책장 동기화 공용 폴더"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Omyaru Browser와 Umtomi에서 같은 폴더를 지정하면 동기화된 책장 사진이 2배로 저장되지 않고 1벌만 공유됩니다.\n\n" +
                "선택한 폴더 안에 현재 Google 계정 이름의 하위 폴더가 자동 생성되므로, 두 앱이 다른 계정을 써도 섞이지 않습니다. " +
                "동시에 동기화가 실행될 때도 파일 잠금(Lock)으로 안전하게 순차 처리됩니다."
            textSize = 15f
            setPadding(0, smallGap, 0, smallGap)
        })
        accountPreview = TextView(this).apply { textSize = 14f; setPadding(0, smallGap, 0, smallGap) }
        root.addView(accountPreview)

        status = TextView(this).apply { textSize = 14f; setPadding(0, smallGap, 0, spacing) }
        root.addView(status)

        pickFolder = Button(this).apply {
            text = "직접 동기화 공용 폴더 선택… (추천)"
            setOnClickListener {
                if (Build.VERSION.SDK_INT < 30) {
                    status.text = "공용 폴더 연결은 Android 11 이상에서 사용할 수 있습니다."
                    return@setOnClickListener
                }
                if (!SharedBookshelfStorage.hasAccess()) {
                    pendingActionAfterPermission = "pick"
                    requestAllFilesPermission()
                } else {
                    folderPicker.launch(null)
                }
            }
        }
        root.addView(pickFolder)

        useDefaultFolder = Button(this).apply {
            text = "기본 공용 폴더 사용 (/Omyaru_Bookshelf)"
            setOnClickListener {
                if (Build.VERSION.SDK_INT < 30) {
                    status.text = "공용 폴더 연결은 Android 11 이상에서 사용할 수 있습니다."
                    return@setOnClickListener
                }
                if (!SharedBookshelfStorage.hasAccess()) {
                    pendingActionAfterPermission = "default"
                    requestAllFilesPermission()
                } else {
                    activateSelectedBaseFolder(File(Environment.getExternalStorageDirectory(), SharedBookshelfStorage.DIRECTORY))
                }
            }
        }
        root.addView(useDefaultFolder)

        usePrivateFolder = Button(this).apply {
            text = "공유 안 함 (이 앱 전용 저장소 사용)"
            setOnClickListener {
                SharedBookshelfStorage(this@BookshelfStorageActivity).markPrivateStorageSelected()
                status.text = "이 앱 전용 저장소 사용으로 설정했습니다."
                finishSetupIfNeeded()
            }
        }
        root.addView(usePrivateFolder)

        medium = Button(this).apply {
            text = "저장 매체 변경 (내부 저장소 / SD 카드)"
            setOnClickListener {
                val storage = SharedBookshelfStorage(this@BookshelfStorageActivity)
                val choices = storage.volumes()
                AlertDialog.Builder(this@BookshelfStorageActivity).setTitle("저장 매체 선택")
                    .setItems(choices.map { it.second }.toTypedArray()) { _, index ->
                        AlertDialog.Builder(this@BookshelfStorageActivity).setTitle("책장 전체 이동")
                            .setMessage("${choices[index].second}/Omyaru_Bookshelf로 옮깁니다. 복사한 원본을 모두 검증한 뒤 두 앱의 위치를 함께 바꿉니다.")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("이동") { _, _ -> perform { storage.moveTo(choices[index].first); "저장 매체를 변경했습니다." } }.show()
                    }.show()
            }
        }
        root.addView(medium)

        imageMode = Button(this).apply {
            setOnClickListener {
                val preferences = getSharedPreferences("bookshelf_sync", MODE_PRIVATE)
                val choices = arrayOf("로컬에 저장 (빠르게 열기)", "필요할 때 클라우드에서 불러오기")
                AlertDialog.Builder(this@BookshelfStorageActivity).setTitle("책장 사진 저장 방식")
                    .setSingleChoiceItems(choices, if (preferences.getString("image_mode", "local") == "on_demand") 1 else 0) { dialog, selected ->
                        preferences.edit().putString("image_mode", if (selected == 0) "local" else "on_demand").apply()
                        refresh(); dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        root.addView(imageMode)

        root.addView(TextView(this).apply {
            text = "두 방식 모두 Google Drive 동기화를 계속합니다. 로컬 저장은 사진을 기기에 내려받고, 필요할 때 불러오기는 사진을 열 때 받습니다. 이미 저장한 원본은 삭제하지 않습니다."
            setPadding(0, smallGap, 0, spacing)
        })
        root.addView(Button(this).apply { text = "돌아가기"; setOnClickListener { if (!busy) finish() } })
        root.gravity = Gravity.TOP
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun requestAllFilesPermission() {
        AlertDialog.Builder(this)
            .setTitle("모든 파일 접근 권한 필요")
            .setMessage("Omyaru 브라우저와 Umtomi가 같은 폴더에서 안전하게 파일 잠금 및 원본 공유를 하려면 ‘모든 파일 접근’ 권한이 필요합니다.\n\n권한을 허용한 뒤 돌아오면 바로 폴더 선택이 이어집니다.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingActionAfterPermission = null }
            .setPositiveButton("권한 설정 열기") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
            .show()
    }

    private fun activateSelectedBaseFolder(baseDir: File) {
        val accountEmail = CloudAccount.account(this)
        val accountSeg = SharedBookshelfStorage.sanitizeAccountFolderName(accountEmail)
        val previewPath = File(baseDir, accountSeg).absolutePath
        AlertDialog.Builder(this)
            .setTitle("이 폴더로 동기화할까요?")
            .setMessage(
                "기준 폴더:\n${baseDir.absolutePath}\n\n" +
                    "계정별 실제 저장 경로:\n$previewPath\n\n" +
                    "Omyaru 브라우저에서도 같은 기준 폴더를 선택하면 동일한 계정 폴더를 함께 사용하여 용량이 절약됩니다. 기존 로컬 원본은 SHA-256 검증 후 안전하게 병합됩니다.",
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("지정 및 연결") { _, _ ->
                perform {
                    val dest = SharedBookshelfStorage(this@BookshelfStorageActivity).activateFolder(
                        baseDir = baseDir,
                        accountEmail = accountEmail,
                        privateCatalog = File(filesDir, "bookshelf/catalog.json"),
                    )
                    "동기화 폴더 연결 완료!\n실제 경로: ${dest.absolutePath}"
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!busy) {
            refresh()
            val pending = pendingActionAfterPermission
            if (pending != null && SharedBookshelfStorage.hasAccess()) {
                pendingActionAfterPermission = null
                when (pending) {
                    "pick" -> folderPicker.launch(null)
                    "default" -> activateSelectedBaseFolder(File(Environment.getExternalStorageDirectory(), SharedBookshelfStorage.DIRECTORY))
                }
            }
        }
    }

    private fun refresh() {
        val storage = SharedBookshelfStorage(this)
        val email = CloudAccount.account(this).orEmpty()
        val accountLabel = if (email.isNotBlank()) email else "미연결 (연결 시 계정 폴더 자동 생성)"
        accountPreview.text = "현재 Google 계정: $accountLabel\n계정별 저장 경로: ${storage.accountFolderPreview(accountEmail = email)}"
        status.text = runCatching { storage.description(email) }.getOrElse { it.localizedMessage ?: "저장소 확인 필요" }
        pickFolder.isEnabled = !busy
        useDefaultFolder.isEnabled = !busy
        usePrivateFolder.isEnabled = !busy
        medium.isEnabled = !busy && storage.enabled && !storage.usesAccountSubfolder && SharedBookshelfStorage.hasAccess()
        medium.visibility = if (storage.usesAccountSubfolder) android.view.View.GONE else android.view.View.VISIBLE
        imageMode.isEnabled = !busy
        imageMode.text = "사진 저장 방식: " + if (getSharedPreferences("bookshelf_sync", MODE_PRIVATE)
                .getString("image_mode", "local") == "on_demand") "필요할 때 불러오기" else "로컬에 저장"
    }

    private fun finishSetupIfNeeded() {
        setResult(RESULT_OK)
        if (returnOkOnComplete) {
            finish()
        } else {
            refresh()
        }
    }

    private fun perform(action: () -> String) {
        if (busy) return
        busy = true
        pickFolder.isEnabled = false
        useDefaultFolder.isEnabled = false
        usePrivateFolder.isEnabled = false
        medium.isEnabled = false
        status.text = "원본을 확인하고 병합하는 중입니다. 완료될 때까지 기다려 주세요."
        worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                busy = false
                if (!isDestroyed) {
                    refresh()
                    if (result.isSuccess) {
                        status.append("\n\n" + result.getOrNull().orEmpty())
                        finishSetupIfNeeded()
                    } else {
                        status.append("\n\n" + (result.exceptionOrNull()?.localizedMessage ?: "이전하지 못했습니다. 기존 원본을 확인해 주세요."))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
