package com.apyfz.lutty.color

import com.apyfz.lutty.model.GradeState
import com.apyfz.lutty.model.LutSlot
import com.apyfz.lutty.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test fun `a domain above one resolves highlights without being clamped first`() {
        // The shader normalises the raw value then clamps; the CPU must not clamp beforehand, or
        // a LUT declaring DOMAIN_MAX above 1 would grade highlights differently in the preview.
        val n = 2
        val rgb = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            rgb[i++] = r.toFloat(); rgb[i++] = g.toFloat(); rgb[i++] = b.toFloat()
        }
        val lut = LutData(n, rgb, domainMax = floatArrayOf(2f, 2f, 2f))
        // 1.5 sits three quarters up a 0..2 domain, not at the top as a pre-clamp would make it.
        assertEquals(0.75f, lut.sample(1.5f, 1.5f, 1.5f)[0], 1e-5f)
    }

    @Test fun `gamut conversion runs only into apple log 2`() {
        // Apple Log 2 is the only profile on Apple Wide Gamut and is always the target, so the
        // matrix must apply going in and never coming back out.
        val grey = ColorProfiles.oLogEncode(0.18).toFloat()
        val into = GradePipeline.apply(
            neutral.copy(inputProfile = Profile.O_LOG.name, targetProfile = Profile.APPLE_LOG_2.name),
            emptyList(), floatArrayOf(grey, grey, grey),
        )
        // Neutral in, neutral out: the matrix preserves greys.
        assertEquals(into[0], into[1], 1e-5f)

        // Same profile on both sides is a straight round trip, no matrix.
        val same = GradePipeline.apply(
            neutral.copy(inputProfile = Profile.APPLE_LOG_2.name, targetProfile = Profile.APPLE_LOG_2.name),
            emptyList(), floatArrayOf(0.5f, 0.4f, 0.3f),
        )
        assertEquals(0.5f, same[0], 1e-4f)
        assertEquals(0.4f, same[1], 1e-4f)
        assertEquals(0.3f, same[2], 1e-4f)
    }

    @Test fun `baked cube uses a dot decimal separator whatever the device locale`() {
        val original = Locale.getDefault()
        try {
            // Germany formats 0.5 as "0,5", which every .cube parser rejects.
            Locale.setDefault(Locale.GERMANY)
            val text = GradePipeline.bakeToCube(neutral, emptyList(), 3, "locale")
            assertTrue("comma separator leaked into the cube", !text.contains(","))
            assertTrue("expected dot separated values", text.contains("0.5"))
            // And it must survive a round trip through our own parser.
            assertTrue(CubeParser.parse(text) is CubeResult.Ok)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `domain bounds remap the input before the lookup`() {
        // A LUT declaring DOMAIN 0..2 must treat 2.0 as its top entry, not 1.0. The shader
        // applies the same normalisation, so preview and swatch agree.
        val n = 2
        val rgb = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            rgb[i++] = r.toFloat(); rgb[i++] = g.toFloat(); rgb[i++] = b.toFloat()
        }
        val lut = LutData(
            n, rgb,
            domainMin = floatArrayOf(0f, 0f, 0f),
            domainMax = floatArrayOf(2f, 2f, 2f),
        )
        // Half way up the domain is 1.0, which should land mid-way between the two entries.
        val mid = lut.sample(1f, 1f, 1f)
        assertEquals(0.5f, mid[0], 1e-5f)
        // The top of the domain is 2.0, not 1.0.
        val top = lut.sample(2f, 2f, 2f)
        assertEquals(1.0f, top[0], 1e-5f)
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
