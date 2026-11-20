package xyz.sabok.umtomi.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.util.concurrent.Executors

class BookshelfStorageActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var medium: Button
    private lateinit var imageMode: Button
    private var busy = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val spacing = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(spacing, spacing * 2, spacing, spacing) }
        root.addView(TextView(this).apply { text = "공용 책장 저장소"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Omyaru Browser와 Umtomi가 같은 책장과 원본 사진을 사용합니다.\n\n위치는 저장 매체의 Omyaru_Bookshelf 폴더로 고정됩니다. 내부 저장소 또는 SD 카드만 선택할 수 있습니다.\n\nAndroid의 ‘모든 파일 접근’ 권한이 필요합니다. 책장 기능은 이 공용 폴더와 기존 앱 책장만 관리합니다. 각 앱에서 한 번씩 연결해 주세요."
            textSize = 16f; setPadding(0, spacing, 0, spacing)
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, spacing, 0, spacing) }; root.addView(status)
        connect = Button(this).apply { text = "공용 책장 연결"; setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT < 30) { status.text = "공용 폴더 연결은 Android 11 이상에서 사용할 수 있습니다."; return@setOnClickListener }
            if (!SharedBookshelfStorage.hasAccess()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else perform {
                SharedBookshelfStorage(this@BookshelfStorageActivity).activate(File(filesDir, "bookshelf/catalog.json"))
                "공용 책장에 연결했습니다. 기존 책장의 원본을 확인한 후 이전했습니다."
            }
        } }; root.addView(connect)
        medium = Button(this).apply { text = "저장 매체 변경"; setOnClickListener {
            val storage = SharedBookshelfStorage(this@BookshelfStorageActivity)
            val choices = storage.volumes()
            AlertDialog.Builder(this@BookshelfStorageActivity).setTitle("저장 매체 선택")
                .setItems(choices.map { it.second }.toTypedArray()) { _, index ->
                    AlertDialog.Builder(this@BookshelfStorageActivity).setTitle("책장 전체 이동")
                        .setMessage("${choices[index].second}/Omyaru_Bookshelf로 옮깁니다. 복사한 원본을 모두 검증한 뒤 두 앱의 위치를 함께 바꿉니다.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("이동") { _, _ -> perform { storage.moveTo(choices[index].first); "저장 매체를 변경했습니다." } }.show()
                }.show()
        } }; root.addView(medium)
        imageMode = Button(this).apply { setOnClickListener {
            val preferences = getSharedPreferences("bookshelf_sync", MODE_PRIVATE)
            val choices = arrayOf("로컬에 저장 (빠르게 열기)", "필요할 때 클라우드에서 불러오기")
            AlertDialog.Builder(this@BookshelfStorageActivity).setTitle("책장 사진 저장 방식")
                .setSingleChoiceItems(choices, if (preferences.getString("image_mode", "local") == "on_demand") 1 else 0) { dialog, selected ->
                    preferences.edit().putString("image_mode", if (selected == 0) "local" else "on_demand").apply()
                    refresh(); dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        } }; root.addView(imageMode)
        root.addView(TextView(this).apply {
            text = "두 방식 모두 Google Drive 동기화를 계속합니다. 로컬 저장은 사진을 기기에 내려받고, 필요할 때 불러오기는 사진을 열 때 받습니다. 이미 저장한 원본은 삭제하지 않습니다. 이 설정은 현재 앱에 적용됩니다."
            setPadding(0, 0, 0, spacing)
        })
        root.addView(Button(this).apply { text = "돌아가기"; setOnClickListener { if (!busy) finish() } })
        root.gravity = Gravity.TOP
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }
    override fun onResume() { super.onResume(); if (!busy) refresh() }
    private fun refresh() {
        val storage = SharedBookshelfStorage(this)
        status.text = runCatching { storage.description() }.getOrElse { it.localizedMessage ?: "저장소 확인 필요" }
        connect.isEnabled = !busy
        medium.isEnabled = !busy && storage.enabled && SharedBookshelfStorage.hasAccess()
        imageMode.isEnabled = !busy
        imageMode.text = "사진 저장 방식: " + if (getSharedPreferences("bookshelf_sync", MODE_PRIVATE)
                .getString("image_mode", "local") == "on_demand") "필요할 때 불러오기" else "로컬에 저장"
    }
    private fun perform(action: () -> String) {
        if (busy) return
        busy = true; connect.isEnabled = false; medium.isEnabled = false; status.text = "원본을 확인하고 있습니다. 완료될 때까지 기다려 주세요."
        worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                busy = false
                if (!isDestroyed) { refresh(); status.append("\n\n" + result.getOrElse { it.localizedMessage ?: "이전하지 못했습니다. 기존 원본을 확인해 주세요." }) }
            }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
