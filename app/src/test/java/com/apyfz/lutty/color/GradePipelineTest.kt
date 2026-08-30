package com.apyfz.lutty.color

import com.apyfz.lutty.model.GradeState
import com.apyfz.lutty.model.LutSlot
import com.apyfz.lutty.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CPU pipeline drives LUT thumbnails and the baked .cube export, so it has to agree with the
 * shader. These pin the behaviours that would silently make previews lie.
 */
class GradePipelineTest {

    private val neutral = GradeState.NEUTRAL

    @Test fun `a neutral grade with no luts changes nothing`() {
        for (v in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            val out = GradePipeline.apply(neutral, emptyList(), floatArrayOf(v, v, v))
            assertEquals(v, out[0], 1e-6f); assertEquals(v, out[1], 1e-6f); assertEquals(v, out[2], 1e-6f)
        }
    }

    @Test fun `an identity lut changes nothing`() {
        val grade = neutral.copy(luts = listOf(LutSlot("id", "id", 1f)))
        val out = GradePipeline.apply(grade, listOf(LutData.identity(33)), floatArrayOf(0.4f, 0.6f, 0.8f))
        assertEquals(0.4f, out[0], 1e-4f); assertEquals(0.6f, out[1], 1e-4f); assertEquals(0.8f, out[2], 1e-4f)
    }

    @Test fun `decode and encode cancel when input and target match`() {
        for (p in listOf(Profile.O_LOG, Profile.APPLE_LOG, Profile.APPLE_LOG_2)) {
            val grade = neutral.copy(inputProfile = p.name, targetProfile = p.name)
            val out = GradePipeline.apply(grade, emptyList(), floatArrayOf(0.5f, 0.5f, 0.5f))
            assertEquals("round trip through $p", 0.5f, out[0], 1e-4f)
        }
    }

    @Test fun `o-log to apple log 2 keeps 18 percent grey neutral`() {
        val grey = ColorProfiles.oLogEncode(0.18).toFloat()
        val grade = neutral.copy(
            inputProfile = Profile.O_LOG.name,
            targetProfile = Profile.APPLE_LOG_2.name,
        )
        val out = GradePipeline.apply(grade, emptyList(), floatArrayOf(grey, grey, grey))
        for (k in 0..2) assertEquals(0.4882725f, out[k], 1e-4f)
    }

    @Test fun `lut strength blends linearly between ungraded and full`() {
        // A LUT that maps everything to black makes the blend easy to reason about.
        val black = LutData(2, FloatArray(2 * 2 * 2 * 3))
        val half = neutral.copy(luts = listOf(LutSlot("k", "k", 0.5f)))
        val out = GradePipeline.apply(half, listOf(black), floatArrayOf(0.8f, 0.8f, 0.8f))
        for (k in 0..2) assertEquals(0.4f, out[k], 1e-4f)
    }

    @Test fun `exposure of one stop doubles scene light`() {
        val grade = neutral.copy(
            inputProfile = Profile.O_LOG.name, targetProfile = Profile.O_LOG.name, exposure = 1f,
        )
        val input = ColorProfiles.oLogEncode(0.18).toFloat()
        val out = GradePipeline.apply(grade, emptyList(), floatArrayOf(input, input, input))
        assertEquals(ColorProfiles.oLogEncode(0.36).toFloat(), out[0], 1e-4f)
    }

    @Test fun `saturation of zero produces a neutral grey`() {
        val grade = neutral.copy(saturation = 0f)
        val out = GradePipeline.apply(grade, emptyList(), floatArrayOf(0.9f, 0.2f, 0.4f))
        assertEquals(out[0], out[1], 1e-6f); assertEquals(out[1], out[2], 1e-6f)
    }

    @Test fun `baked cube is a well formed identity when the grade is neutral`() {
        val text = GradePipeline.bakeToCube(neutral, emptyList(), 9, "test")
        val parsed = CubeParser.parse(text)
        assertTrue("bake did not reparse: $parsed", parsed is CubeResult.Ok)
        val lut = (parsed as CubeResult.Ok).lut
        assertEquals(9, lut.size)
        for (v in listOf(0f, 0.5f, 1f)) {
            val s = lut.sample(v, v, v)
            assertEquals(v, s[0], 1e-3f)
        }
    }

    @Test fun `baked cube reproduces the grade it was baked from`() {
        val grade = neutral.copy(
            inputProfile = Profile.O_LOG.name,
            targetProfile = Profile.APPLE_LOG_2.name,
            contrast = 1.2f,
            saturation = 0.8f,
        )
        val baked = (CubeParser.parse(
            GradePipeline.bakeToCube(grade, emptyList(), 33, "test")
        ) as CubeResult.Ok).lut

        for (v in listOf(0.2f, 0.45f, 0.7f)) {
            val direct = GradePipeline.apply(grade, emptyList(), floatArrayOf(v, v, v))
            val viaCube = baked.sample(v, v, v)
            // A 33-point grid interpolates, so allow a small tolerance rather than exactness.
            assertEquals("channel r at $v", direct[0], viaCube[0], 5e-3f)
            assertEquals("channel g at $v", direct[1], viaCube[1], 5e-3f)
            assertEquals("channel b at $v", direct[2], viaCube[2], 5e-3f)
        }
    }
}
