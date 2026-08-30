package com.apyfz.lutty.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.apyfz.lutty.color.LutData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Uploads a [LutData] as a GL_TEXTURE_3D.
 *
 * Tries RGBA16F first so the LUT itself keeps far more precision than Media3's built-in
 * SingleColorLut, which quantises to 8 bits per channel. Falls back to RGBA8 if the device
 * refuses to filter half-float 3D textures.
 */
class Lut3dTexture private constructor(val textureId: Int, val size: Int, val isHighPrecision: Boolean) {

    fun release() {
        val ids = intArrayOf(textureId)
        GLES20.glDeleteTextures(1, ids, 0)
    }

    companion object {
        const val TAG = "LuttyGl"

        fun upload(lut: LutData): Lut3dTexture {
            val n = lut.size
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val id = ids[0]
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, id)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)

            // RGBA float data, R varying fastest, matching both the .cube convention and GL's
            // expectation that the first axis is x.
            val floats = FloatArray(n * n * n * 4)
            var s = 0
            var d = 0
            while (s < lut.rgb.size) {
                floats[d++] = lut.rgb[s]
                floats[d++] = lut.rgb[s + 1]
                floats[d++] = lut.rgb[s + 2]
                floats[d++] = 1f
                s += 3
            }
            val buf: FloatBuffer = ByteBuffer
                .allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(floats).position(0)

            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, n, n, n, 0,
                GLES20.GL_RGBA, GLES20.GL_FLOAT, buf,
            )
            var err = GLES20.glGetError()
            var highPrecision = true

            if (err != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "RGBA16F 3D texture rejected (0x${err.toString(16)}), falling back to RGBA8")
                highPrecision = false
                val bytes = ByteArray(n * n * n * 4)
                var i = 0
                var j = 0
                while (i < lut.rgb.size) {
                    bytes[j++] = ((lut.rgb[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()).toByte()
                    bytes[j++] = ((lut.rgb[i + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()).toByte()
                    bytes[j++] = ((lut.rgb[i + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()).toByte()
                    bytes[j++] = -1
                    i += 3
                }
                val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                bb.put(bytes).position(0)
                GLES30.glTexImage3D(
                    GLES30.GL_TEXTURE_3D, 0, GLES20.GL_RGBA, n, n, n, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb,
                )
                err = GLES20.glGetError()
                if (err != GLES20.GL_NO_ERROR) {
                    Log.e(TAG, "RGBA8 3D texture also failed: 0x${err.toString(16)}")
                }
            }
            Log.i(TAG, "uploaded ${n}^3 LUT as 3D texture id=$id highPrecision=$highPrecision")
            return Lut3dTexture(id, n, highPrecision)
        }
    }
}
