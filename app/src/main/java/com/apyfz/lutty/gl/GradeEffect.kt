package com.apyfz.lutty.gl

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.model.GradeState

/** Media3 effect wrapping the whole grade. */
@UnstableApi
class GradeEffect(private val controller: GradeController) : GlEffect {

    /** Convenience for a fixed grade, used by export where nothing changes mid-run. */
    constructor(grade: GradeState, luts: List<LutData>) : this(GradeController(grade, luts))

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        GradeShaderProgram(context, useHdr, controller)
}
