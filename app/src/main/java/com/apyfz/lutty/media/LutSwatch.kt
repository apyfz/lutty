package com.apyfz.lutty.media

import android.graphics.Bitmap
import com.apyfz.lutty.color.ColorProfiles
import com.apyfz.lutty.color.GradePipeline
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.model.GradeState
import com.apyfz.lutty.model.Profile

/**
 * The tile shown for each LUT in the picker.
 *
 * A fixed synthetic chart rather than a frame of the clip: it reads at thumbnail size, it is the
 * same for every LUT so they can be compared, and it exists before any clip is loaded.
 *
 * The chart is built in scene-linear and then encoded into whichever profile the LUT expects, so
 * a log-to-709 LUT receives log values and its tile looks the way it will on real footage.
 */
object LutSwatch {

    private const val SIZE = 72

    /** Six hues over a grey ramp, plus a skin tone, which is where LUT differences show most. */
    private fun chartLinear(x: Float, y: Float): FloatArray {
        // Bottom third: neutral ramp from near black to diffuse white.
        if (y > 0.62f) {
            val v = 0.02f + x * 0.88f
            return floatArrayOf(v, v, v)
        }
        // Middle band: a skin tone, the most revealing single patch.
        if (y > 0.44f) return floatArrayOf(0.42f, 0.28f, 0.21f)

        // Top: saturated primaries and secondaries at mid brightness.
        val hues = arrayOf(
            floatArrayOf(0.50f, 0.06f, 0.06f), // red
            floatArrayOf(0.50f, 0.34f, 0.05f), // orange
            floatArrayOf(0.46f, 0.46f, 0.06f), // yellow
            floatArrayOf(0.08f, 0.42f, 0.14f), // green
            floatArrayOf(0.07f, 0.28f, 0.52f), // blue
            floatArrayOf(0.34f, 0.10f, 0.44f), // violet
        )
        return hues[(x * hues.size).toInt().coerceIn(0, hues.lastIndex)]
    }

    /**
     * @param target the encoding the LUT stack expects, so the chart is fed the right values.
     * @param candidate the LUT being previewed, or null for the ungraded chart.
     */
    fun render(target: Profile, grade: GradeState, stack: List<LutData>, candidate: LutData?): Bitmap {
        // Conversion is already accounted for by encoding the chart into the target profile, so
        // the preview grade treats input and target as the same.
        val previewGrade = grade.copy(
            inputProfile = target.name,
            targetProfile = target.name,
            luts = if (candidate == null) grade.luts.take(stack.size)
            else grade.luts.take(stack.size) + com.apyfz.lutty.model.LutSlot("preview", "preview", 1f),
        )
        val luts = if (candidate == null) stack else stack + candidate

        val pixels = IntArray(SIZE * SIZE)
        for (py in 0 until SIZE) {
            val y = py / (SIZE - 1f)
            for (px in 0 until SIZE) {
                val x = px / (SIZE - 1f)
                val lin = chartLinear(x, y)
                val encoded = FloatArray(3) { encode(lin[it], target) }
                val out = GradePipeline.apply(previewGrade, luts, encoded)
                pixels[py * SIZE + px] = (0xFF shl 24) or
                    ((out[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((out[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (out[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    private fun encode(linear: Float, profile: Profile): Float = when (profile) {
        Profile.O_LOG -> ColorProfiles.oLogEncode(linear.toDouble()).toFloat()
        Profile.APPLE_LOG, Profile.APPLE_LOG_2 -> ColorProfiles.appleLogEncode(linear.toDouble()).toFloat()
        // Treated as display-referred, so approximate the usual 709 transfer.
        Profile.PASSTHROUGH -> Math.pow(linear.toDouble().coerceAtLeast(0.0), 1.0 / 2.2).toFloat()
    }.coerceIn(0f, 1f)
}
