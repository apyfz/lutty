package com.apyfz.lutty.color

import android.graphics.Color

/**
 * Bridges a parsed [LutData] into the int[R][G][B] cube that Media3's SingleColorLut expects.
 *
 * Media3 documents the indexing as cube[R][G][B] with ARGB_8888 ints, so this quantises to 8 bits
 * per channel. That is a real precision loss against the 16-bit-float texture the design calls for,
 * and is acceptable only for pipeline validation.
 */
object LutCube {

    fun toMedia3Cube(lut: LutData): Array<Array<IntArray>> {
        val n = lut.size
        return Array(n) { r ->
            Array(n) { g ->
                IntArray(n) { b ->
                    val e = lut.entry(r, g, b)
                    Color.argb(
                        255,
                        (e[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                        (e[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                        (e[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                    )
                }
            }
        }
    }
}
