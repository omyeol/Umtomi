package xyz.sabok.umtomi.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object PortAppLock : Application.ActivityLifecycleCallbacks {
    private var visibleActivities = 0
    private var backgroundAt: Long? = null
    var unlocked = false
        private set

    private fun credential(context: Context) = AtomicFile(File(context.noBackupFilesDir, "app-password"))
    fun enabled(context: Context) = credential(context).baseFile.exists()

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    fun save(context: Context, password: CharArray) {
        val salt = ByteArray(32).also(SecureRandom()::nextBytes)
        val verifier = derive(password, salt)
        val file = credential(context)
        val stream = file.startWrite()
        try {
            val record = Base64.encodeToString(salt + verifier, Base64.NO_WRAP)
            stream.write(record.toByteArray(Charsets.US_ASCII))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        } finally {
            verifier.fill(0)
        }
    }

    fun matches(context: Context, password: CharArray): Boolean = runCatching {
        val bytes = Base64.decode(credential(context).readFully(), Base64.NO_WRAP)
        if (bytes.size != 64) return@runCatching false
        val supplied = derive(password, bytes.copyOfRange(0, 32))
        try { MessageDigest.isEqual(supplied, bytes.copyOfRange(32, 64)) }
        finally { supplied.fill(0) }
    }.getOrDefault(false)

    fun remove(context: Context) {
        credential(context).delete()
        Preferences["port_lock_biometric"] = false
        unlocked = false
    }

    fun authorize() { unlocked = true }
    fun requireUnlock() { unlocked = false }

    override fun onActivityStarted(activity: Activity) {
        if (visibleActivities++ != 0) return
        val stopped = backgroundAt ?: return
        backgroundAt = null
        val delay = if (Preferences["port_lock_immediately", true]) 0 else Preferences["port_lock_minutes", 5].coerceIn(1, 1440)
        if (SystemClock.elapsedRealtime() - stopped >= delay * 60_000L) requireUnlock()
    }

    override fun onActivityStopped(activity: Activity) {
        if (--visibleActivities == 0 && !activity.isChangingConfigurations) backgroundAt = SystemClock.elapsedRealtime()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
