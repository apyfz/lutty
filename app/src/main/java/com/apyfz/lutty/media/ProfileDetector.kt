package com.apyfz.lutty.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.apyfz.lutty.color.ColorProfiles
import com.apyfz.lutty.model.Profile
import kotlin.math.min

/**
 * Guesses which log curve a clip was shot in.
 *
 * Two independent signals are combined:
 *
 * 1. Container colour tags. O-Log is BT.2020 primaries at full range with an SDR transfer, which
 *    is an unusual combination. Blackmagic writes Apple Log with an unspecified transfer.
 *
 * 2. The black floor. Each curve has a mathematical minimum that the signal cannot go below:
 *    O-Log encodes zero reflectance at 0.0631, Apple Log at 0.1505, and Rec.709 at 0. Since it is
 *    a lower bound rather than a measurement, a dark corner of the frame identifies the curve.
 *
 * The floor test only narrows things down when the frame actually contains shadows, so the result
 * carries a confidence and the user can always override it.
 */
object ProfileDetector {

    const val TAG = "LuttyDetect"

    /**
     * [reason] is plain language for the interface. The measurements behind the decision go to the
     * log instead, so the app never explains itself in black-floor numbers.
     */
    data class Result(val profile: Profile, val confident: Boolean, val reason: String)

    private val O_LOG_FLOOR = ColorProfiles.oLogEncode(0.0)        // 0.0631
    private val APPLE_FLOOR = ColorProfiles.appleLogEncode(0.0)    // 0.1505

    fun detect(context: Context, uri: Uri): Result {
        val tags = readColorTags(context, uri)
        val floor = measureBlackFloor(context, uri)
        Log.i(TAG, "tags=$tags floor=$floor (O-Log floor $O_LOG_FLOOR, Apple floor $APPLE_FLOOR)")

        // Blackmagic and Apple write Apple Log with no transfer function declared, because Apple
        // Log has no standard CICP code.
        val transferUnspecified = tags.transfer == null || tags.transfer == 0


        if (floor != null) {
            // Half way between the two floors, so a noisy measurement still lands on the right side.
            val appleThreshold = (O_LOG_FLOOR + APPLE_FLOOR) / 2.0   // ~0.107
            when {
                floor >= appleThreshold && transferUnspecified ->
                    return Result(Profile.APPLE_LOG_2, true, "Recognised from the file")
                floor >= appleThreshold ->
                    return Result(Profile.APPLE_LOG_2, false, "Best guess. Change it if the picture looks wrong")
                floor >= O_LOG_FLOOR - 0.02 && tags.fullRange && tags.bt2020 ->
                    return Result(Profile.O_LOG, true, "Recognised from the file")
                floor < 0.03 ->
                    return Result(Profile.PASSTHROUGH, true, "Already graded, no conversion needed")
            }
        }

        // Fall back to tags alone.
        return when {
            tags.fullRange && tags.bt2020 ->
                Result(Profile.O_LOG, false, "Best guess. Change it if the picture looks wrong")
            tags.bt2020 && transferUnspecified ->
                Result(Profile.APPLE_LOG_2, false, "Best guess. Change it if the picture looks wrong")
            else ->
                Result(Profile.PASSTHROUGH, false, "Could not tell. Pick the format yourself")
        }
    }

    private data class Tags(val bt2020: Boolean, val fullRange: Boolean, val transfer: Int?)

    private fun readColorTags(context: Context, uri: Uri): Tags {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var result = Tags(false, false, null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                val standard = f.getIntOrNull(MediaFormat.KEY_COLOR_STANDARD)
                val range = f.getIntOrNull(MediaFormat.KEY_COLOR_RANGE)
                result = Tags(
                    bt2020 = standard == MediaFormat.COLOR_STANDARD_BT2020,
                    fullRange = range == MediaFormat.COLOR_RANGE_FULL,
                    transfer = f.getIntOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                )
                break
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "could not read colour tags", e); Tags(false, false, null)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    /** Lowest luma in a sampled frame, normalised 0..1, or null if no frame could be read. */
    private fun measureBlackFloor(context: Context, uri: Uri): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame: Bitmap = retriever.getFrameAtTime(0) ?: return null
            val w = min(frame.width, 320)
            val h = min(frame.height, 320)
            val small = Bitmap.createScaledBitmap(frame, w, h, true)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            frame.recycle()
            if (small != frame) small.recycle()

            // Use a low percentile rather than the true minimum, so one dead pixel or a black
            // letterbox edge cannot decide the answer.
            val luma = pixels.map { p ->
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                (0.2627 * r + 0.6780 * g + 0.0593 * b) / 255.0
            }.sorted()
            luma[(luma.size * 0.01).toInt()]
        } catch (e: Exception) {
            Log.w(TAG, "could not sample a frame", e); null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
