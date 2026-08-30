package com.apyfz.lutty.gl

import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.model.GradeState

/**
 * Live link between the UI and the running shader.
 *
 * The shader program is built once and reads these fields on every frame, so moving a slider
 * costs nothing more than a uniform write. Rebuilding the effect chain per change would re-upload
 * the 3D LUT texture — 2.2 MB for a 65-cube — and stall the preview.
 *
 * [lutGeneration] changes only when the set of LUTs changes, which is the one case that genuinely
 * requires new textures.
 */
class GradeController(grade: GradeState = GradeState.NEUTRAL, luts: List<LutData> = emptyList()) {

    @Volatile var grade: GradeState = grade
        private set

    @Volatile var luts: List<LutData> = luts
        private set

    @Volatile var lutGeneration: Int = 0
        private set

    /** While true the shader passes the frame through untouched, for press-and-hold compare. */
    @Volatile var bypass: Boolean = false

    /** Parameter-only update. Takes effect on the next rendered frame, no GL work. */
    fun updateGrade(newGrade: GradeState) {
        grade = newGrade
    }

    /** Replaces the LUT textures. Only call this when the actual LUT files change. */
    fun updateLuts(newGrade: GradeState, newLuts: List<LutData>) {
        grade = newGrade
        luts = newLuts
        lutGeneration++
    }
}
