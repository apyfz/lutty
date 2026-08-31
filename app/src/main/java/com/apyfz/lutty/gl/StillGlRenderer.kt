package com.apyfz.lutty.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import com.apyfz.lutty.color.ColorProfiles
import com.apyfz.lutty.color.GradePipeline
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.media.RawDecoder
import com.apyfz.lutty.media.StillEngine
import com.apyfz.lutty.model.GradeState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a still through the exact same GLSL as video ([FRAGMENT_SHADER]), off-screen. The CPU
 * path was minutes per grade because of per-pixel trilinear LUT sampling; the GPU does it in a
 * single draw. Each call is self-contained — its own EGL context, program, textures and FBO — so
 * it is safe to invoke from any worker thread without shared GL state.
 */
object StillGlRenderer {

    const val TAG = "LuttyStillGl"

    /** Grades a float [img] (used for the interactive preview) into an ARGB_8888 bitmap. */
    fun render(img: StillEngine.LinearImage, grade: GradeState, luts: List<LutData>): Bitmap? =
        renderWith(img.width, img.height, grade, luts) { uploadFloat(img) }

    /**
     * Grades a 16-bit linear source directly (used for export). Uploading straight from the shorts
     * avoids building a full-resolution float copy, which is what made a 12 MP export OOM.
     */
    fun render(raw: RawDecoder.Linear, grade: GradeState, luts: List<LutData>): Bitmap? =
        renderWith(raw.width, raw.height, grade, luts) { uploadShort(raw) }

    /**
     * Grades a 16-bit source in horizontal strips into one full-resolution bitmap. The whole frame
     * as a single GL float texture would be hundreds of MB at 48 MP; a strip is a few MB, so this
     * exports at native resolution without running out of memory. One GL context and program are
     * reused across strips.
     */
    fun renderTiled(
        raw: RawDecoder.Linear, grade: GradeState, luts: List<LutData>, tileRows: Int = 512,
    ): Bitmap? {
        val w = raw.width
        val h = raw.height
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        var program: GlProgram? = null
        val lutTextures = mutableListOf<Lut3dTexture>()
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)
            val cfg = arrayOfNulls<EGLConfig>(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt_OPENGL_ES3_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
                ),
                0, cfg, 0, 1, IntArray(1), 0,
            )
            context = EGL14.eglCreateContext(
                display, cfg[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            surface = EGL14.eglCreatePbufferSurface(
                display, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            EGL14.eglMakeCurrent(display, surface, surface, context)

            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            val identity = GlUtil.create4x4IdentityMatrix()
            program.setFloatsUniform("uTransformationMatrix", identity)
            program.setFloatsUniform("uTexTransformationMatrix", identity)
            GLES20.glDisable(GLES20.GL_DITHER)
            program.use()

            val stack = luts.take(2).map { Lut3dTexture.upload(it) }
            lutTextures += stack
            val placeholder = Lut3dTexture.upload(LutData.identity(2))
            lutTextures += placeholder
            bindLut(program, grade, stack.getOrNull(0), placeholder, 0, "uLut0", 1)
            bindLut(program, grade, stack.getOrNull(1), placeholder, 1, "uLut1", 2)
            program.setIntUniform("uInputProfile", grade.input.shaderId)
            program.setIntUniform("uTargetProfile", grade.target.shaderId)
            val gamut = GradePipeline.gamutMatrixToTarget(grade.input, grade.target)
            program.setIntUniform("uGamutEnabled", if (gamut != null) 1 else 0)
            program.setFloatsUniform("uGamutMatrix", columnMajor(gamut ?: ColorProfiles.BT2020_TO_APPLE_WIDE_GAMUT))
            program.setFloatUniform("uExposure", grade.exposure)
            program.setFloatsUniform("uWhiteBalance", grade.whiteBalanceGain())
            program.setFloatUniform("uContrast", grade.contrast)
            program.setFloatUniform("uSaturation", grade.saturation)
            program.setIntUniform("uBypass", 0)

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val src = raw.rgb
            var y0 = 0
            while (y0 < h) {
                val bh = minOf(tileRows, h - y0)
                // Input texture for this strip only.
                val buf = ByteBuffer.allocateDirect(w * bh * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                var row = y0
                while (row < y0 + bh) {
                    var s = row * w * 3
                    var x = 0
                    while (x < w) {
                        buf.put((src[s].toInt() and 0xFFFF) / 65535f)
                        buf.put((src[s + 1].toInt() and 0xFFFF) / 65535f)
                        buf.put((src[s + 2].toInt() and 0xFFFF) / 65535f)
                        buf.put(1f)
                        s += 3; x++
                    }
                    row++
                }
                buf.position(0)
                val inTex = makeInputTexture(w, bh, buf)
                val outTex = IntArray(1); GLES20.glGenTextures(1, outTex, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outTex[0])
                GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, bh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                val fbo = IntArray(1); GLES20.glGenFramebuffers(1, fbo, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outTex[0], 0)
                GLES20.glViewport(0, 0, w, bh)
                program.setSamplerTexIdUniform("uTexSampler", inTex, 0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                val rb = ByteBuffer.allocateDirect(w * bh * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, w, bh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rb)
                rb.position(0)
                val strip = IntArray(w * bh)
                for (i in strip.indices) {
                    val r = rb.get().toInt() and 0xFF
                    val g = rb.get().toInt() and 0xFF
                    val b = rb.get().toInt() and 0xFF
                    val a = rb.get().toInt() and 0xFF
                    strip[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                bmp.setPixels(strip, 0, w, 0, y0, w, bh)

                GLES20.glDeleteTextures(2, intArrayOf(inTex, outTex[0]), 0)
                GLES20.glDeleteFramebuffers(1, fbo, 0)
                y0 += bh
            }
            return bmp
        } catch (e: Throwable) {
            Log.e(TAG, "tiled GL render failed", e)
            return null
        } finally {
            lutTextures.forEach { it.release() }
            try { program?.delete() } catch (_: Exception) {}
            if (display != null) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != null) EGL14.eglDestroySurface(display, surface)
                if (context != null) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun renderWith(
        w: Int, h: Int, grade: GradeState, luts: List<LutData>, uploadInput: () -> Int,
    ): Bitmap? {
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        var program: GlProgram? = null
        val cleanupTex = mutableListOf<Int>()
        val cleanupFbo = mutableListOf<Int>()
        val lutTextures = mutableListOf<Lut3dTexture>()
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)
            val cfg = arrayOfNulls<EGLConfig>(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt_OPENGL_ES3_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                ),
                0, cfg, 0, 1, IntArray(1), 0,
            )
            context = EGL14.eglCreateContext(
                display, cfg[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            surface = EGL14.eglCreatePbufferSurface(
                display, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            EGL14.eglMakeCurrent(display, surface, surface, context)

            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            val identity = GlUtil.create4x4IdentityMatrix()
            program.setFloatsUniform("uTransformationMatrix", identity)
            program.setFloatsUniform("uTexTransformationMatrix", identity)

            val inputTex = uploadInput()
            cleanupTex += inputTex

            // Output: RGBA8 colour texture backing a framebuffer, matching the 8-bit video output.
            val outTex = IntArray(1); GLES20.glGenTextures(1, outTex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outTex[0])
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            cleanupTex += outTex[0]
            val fbo = IntArray(1); GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outTex[0], 0)
            cleanupFbo += fbo[0]

            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_DITHER)
            program.use()

            val stack = luts.take(2).map { Lut3dTexture.upload(it) }
            lutTextures += stack
            val placeholder = Lut3dTexture.upload(LutData.identity(2))
            lutTextures += placeholder

            program.setSamplerTexIdUniform("uTexSampler", inputTex, 0)
            bindLut(program, grade, stack.getOrNull(0), placeholder, 0, "uLut0", 1)
            bindLut(program, grade, stack.getOrNull(1), placeholder, 1, "uLut1", 2)

            program.setIntUniform("uInputProfile", grade.input.shaderId)
            program.setIntUniform("uTargetProfile", grade.target.shaderId)
            val gamut = GradePipeline.gamutMatrixToTarget(grade.input, grade.target)
            program.setIntUniform("uGamutEnabled", if (gamut != null) 1 else 0)
            program.setFloatsUniform("uGamutMatrix", columnMajor(gamut ?: ColorProfiles.BT2020_TO_APPLE_WIDE_GAMUT))
            program.setFloatUniform("uExposure", grade.exposure)
            program.setFloatsUniform("uWhiteBalance", grade.whiteBalanceGain())
            program.setFloatUniform("uContrast", grade.contrast)
            program.setFloatUniform("uSaturation", grade.saturation)
            program.setIntUniform("uBypass", 0)

            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            return readback(w, h)
        } catch (e: Throwable) {
            Log.e(TAG, "still GL render failed", e)
            return null
        } finally {
            lutTextures.forEach { it.release() }
            if (cleanupTex.isNotEmpty()) GLES20.glDeleteTextures(cleanupTex.size, cleanupTex.toIntArray(), 0)
            if (cleanupFbo.isNotEmpty()) GLES20.glDeleteFramebuffers(cleanupFbo.size, cleanupFbo.toIntArray(), 0)
            try { program?.delete() } catch (_: Exception) {}
            if (display != null) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != null) EGL14.eglDestroySurface(display, surface)
                if (context != null) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun uploadFloat(img: StillEngine.LinearImage): Int {
        val src = img.rgb
        val buf = ByteBuffer.allocateDirect(img.width * img.height * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        var s = 0
        while (s < src.size) {
            buf.put(src[s]); buf.put(src[s + 1]); buf.put(src[s + 2]); buf.put(1f)
            s += 3
        }
        buf.position(0)
        return makeInputTexture(img.width, img.height, buf)
    }

    private fun uploadShort(raw: RawDecoder.Linear): Int {
        val src = raw.rgb
        val buf = ByteBuffer.allocateDirect(raw.width * raw.height * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        var s = 0
        while (s < src.size) {
            buf.put((src[s].toInt() and 0xFFFF) / 65535f)
            buf.put((src[s + 1].toInt() and 0xFFFF) / 65535f)
            buf.put((src[s + 2].toInt() and 0xFFFF) / 65535f)
            buf.put(1f)
            s += 3
        }
        buf.position(0)
        return makeInputTexture(raw.width, raw.height, buf)
    }

    private fun makeInputTexture(w: Int, h: Int, buf: FloatBuffer): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, buf)
        return ids[0]
    }

    private fun bindLut(
        program: GlProgram, grade: GradeState, tex: Lut3dTexture?, placeholder: Lut3dTexture,
        index: Int, sampler: String, texUnit: Int,
    ) {
        if (tex == null) {
            program.setSamplerTexIdUniform(sampler, placeholder.textureId, texUnit, GLES30.GL_TEXTURE_3D)
            program.setIntUniform("${sampler}Enabled", 0)
            program.setFloatUniform("${sampler}Strength", 0f)
            program.setFloatUniform("${sampler}Size", placeholder.size.toFloat())
            program.setFloatsUniform("${sampler}DomainMin", floatArrayOf(0f, 0f, 0f))
            program.setFloatsUniform("${sampler}DomainMax", floatArrayOf(1f, 1f, 1f))
            return
        }
        program.setSamplerTexIdUniform(sampler, tex.textureId, texUnit, GLES30.GL_TEXTURE_3D)
        program.setIntUniform("${sampler}Enabled", 1)
        program.setFloatUniform("${sampler}Strength", grade.luts.getOrNull(index)?.strength ?: 1f)
        program.setFloatUniform("${sampler}Size", tex.size.toFloat())
        program.setFloatsUniform("${sampler}DomainMin", tex.domainMin)
        program.setFloatsUniform("${sampler}DomainMax", tex.domainMax)
    }

    /** glReadPixels gives RGBA bytes, which is ARGB_8888's in-memory layout, so it copies straight
     *  into the bitmap. The input texture is stored bottom-up too, so no vertical flip is needed. */
    private fun readback(w: Int, h: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    /** EGL_OPENGL_ES3_BIT_KHR — not surfaced as a constant in android.opengl.EGL14. */
    private const val EGLExt_OPENGL_ES3_BIT = 0x0040
}
