package com.apyfz.lutty.model

import kotlinx.serialization.Serializable

/** Which curve/gamut a clip is in, and which one the LUT stack expects. */
enum class Profile(val shaderId: Int, val label: String) {
    PASSTHROUGH(0, "As shot (no conversion)"),
    O_LOG(1, "OPPO O-Log"),
    APPLE_LOG(2, "Apple Log"),
    APPLE_LOG_2(3, "Apple Log 2"),
}

@Serializable
data class LutSlot(
    val lutId: String,
    val name: String,
    val strength: Float = 1f,
)

/** A complete grade. This is what a saved preset contains. */
@Serializable
data class GradeState(
    val inputProfile: String = Profile.PASSTHROUGH.name,
    val targetProfile: String = Profile.PASSTHROUGH.name,
    val luts: List<LutSlot> = emptyList(),
    /** Stops. 0 = unchanged. Applied in scene-linear, before the LUT stack. */
    val exposure: Float = 0f,
    /** -1..1, warm positive. Applied in scene-linear, before the LUT stack. */
    val temperature: Float = 0f,
    /** -1..1, magenta positive. Applied in scene-linear, before the LUT stack. */
    val tint: Float = 0f,
    /** 1 = unchanged. Applied after the LUT stack. */
    val contrast: Float = 1f,
    /** 1 = unchanged. Applied after the LUT stack. */
    val saturation: Float = 1f,
) {
    val input: Profile get() = Profile.valueOf(inputProfile)
    val target: Profile get() = Profile.valueOf(targetProfile)

    /**
     * Per-channel linear gain for white balance. Temperature trades red against blue, tint trades
     * green against magenta. Normalised so that a neutral setting is exactly 1,1,1.
     */
    fun whiteBalanceGain(): FloatArray {
        val r = 1f + 0.30f * temperature
        val b = 1f - 0.30f * temperature
        val g = 1f - 0.20f * tint
        return floatArrayOf(r, g, b)
    }

    /**
     * Whether anything worth carrying to another clip has been set. Profiles are excluded because
     * those describe the footage rather than the look, and are re-detected per clip.
     */
    fun hasEdits(): Boolean =
        luts.isNotEmpty() || exposure != 0f || temperature != 0f || tint != 0f ||
            contrast != 1f || saturation != 1f

    companion object {
        /** Grade that must leave every pixel untouched. Used by the transparency check. */
        val NEUTRAL = GradeState()
    }
}
