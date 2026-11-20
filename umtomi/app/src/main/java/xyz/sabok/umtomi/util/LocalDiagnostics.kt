package xyz.sabok.umtomi.util

import android.util.Log

/** Diagnostics remain on the device; no upstream service account is required. */
object LocalDiagnostics {
    fun recordException(error: Throwable) { Log.e("Umtomi", "Operation failed", error) }
    fun setUserId(id: String) = Unit
    fun setCustomKey(key: String, value: Int) = Unit
}
