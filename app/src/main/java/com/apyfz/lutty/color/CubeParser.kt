package com.apyfz.lutty.color

import java.io.BufferedReader
import java.io.InputStream

sealed interface CubeResult {
    data class Ok(val lut: LutData) : CubeResult
    data class Error(val message: String, val line: Int? = null) : CubeResult
}

/**
 * Parser for Adobe .cube LUT files.
 *
 * Handles LUT_3D_SIZE and LUT_1D_SIZE, quoted TITLE, DOMAIN_MIN / DOMAIN_MAX, '#' comments,
 * blank lines, CRLF, and arbitrary whitespace between values.
 *
 * Written as a streaming single pass because real files are large: a 65^3 LUT is 274,625 data
 * lines and roughly 7 MB of text.
 */
object CubeParser {

    fun parse(stream: InputStream): CubeResult =
        stream.bufferedReader().use { parse(it) }

    fun parse(text: String): CubeResult = parse(text.reader().buffered())

    fun parse(reader: BufferedReader): CubeResult {
        var size = -1
        var oneDimensional = false
        var title: String? = null
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)

        var data: FloatArray? = null
        var written = 0
        var lineNo = 0

        while (true) {
            val raw = reader.readLine() ?: break
            lineNo++
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue

            val upper = line.uppercase()
            when {
                upper.startsWith("TITLE") -> {
                    title = line.substringAfter("TITLE").trim().trim('"')
                    continue
                }
                upper.startsWith("LUT_3D_SIZE") || upper.startsWith("LUT_1D_SIZE") -> {
                    if (size != -1) return CubeResult.Error("duplicate LUT size declaration", lineNo)
                    oneDimensional = upper.startsWith("LUT_1D_SIZE")
                    val n = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                        ?: return CubeResult.Error("malformed LUT size", lineNo)
                    if (n < 2 || n > 256) return CubeResult.Error("LUT size $n out of range 2..256", lineNo)
                    size = n
                    val count = if (oneDimensional) n else n * n * n
                    data = FloatArray(count * 3)
                    continue
                }
                upper.startsWith("DOMAIN_MIN") -> {
                    domainMin = parseTriplet(line.drop("DOMAIN_MIN".length))
                        ?: return CubeResult.Error("malformed DOMAIN_MIN", lineNo)
                    continue
                }
                upper.startsWith("DOMAIN_MAX") -> {
                    domainMax = parseTriplet(line.drop("DOMAIN_MAX".length))
                        ?: return CubeResult.Error("malformed DOMAIN_MAX", lineNo)
                    continue
                }
                upper.startsWith("LUT_3D_INPUT_RANGE") || upper.startsWith("LUT_1D_INPUT_RANGE") -> {
                    val parts = line.split(Regex("\\s+"))
                    val lo = parts.getOrNull(1)?.toFloatOrNull()
                    val hi = parts.getOrNull(2)?.toFloatOrNull()
                    if (lo == null || hi == null) return CubeResult.Error("malformed INPUT_RANGE", lineNo)
                    domainMin = floatArrayOf(lo, lo, lo)
                    domainMax = floatArrayOf(hi, hi, hi)
                    continue
                }
            }

            val buf = data ?: return CubeResult.Error("data before LUT size declaration", lineNo)
            val triplet = parseTriplet(line) ?: return CubeResult.Error("malformed data row: \"$line\"", lineNo)
            if (written + 3 > buf.size) return CubeResult.Error("more data rows than LUT size declares", lineNo)
            buf[written] = triplet[0]; buf[written + 1] = triplet[1]; buf[written + 2] = triplet[2]
            written += 3
        }

        if (size == -1) return CubeResult.Error("no LUT_3D_SIZE or LUT_1D_SIZE found")
        val buf = data!!
        if (written != buf.size) {
            return CubeResult.Error("expected ${buf.size / 3} data rows, found ${written / 3}")
        }
        for (k in 0..2) {
            if (domainMax[k] <= domainMin[k]) return CubeResult.Error("DOMAIN_MAX must exceed DOMAIN_MIN")
        }

        val lut = if (oneDimensional) expand1dTo3d(size, buf) else LutData(size, buf, domainMin, domainMax, title)
        return CubeResult.Ok(
            if (oneDimensional) lut.copy(domainMin = domainMin, domainMax = domainMax, title = title) else lut
        )
    }

    /** A 1D LUT is a per-channel curve; expand it so the GPU path stays a single 3D sampler. */
    private fun expand1dTo3d(size: Int, curve: FloatArray): LutData {
        val rgb = FloatArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            rgb[i++] = curve[r * 3]
            rgb[i++] = curve[g * 3 + 1]
            rgb[i++] = curve[b * 3 + 2]
        }
        return LutData(size, rgb)
    }

    private fun parseTriplet(line: String): FloatArray? {
        var i = 0
        val out = FloatArray(3)
        var found = 0
        val n = line.length
        while (found < 3) {
            while (i < n && line[i].isWhitespace()) i++
            if (i >= n) return null
            val start = i
            while (i < n && !line[i].isWhitespace()) i++
            out[found] = line.substring(start, i).toFloatOrNull() ?: return null
            found++
        }
        while (i < n && line[i].isWhitespace()) i++
        return if (i < n && !line.substring(i).all { it.isWhitespace() }) null else out
    }
}
