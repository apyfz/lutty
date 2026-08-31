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

    // ---- RED Log3G10 (v3) -------------------------------------------------
    // "White Paper on REDWideGamutRGB and Log3G10" (RED Digital Cinema, 2017), the curve RED's
    // IPP2 pipeline and .R3D files use. Constants cross-checked against colour-science's
    // log_(en/de)coding_Log3G10 method='v3'. Values are scene reflectance, so 0.18 == 18% grey.

    private const val G10_A = 0.224282
    private const val G10_B = 155.975327
    private const val G10_C = 0.01
    private const val G10_G = 15.1927

    fun log3g10Encode(r: Double): Double {
        val x = r + G10_C
        return if (x < 0.0) x * G10_G else G10_A * (ln(x * G10_B + 1.0) / ln(10.0))
    }

    fun log3g10Decode(p: Double): Double =
        if (p < 0.0) p / G10_G - G10_C else (10.0.pow(p / G10_A) - 1.0) / G10_B - G10_C

    // ---- Nikon N-Log ------------------------------------------------------
    // Nikon "N-Log Specification Document" version 1.0. Constants cross-checked against
    // colour-science's log_(en/de)coding_NLog (reflection input, normalised code-value output).
    // N-Gamut is BT.2020 primaries at D65, so N-Log -> Apple Log 2 reuses the BT.2020 matrix.

    private const val NLOG_CUT1 = 0.328
    private const val NLOG_CUT2 = 0.4418377321603128
    private const val NLOG_A = 0.635386119257087
    private const val NLOG_B = 0.0075
    private const val NLOG_C = 0.1466275659824047
    private const val NLOG_D = 0.6050830889540567

    fun nLogEncode(r: Double): Double =
        if (r < NLOG_CUT1) NLOG_A * (r + NLOG_B).pow(1.0 / 3.0) else NLOG_C * ln(r) + NLOG_D

    fun nLogDecode(p: Double): Double =
        if (p < NLOG_CUT2) (p / NLOG_A).pow(3.0) - NLOG_B else exp((p - NLOG_D) / NLOG_C)

    // ---- Fujifilm F-Log2 --------------------------------------------------
    // Fujifilm "F-Log2 Data Sheet" (2022). Constants cross-checked against colour-science's
    // log_(en/de)coding_FLog2 (reflection input, normalised code-value output). F-Gamut is
    // BT.2020 primaries at D65, so F-Log2 -> Apple Log 2 reuses the BT.2020 matrix.

    private const val FL2_CUT1 = 0.000889
    private const val FL2_CUT2 = 0.100686685370811
    private const val FL2_A = 5.555556
    private const val FL2_B = 0.064829
    private const val FL2_C = 0.245281
    private const val FL2_D = 0.384316
    private const val FL2_E = 8.799461
    private const val FL2_F = 0.092864

    fun fLog2Encode(r: Double): Double =
        if (r < FL2_CUT1) FL2_E * r + FL2_F else FL2_C * (ln(FL2_A * r + FL2_B) / ln(10.0)) + FL2_D

    fun fLog2Decode(p: Double): Double =
        if (p < FL2_CUT2) (p - FL2_F) / FL2_E else (10.0.pow((p - FL2_D) / FL2_C) - FL2_B) / FL2_A

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

    /**
     * REDWideGamutRGB (D65) to Apple Wide Gamut, composed the same way as the BT.2020 matrix above:
     * REDWideGamutRGB -> ACES AP0 under Bradford, then the inverse of the Apple Wide Gamut -> AP0
     * matrix from OpenColorIO-Config-ACES issue 163. Row sums are 1.0 (neutral-preserving). Used
     * for RED Log3G10 (.R3D) footage, whose primaries are REDWideGamutRGB rather than BT.2020.
     */
    val RED_WIDE_GAMUT_TO_APPLE_WIDE_GAMUT = doubleArrayOf(
        1.1455528684, -0.2293505398, 0.0837960327,
        -0.0333742668, 1.0799487937, -0.0465739624,
        -0.0471347145, -0.2743408983, 1.3214758140,
    )

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

    /** RED Log3G10 (REDWideGamutRGB) code values to Apple Log 2 code values. */
    fun redLog3G10ToAppleLog2(rgb: DoubleArray): DoubleArray {
        val lin = doubleArrayOf(log3g10Decode(rgb[0]), log3g10Decode(rgb[1]), log3g10Decode(rgb[2]))
        val awg = apply3x3(RED_WIDE_GAMUT_TO_APPLE_WIDE_GAMUT, lin)
        return doubleArrayOf(appleLogEncode(awg[0]), appleLogEncode(awg[1]), appleLogEncode(awg[2]))
    }

    /** Nikon N-Log (BT.2020) code values to Apple Log 2 code values. */
    fun nLogToAppleLog2(rgb: DoubleArray): DoubleArray {
        val lin = doubleArrayOf(nLogDecode(rgb[0]), nLogDecode(rgb[1]), nLogDecode(rgb[2]))
        val awg = apply3x3(BT2020_TO_APPLE_WIDE_GAMUT, lin)
        return doubleArrayOf(appleLogEncode(awg[0]), appleLogEncode(awg[1]), appleLogEncode(awg[2]))
    }

    /** Fujifilm F-Log2 (F-Gamut == BT.2020) code values to Apple Log 2 code values. */
    fun fLog2ToAppleLog2(rgb: DoubleArray): DoubleArray {
        val lin = doubleArrayOf(fLog2Decode(rgb[0]), fLog2Decode(rgb[1]), fLog2Decode(rgb[2]))
        val awg = apply3x3(BT2020_TO_APPLE_WIDE_GAMUT, lin)
        return doubleArrayOf(appleLogEncode(awg[0]), appleLogEncode(awg[1]), appleLogEncode(awg[2]))
    }
}
