package com.apyfz.lutty.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import com.apyfz.lutty.color.ColorProfiles
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.model.GradeState
import com.apyfz.lutty.model.Profile

private const val VERTEX_SHADER = """#version 300 es
in vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
out vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,
                              aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
precision highp sampler3D;

uniform sampler2D uTexSampler;
uniform sampler3D uLut0;
uniform sampler3D uLut1;
uniform int   uLut0Enabled;
uniform float uLut0Strength;
uniform float uLut0Size;
uniform vec3  uLut0DomainMin;
uniform vec3  uLut0DomainMax;
uniform int   uLut1Enabled;
uniform float uLut1Strength;
uniform float uLut1Size;
uniform vec3  uLut1DomainMin;
uniform vec3  uLut1DomainMax;

uniform int  uInputProfile;   // 0 passthrough, 1 O-Log, 2 Apple Log, 3 Apple Log 2
uniform int  uTargetProfile;
uniform int  uGamutEnabled;
uniform mat3 uGamutMatrix;

uniform float uExposure;      // stops
uniform vec3  uWhiteBalance;  // linear per-channel gain
uniform float uContrast;      // 1 = unchanged
uniform float uSaturation;    // 1 = unchanged
uniform int   uBypass;        // 1 = show the ungraded frame

in vec2 vTexSamplingCoord;
out vec4 outColor;

// ---- OPPO O-Log, White Paper V1 sections 3.1 / 3.2 ----
const float O_G = 0.139;
const float O_B = 0.019;
const float O_D = 0.614;
float oLogDecode(float p) { return exp((p - O_D) / O_G) - O_B; }
float oLogEncode(float r) { return O_G * log(max(r + O_B, 1e-8)) + O_D; }

// ---- Apple Log, constants from OpenColorIO AppleCameras.cpp ----
const float A_R0    = -0.05641088;
const float A_RT    = 0.01;
const float A_C     = 47.28711236;
const float A_BETA  = 0.00964052;
const float A_GAMMA = 0.08550479;
const float A_DELTA = 0.69336945;
const float A_PT    = 0.20855531595464202;
float appleEncode(float r) {
  if (r >= A_RT) return A_GAMMA * (log(r + A_BETA) / log(2.0)) + A_DELTA;
  if (r >= A_R0) return A_C * (r - A_R0) * (r - A_R0);
  return 0.0;
}
float appleDecode(float p) {
  if (p >= A_PT) return exp2((p - A_DELTA) / A_GAMMA) - A_BETA;
  if (p >= 0.0)  return sqrt(p / A_C) + A_R0;
  return A_R0;
}

vec3 decodeToLinear(vec3 c, int profile) {
  if (profile == 1) return vec3(oLogDecode(c.r), oLogDecode(c.g), oLogDecode(c.b));
  if (profile == 2 || profile == 3) return vec3(appleDecode(c.r), appleDecode(c.g), appleDecode(c.b));
  return c;
}

vec3 encodeFromLinear(vec3 l, int profile) {
  if (profile == 1) return vec3(oLogEncode(l.r), oLogEncode(l.g), oLogEncode(l.b));
  if (profile == 2 || profile == 3) return vec3(appleEncode(l.r), appleEncode(l.g), appleEncode(l.b));
  return l;
}

// Sample a 3D LUT with correct texel-centre offsets so the endpoints land exactly.
// The domain is applied here so the GPU matches the CPU path, which normalises the same way;
// without it a LUT declaring DOMAIN_MIN/MAX would grade differently from its own swatch.
vec3 sampleLut(sampler3D lut, vec3 c, float n, vec3 dMin, vec3 dMax) {
  vec3 norm = clamp((c - dMin) / max(dMax - dMin, vec3(1e-6)), 0.0, 1.0);
  vec3 uvw = (norm * (n - 1.0) + 0.5) / n;
  return texture(lut, uvw).rgb;
}

void main() {
  vec3 c = texture(uTexSampler, vTexSamplingCoord).rgb;

  if (uBypass == 1) { outColor = vec4(c, 1.0); return; }

  // 1. to scene linear, so exposure and white balance behave photographically
  vec3 lin = decodeToLinear(c, uInputProfile);

  // 2. exposure and white balance, in linear
  lin *= exp2(uExposure);
  lin *= uWhiteBalance;

  // 3. gamut conversion, only when the target uses different primaries
  if (uGamutEnabled == 1) lin = uGamutMatrix * lin;

  // 4. back to the encoding the LUT stack expects
  vec3 p = encodeFromLinear(lin, uTargetProfile);

  // 5. LUT stack, in order, each blended by its own strength
  if (uLut0Enabled == 1) p = mix(p, sampleLut(uLut0, p, uLut0Size, uLut0DomainMin, uLut0DomainMax), uLut0Strength);
  if (uLut1Enabled == 1) p = mix(p, sampleLut(uLut1, p, uLut1Size, uLut1DomainMin, uLut1DomainMax), uLut1Strength);

  // 6. contrast and saturation, in the output space where they behave as expected
  p = (p - 0.5) * uContrast + 0.5;
  float luma = dot(p, vec3(0.2126, 0.7152, 0.0722));
  p = mix(vec3(luma), p, uSaturation);

  outColor = vec4(clamp(p, 0.0, 1.0), 1.0);
}
"""

/**
 * The whole grade in one GPU pass: colour space transform, ordered LUT stack with per-LUT
 * strength, and the grading controls.
 *
 * One pass matters. Each Media3 shader program writes to an intermediate texture, so splitting
 * this into several effects would quantise between every stage.
 */
class GradeShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val controller: GradeController,
) : BaseGlShaderProgram(useHdr, 1) {

    companion object { const val TAG = "LuttyGl" }

    private val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
        Log.e(TAG, "shader compile failed", e)
        throw VideoFrameProcessingException(e)
    }

    private var textures: List<Lut3dTexture> = emptyList()
    private var uploadedGeneration = -1

    /**
     * GLES requires every declared sampler to have a complete texture bound, even when the shader
     * never reads it. Binding id 0 to a sampler3D leaves the unit incomplete and the draw fails,
     * so an unused slot gets this 2^3 placeholder instead.
     */
    private var placeholder: Lut3dTexture? = null

    /** Uploads only when the LUT set actually changed. Runs on the GL thread from drawFrame. */
    private fun syncTextures() {
        if (uploadedGeneration == controller.lutGeneration && placeholder != null) return
        textures.forEach { it.release() }
        textures = controller.luts.take(2).map { Lut3dTexture.upload(it) }
        if (placeholder == null) placeholder = Lut3dTexture.upload(LutData.identity(2))
        uploadedGeneration = controller.lutGeneration
        Log.i(TAG, "LUT textures synced: ${textures.size} (generation $uploadedGeneration)")
    }

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        glProgram.setFloatsUniform("uTransformationMatrix", identity)
        glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
        Log.i(TAG, "GradeShaderProgram ready: useHdr=$useHdr")
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val grade = controller.grade
        try {
            glProgram.use()
            syncTextures()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)

            bindLut(grade, 0, "uLut0", 1)
            bindLut(grade, 1, "uLut1", 2)

            glProgram.setIntUniform("uInputProfile", grade.input.shaderId)
            glProgram.setIntUniform("uTargetProfile", grade.target.shaderId)

            val needsGamut = grade.input != Profile.APPLE_LOG_2 && grade.target == Profile.APPLE_LOG_2
            glProgram.setIntUniform("uGamutEnabled", if (needsGamut) 1 else 0)
            glProgram.setFloatsUniform("uGamutMatrix", GAMUT_BT2020_TO_AWG_COLUMN_MAJOR)

            glProgram.setFloatUniform("uExposure", grade.exposure)
            glProgram.setFloatsUniform("uWhiteBalance", grade.whiteBalanceGain())
            glProgram.setFloatUniform("uContrast", grade.contrast)
            glProgram.setFloatUniform("uSaturation", grade.saturation)
            glProgram.setIntUniform("uBypass", if (controller.bypass) 1 else 0)

            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun bindLut(grade: GradeState, index: Int, sampler: String, texUnit: Int) {
        val enabled = "${sampler}Enabled"
        val strength = "${sampler}Strength"
        val size = "${sampler}Size"
        val dMin = "${sampler}DomainMin"
        val dMax = "${sampler}DomainMax"

        val tex = textures.getOrNull(index)
        if (tex == null) {
            val fill = placeholder ?: textures.firstOrNull() ?: return
            glProgram.setSamplerTexIdUniform(sampler, fill.textureId, texUnit, GLES30.GL_TEXTURE_3D)
            glProgram.setIntUniform(enabled, 0)
            glProgram.setFloatUniform(strength, 0f)
            glProgram.setFloatUniform(size, fill.size.toFloat())
            glProgram.setFloatsUniform(dMin, floatArrayOf(0f, 0f, 0f))
            glProgram.setFloatsUniform(dMax, floatArrayOf(1f, 1f, 1f))
            return
        }
        glProgram.setSamplerTexIdUniform(sampler, tex.textureId, texUnit, GLES30.GL_TEXTURE_3D)
        glProgram.setIntUniform(enabled, 1)
        glProgram.setFloatUniform(strength, grade.luts.getOrNull(index)?.strength ?: 1f)
        glProgram.setFloatUniform(size, tex.size.toFloat())
        glProgram.setFloatsUniform(dMin, tex.domainMin)
        glProgram.setFloatsUniform(dMax, tex.domainMax)
    }

    override fun release() {
        super.release()
        textures.forEach { it.release() }
        placeholder?.release()
        try { glProgram.delete() } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/** GLSL mat3 uniforms are column-major, so the row-major matrix is transposed here. */
private val GAMUT_BT2020_TO_AWG_COLUMN_MAJOR: FloatArray = run {
    val m = ColorProfiles.BT2020_TO_APPLE_WIDE_GAMUT
    floatArrayOf(
        m[0].toFloat(), m[3].toFloat(), m[6].toFloat(),
        m[1].toFloat(), m[4].toFloat(), m[7].toFloat(),
        m[2].toFloat(), m[5].toFloat(), m[8].toFloat(),
    )
}
