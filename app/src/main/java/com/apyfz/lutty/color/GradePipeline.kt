package com.apyfz.lutty.color

import com.apyfz.lutty.model.GradeState
import com.apyfz.lutty.model.Profile
import java.util.Locale
import kotlin.math.pow

/**
 * The grade, evaluated on the CPU.
 *
 * This mirrors GradeShaderProgram's fragment shader step for step. It exists so thumbnails and
 * baked .cube exports show exactly what the GPU produces — if the two drift apart, previews start
 * lying. The unit tests pin them together.
 */
object GradePipeline {

    fun apply(grade: GradeState, luts: List<LutData>, rgb: FloatArray): FloatArray {
        // 1. to scene linear
        var lin = decodeToLinear(rgb, grade.input)

        // 2. exposure and white balance, in linear
        val gain = 2.0.pow(grade.exposure.toDouble()).toFloat()
        val wb = grade.whiteBalanceGain()
        lin = FloatArray(3) { lin[it] * gain * wb[it] }

        // 3. gamut. Only Apple Log 2 uses Apple Wide Gamut; everything else here is on BT.2020
        // primaries. Apple Log 2 is always the conversion target, so the reverse direction is
        // deliberately not implemented.
        if (grade.input != Profile.APPLE_LOG_2 && grade.target == Profile.APPLE_LOG_2) {
            val m = ColorProfiles.BT2020_TO_APPLE_WIDE_GAMUT
            val r = m[0] * lin[0] + m[1] * lin[1] + m[2] * lin[2]
            val g = m[3] * lin[0] + m[4] * lin[1] + m[5] * lin[2]
            val b = m[6] * lin[0] + m[7] * lin[1] + m[8] * lin[2]
            lin = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
        }

        // 4. back to the encoding the LUT stack expects
        var p = encodeFromLinear(lin, grade.target)

        // 5. LUT stack, in order, blended by strength
        luts.forEachIndexed { i, lut ->
            val strength = grade.luts.getOrNull(i)?.strength ?: 1f
            val out = lut.sample(
                p[0].coerceIn(0f, 1f), p[1].coerceIn(0f, 1f), p[2].coerceIn(0f, 1f),
            )
            p = FloatArray(3) { p[it] + (out[it] - p[it]) * strength }
        }

        // 6. contrast and saturation, in output space
        p = FloatArray(3) { (p[it] - 0.5f) * grade.contrast + 0.5f }
        val luma = 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]
        p = FloatArray(3) { luma + (p[it] - luma) * grade.saturation }

        return FloatArray(3) { p[it].coerceIn(0f, 1f) }
    }

    private fun decodeToLinear(c: FloatArray, profile: Profile): FloatArray = when (profile) {
        Profile.O_LOG -> FloatArray(3) { ColorProfiles.oLogDecode(c[it].toDouble()).toFloat() }
        Profile.APPLE_LOG, Profile.APPLE_LOG_2 ->
            FloatArray(3) { ColorProfiles.appleLogDecode(c[it].toDouble()).toFloat() }
        Profile.PASSTHROUGH -> c.copyOf()
    }

    private fun encodeFromLinear(l: FloatArray, profile: Profile): FloatArray = when (profile) {
        Profile.O_LOG -> FloatArray(3) { ColorProfiles.oLogEncode(l[it].toDouble()).toFloat() }
        Profile.APPLE_LOG, Profile.APPLE_LOG_2 ->
            FloatArray(3) { ColorProfiles.appleLogEncode(l[it].toDouble()).toFloat() }
        Profile.PASSTHROUGH -> l.copyOf()
    }

    /**
     * Bakes the entire grade into a single .cube, so it can be reused in a desktop grading tool.
     * Conversion, LUT stack and every slider collapse into one lookup.
     */
    fun bakeToCube(grade: GradeState, luts: List<LutData>, size: Int = 33, title: String): String {
        val sb = StringBuilder()
        sb.append("TITLE \"").append(title.replace('"', '\'')).append("\"\n")
        sb.append("LUT_3D_SIZE ").append(size).append("\n\n")
        val n = (size - 1).toFloat()
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val out = apply(grade, luts, floatArrayOf(r / n, g / n, b / n))
            sb.append(fmt(out[0])).append(' ').append(fmt(out[1])).append(' ').append(fmt(out[2])).append('\n')
        }
        return sb.toString()
    }

    // Locale.US, not the default: a comma decimal separator produces "0,500000", which every
    // .cube parser rejects, including this app's own.
    private fun fmt(v: Float): String = String.format(Locale.US, "%.6f", v)
}
