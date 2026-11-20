package xyz.sabok.umtomi.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

internal object CaptureStitch {
    /** Append only newly exposed bottom rows; overlapping content must not be duplicated. */
    fun append(previous: Bitmap, next: Bitmap, moved: Int): Bitmap {
        require(previous.width == next.width && moved in 1..next.height)
        require(previous.width.toLong() * (previous.height.toLong() + moved) <= 12_000_000L)
        return Bitmap.createBitmap(previous.width, previous.height + moved, Bitmap.Config.ARGB_8888).also { result ->
            Canvas(result).apply {
                drawBitmap(previous, 0f, 0f, null)
                drawBitmap(next, Rect(0, next.height - moved, next.width, next.height),
                    Rect(0, previous.height, previous.width, result.height), null)
            }
        }
    }
}
