package com.apyfz.lutty.color

/**
 * A parsed 3D LUT, laid out for direct upload to a GL_TEXTURE_3D.
 *
 * [rgb] holds size^3 triplets in R-fastest order (the .cube convention): the entry for grid point
 * (r, g, b) starts at index 3 * (r + size * g + size * size * b).
 */
data class LutData(
    val size: Int,
    val rgb: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    val title: String? = null,
) {
    init {
        require(size >= 2) { "LUT size must be at least 2, was $size" }
        require(rgb.size == size * size * size * 3) {
            "expected ${size * size * size * 3} floats for a ${size}^3 LUT, got ${rgb.size}"
        }
    }

    fun entry(r: Int, g: Int, b: Int): FloatArray {
        val i = 3 * (r + size * g + size * size * b)
        return floatArrayOf(rgb[i], rgb[i + 1], rgb[i + 2])
    }

    /** Trilinear sample, matching what the GPU does. Used by tests and by the identity check. */
    fun sample(rIn: Float, gIn: Float, bIn: Float): FloatArray {
        val out = FloatArray(3)
        val n = size - 1
        val c = floatArrayOf(rIn, gIn, bIn)
        val f = FloatArray(3)
        val i0 = IntArray(3)
        for (k in 0..2) {
            val norm = ((c[k] - domainMin[k]) / (domainMax[k] - domainMin[k])).coerceIn(0f, 1f)
            val pos = norm * n
            i0[k] = pos.toInt().coerceIn(0, n - 1).let { if (n == 0) 0 else it }
            f[k] = pos - i0[k]
        }
        for (corner in 0 until 8) {
            val dr = corner and 1
            val dg = (corner shr 1) and 1
            val db = (corner shr 2) and 1
            val w = (if (dr == 1) f[0] else 1f - f[0]) *
                    (if (dg == 1) f[1] else 1f - f[1]) *
                    (if (db == 1) f[2] else 1f - f[2])
            if (w == 0f) continue
            val e = entry(
                (i0[0] + dr).coerceAtMost(n),
                (i0[1] + dg).coerceAtMost(n),
                (i0[2] + db).coerceAtMost(n),
            )
            out[0] += w * e[0]; out[1] += w * e[1]; out[2] += w * e[2]
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is LutData && size == other.size && rgb.contentEquals(other.rgb))

    override fun hashCode(): Int = 31 * size + rgb.contentHashCode()

    companion object {
        /** Mathematically exact identity LUT — the basis of the pipeline transparency check. */
        fun identity(size: Int = 33): LutData {
            val rgb = FloatArray(size * size * size * 3)
            val n = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                rgb[i++] = r / n; rgb[i++] = g / n; rgb[i++] = b / n
            }
            return LutData(size, rgb, title = "Identity")
        }
    }
}
