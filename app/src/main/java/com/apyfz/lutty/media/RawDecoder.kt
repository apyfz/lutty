package com.apyfz.lutty.media

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Develops a raw file (DNG and the other formats LibRaw supports) to 16-bit linear Rec.2020 in
 * native code. The linear data is fed into the grade pipeline as the [com.apyfz.lutty.model.Profile.RAW_LINEAR]
 * input, so a still can be encoded to any log curve and graded with the same LUT stack as video.
 */
object RawDecoder {

    const val TAG = "LuttyRaw"

    private var available = false

    init {
        available = try {
            System.loadLibrary("rawdev")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "native raw library missing", e); false
        }
    }

    /** A developed still: [width] x [height] pixels of linear Rec.2020, 3 shorts (unsigned) per pixel. */
    class Linear(val width: Int, val height: Int, val rgb: ShortArray)

    private external fun nativeDevelop(data: ByteArray, outWH: IntArray, maxEdge: Int): ShortArray?

    /**
     * Reads and develops [uri], or returns null if the file cannot be decoded. [maxEdge] caps the
     * long edge (0 = full): the downsample happens in native code, so a 48 MP sensor never lands a
     * ~292 MB array on the Java heap.
     */
    fun develop(context: Context, uri: Uri, maxEdge: Int = 0): Linear? {
        if (!available) return null
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "could not read $uri", e); null
        } ?: return null

        val wh = IntArray(2)
        val rgb = try {
            nativeDevelop(bytes, wh, maxEdge)
        } catch (e: Throwable) {
            Log.e(TAG, "native develop failed", e); null
        } ?: return null

        if (wh[0] <= 0 || wh[1] <= 0 || rgb.size < wh[0] * wh[1] * 3) return null
        return Linear(wh[0], wh[1], rgb)
    }
}
