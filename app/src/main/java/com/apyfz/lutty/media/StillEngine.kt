package com.apyfz.lutty.media

import android.graphics.Bitmap
import com.apyfz.lutty.color.GradePipeline
import com.apyfz.lutty.color.LutData
import com.apyfz.lutty.model.GradeState

/**
 * Renders a developed raw still through the same grade pipeline as video. The raw is scene-linear
 * BT.2020 (see [RawDecoder]); the grade encodes it into the target log curve and runs the LUT
 * stack, exactly mirroring [com.apyfz.lutty.gl.GradeShaderProgram].
 *
 * The CPU path is used rather than GL because a still is graded on demand, not 30 times a second.
 * A bounded-resolution [LinearImage] backs the interactive preview; export re-renders at full size.
 */
object StillEngine {

    /** Scene-linear RGB, [width] x [height], three floats per pixel (0..1+). */
    class LinearImage(val width: Int, val height: Int, val rgb: FloatArray)

    /**
     * Converts LibRaw's 16-bit output to float, downscaling (nearest, integer factor) so the long
     * edge is at most [maxEdge]. maxEdge <= 0 keeps full resolution, for export.
     */
    fun fromRaw(lin: RawDecoder.Linear, maxEdge: Int): LinearImage {
        val sw = lin.width
        val sh = lin.height
        // Round the divisor up, not down: floor would leave the long edge *above* maxEdge
        // (6048 / 2048 = 2 -> 3024px), which is how the preview ballooned to ~6MP and OOM'd.
        val step = if (maxEdge <= 0) 1 else maxOf(1, (maxOf(sw, sh) + maxEdge - 1) / maxEdge)
        val w = sw / step
        val h = sh / step
        val out = FloatArray(w * h * 3)
        var di = 0
        for (y in 0 until h) {
            val srow = (y * step) * sw * 3
            for (x in 0 until w) {
                val si = srow + (x * step) * 3
                out[di++] = (lin.rgb[si].toInt() and 0xFFFF) / 65535f
                out[di++] = (lin.rgb[si + 1].toInt() and 0xFFFF) / 65535f
                out[di++] = (lin.rgb[si + 2].toInt() and 0xFFFF) / 65535f
            }
        }
        return LinearImage(w, h, out)
    }

    /** Grades [img] into an ARGB_8888 bitmap. */
    fun render(img: LinearImage, grade: GradeState, luts: List<LutData>): Bitmap {
        val w = img.width
        val h = img.height
        val pixels = IntArray(w * h)
        val src = img.rgb
        val tmp = FloatArray(3)
        var i = 0
        while (i < w * h) {
            val k = i * 3
            tmp[0] = src[k]; tmp[1] = src[k + 1]; tmp[2] = src[k + 2]
            val out = GradePipeline.apply(grade, luts, tmp)
            pixels[i] = (0xFF shl 24) or
                ((out[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((out[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (out[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            i++
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
