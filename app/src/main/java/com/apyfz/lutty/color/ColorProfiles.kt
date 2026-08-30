package com.apyfz.lutty.color

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Transfer functions and gamut matrices for the colour space transform.
 *
 * Every constant here comes from a primary source and is pinned by a unit test against values
 * cross-checked with colour-science 0.4.7. See tools/verify_color.py and the design spec.
 */
object ColorProfiles {

    // ---- OPPO O-Log -------------------------------------------------------
    // OPPO O-Log White Paper V1 (2025-10-22), sections 3.1 and 3.2.
    // Gamut is plain BT.2020 primaries at D65 (section 4).

    private const val O_GAMMA = 0.139
    private const val O_BETA = 0.019
    private const val O_DELTA = 0.614

    /** Scene reflectance (0.18 == 18% grey) to O-Log code value. */
    fun oLogEncode(r: Double): Double = O_GAMMA * ln(r + O_BETA) + O_DELTA

    /** O-Log code value to scene reflectance. */
    fun oLogDecode(p: Double): Double = exp((p - O_DELTA) / O_GAMMA) - O_BETA

    /**
     * The white paper's section 6 ACES CTL applies an extra /16 * 7.37235 on decode, which sits
     * about 1.12 stops below anchoring 18% grey at 0.18. That placement suits an ACES pipeline but
     * misplaces middle grey for LUTs built against the standard anchoring, so it is opt-in.
     */
    const val O_LOG_ACES_CTL_SCALE = 7.37235 / 16.0

    // ---- Apple Log --------------------------------------------------------
    // Constants from OpenColorIO src/OpenColorIO/transforms/builtins/AppleCameras.cpp,
    // which implements Apple's published Log profile. Apple Log 1 and Apple Log 2 share this
    // curve and differ only in gamut.

    private const val A_R0 = -0.05641088
    private const val A_RT = 0.01
    private const val A_C = 47.28711236
    private const val A_BETA = 0.00964052
    private const val A_GAMMA = 0.08550479
    private const val A_DELTA = 0.69336945
    val applePt: Double = A_C * (A_RT - A_R0).pow(2.0)

    fun appleLogEncode(r: Double): Double = when {
        r >= A_RT -> A_GAMMA * (ln(r + A_BETA) / ln(2.0)) + A_DELTA
        r >= A_R0 -> A_C * (r - A_R0).pow(2.0)
        else -> 0.0
    }

    fun appleLogDecode(p: Double): Double = when {
        p >= applePt -> 2.0.pow((p - A_DELTA) / A_GAMMA) - A_BETA
        p >= 0.0 -> sqrt(p / A_C) + A_R0
        else -> A_R0
    }

    // ---- Gamut ------------------------------------------------------------

    /**
     * BT.2020 (D65) to Apple Wide Gamut, composed from BT.2020 -> ACES AP0 under Bradford with the
     * inverse of the Apple Wide Gamut -> AP0 matrix ratified in OpenColorIO-Config-ACES issue 163.
     * Row sums are 1.0, so the matrix preserves neutrals.
     *
     * O-Gamut and Apple Log 1 both use BT.2020 primaries, so O-Log -> Apple Log 1 needs no matrix.
     */
    val BT2020_TO_APPLE_WIDE_GAMUT = doubleArrayOf(
        0.9750428995, -0.0768564900, 0.1018135904,
        0.0008445448, 0.8615375131, 0.1376179421,
        0.0198781607, 0.0492425795, 0.9308792597,
    )

    val APPLE_WIDE_GAMUT_TO_BT2020 = invert3x3(BT2020_TO_APPLE_WIDE_GAMUT)

    fun apply3x3(m: DoubleArray, rgb: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2],
        m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2],
        m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2],
    )

    fun invert3x3(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(det != 0.0) { "singular matrix" }
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    // ---- Full transforms --------------------------------------------------

    /** O-Log code values to Apple Log 2 code values, per the verified chain in the spec. */
    fun oLogToAppleLog2(rgb: DoubleArray): DoubleArray {
        val lin = doubleArrayOf(oLogDecode(rgb[0]), oLogDecode(rgb[1]), oLogDecode(rgb[2]))
        val awg = apply3x3(BT2020_TO_APPLE_WIDE_GAMUT, lin)
        return doubleArrayOf(appleLogEncode(awg[0]), appleLogEncode(awg[1]), appleLogEncode(awg[2]))
    }

    /** O-Log to Apple Log 1. Same gamut, so this is a pure curve swap. */
    fun oLogToAppleLog(rgb: DoubleArray): DoubleArray = DoubleArray(3) { k ->
        appleLogEncode(oLogDecode(rgb[k]))
    }
}
