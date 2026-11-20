package xyz.sabok.umtomi.sync

import android.Manifest
import android.accounts.Account
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.sync.shared.SharedShelfSync

class BookshelfSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT < 26) return Result.success()
        val context = applicationContext
        val preferences = CloudAccount.preferences(context)
        val account = CloudAccount.account(context) ?: return Result.success()
        if (!preferences.getBoolean(ENABLE_KEY, false)) return Result.success()
        val notice = BookshelfSyncNotice(context, background = true)
        return try {
            withTimeout(8 * 60_000L) {
                runInterruptible(Dispatchers.IO) {
                    fun valid() = !Thread.currentThread().isInterrupted &&
                        preferences.getBoolean(ENABLE_KEY, false) && CloudAccount.account(context) == account
                    check(valid())
                    notice.report(context.getString(R.string.workspace_drive_checking))
                    val request = AuthorizationRequest.builder().setAccount(Account(account, "com.google"))
                        .setRequestedScopes(listOf(Scope(DriveAppDataClient.SCOPE)))
                        .setOptOutIncludingGrantedScopes(true).build()
                    val authorization = Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 25, TimeUnit.SECONDS)
                    val token = authorization.accessToken?.takeIf { !authorization.hasResolution() &&
                        DriveAppDataClient.SCOPE in authorization.grantedScopes }
                    if (token == null) {
                        notice.report(context.getString(R.string.workspace_drive_reconnect), finished = true)
                        return@runInterruptible
                    }
                    SharedShelfSync.run(context, account, { token }, progress = { notice.report(it) }, valid = ::valid)
                    if (valid()) notice.report(context.getString(R.string.workspace_drive_complete), finished = true)
                }
            }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            notice.report(context.getString(R.string.workspace_drive_retry), finished = true)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            notice.clear()
            throw cancelled
        } catch (_: Exception) {
            notice.report(context.getString(R.string.workspace_drive_retry), finished = true)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        const val ENABLE_KEY = "background_bookshelf_sync"
        private const val WORK_NAME = "umtomi.bookshelf.periodic"

        internal fun updateSchedule(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = WorkManager.getInstance(context)
            if (CloudAccount.account(context) == null || !CloudAccount.preferences(context).getBoolean(ENABLE_KEY, false)) {
                manager.cancelUniqueWork(WORK_NAME)
                BookshelfSyncNotice(context, background = true).clear()
                return
            }
            val request = PeriodicWorkRequestBuilder<BookshelfSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

internal class BookshelfSyncNotice(private val context: Context, private val background: Boolean) {
    companion object {
        const val ENABLE_KEY = "bookshelf_sync_notifications"
        private const val CHANNEL = "umtomi.bookshelf.sync"
        private const val MANUAL_ID = 74132
        private const val BACKGROUND_ID = 74133

        fun clear(context: Context) {
            NotificationManagerCompat.from(context).cancel(MANUAL_ID)
            NotificationManagerCompat.from(context).cancel(BACKGROUND_ID)
        }
    }

    private val progressPattern = Regex("""(\d{1,3})%""")
    private var last = 0L
    private val id get() = if (background) BACKGROUND_ID else MANUAL_ID

    fun report(text: String, finished: Boolean = false) {
        if (!CloudAccount.preferences(context).getBoolean(ENABLE_KEY, false)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!finished && last != 0L && now - last < 4_000L) return
        last = now
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.workspace_drive_sync), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val percent = progressPattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
        val intent = android.content.Intent(context, xyz.sabok.umtomi.ui.DriveSyncActivity::class.java)
        val destination = android.app.PendingIntent.getActivity(context, id, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(if (background) R.string.workspace_drive_background else R.string.workspace_drive_sync))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(destination)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setAutoCancel(finished)
        if (!finished) notification.setProgress(100, percent ?: 0, percent == null)
        try { manager.notify(id, notification.build()) } catch (_: SecurityException) { }
    }

    fun clear() = NotificationManagerCompat.from(context).cancel(id)
}
