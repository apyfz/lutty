package com.apyfz.lutty.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeParserTest {

    private fun ok(text: String): LutData {
        val r = CubeParser.parse(text)
        assertTrue("expected success, got $r", r is CubeResult.Ok)
        return (r as CubeResult.Ok).lut
    }

    private fun err(text: String): CubeResult.Error {
        val r = CubeParser.parse(text)
        assertTrue("expected failure, got $r", r is CubeResult.Error)
        return r as CubeResult.Error
    }

    private fun identityText(size: Int): String = buildString {
        appendLine("TITLE \"Identity $size\"")
        appendLine("LUT_3D_SIZE $size")
        appendLine()
        val n = (size - 1).toFloat()
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            appendLine("${r / n} ${g / n} ${b / n}")
        }
    }

    @Test fun `parses a minimal 2x2x2 lut`() {
        val lut = ok(identityText(2))
        assertEquals(2, lut.size)
        assertEquals(24, lut.rgb.size)
        assertEquals("Identity 2", lut.title)
    }

    @Test fun `r varies fastest per the cube convention`() {
        val lut = ok(identityText(3))
        assertEquals(0.5f, lut.entry(1, 0, 0)[0], 1e-6f)
        assertEquals(0.0f, lut.entry(1, 0, 0)[1], 1e-6f)
        assertEquals(0.5f, lut.entry(0, 1, 0)[1], 1e-6f)
        assertEquals(0.5f, lut.entry(0, 0, 1)[2], 1e-6f)
    }

    @Test fun `identity lut is an identity under trilinear sampling`() {
        val lut = ok(identityText(17))
        for (v in listOf(0f, 0.1f, 0.25f, 0.5f, 0.731f, 1f)) {
            val s = lut.sample(v, v, v)
            assertEquals(v, s[0], 1e-5f); assertEquals(v, s[1], 1e-5f); assertEquals(v, s[2], 1e-5f)
        }
    }

    @Test fun `handles comments blank lines crlf and ragged whitespace`() {
        val text = "# leading comment\r\n" +
            "TITLE \"Messy\"\r\n" +
            "\r\n" +
            "LUT_3D_SIZE 2   # trailing comment\r\n" +
            (0 until 8).joinToString("\r\n") { "  0.0\t0.5     1.0  " } + "\r\n"
        val lut = ok(text)
        assertEquals(2, lut.size)
        assertEquals(0.5f, lut.entry(1, 1, 1)[1], 1e-6f)
    }

    @Test fun `reads domain min and max`() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 4.0 4.0 4.0\n" +
            (0 until 8).joinToString("\n") { "0.1 0.2 0.3" }
        assertEquals(4.0f, ok(text).domainMax[0], 1e-6f)
    }

    @Test fun `expands a 1d lut into a 3d lut`() {
        val lut = ok("LUT_1D_SIZE 2\n0.0 0.0 0.0\n1.0 0.5 0.25")
        assertEquals(2, lut.size)
        assertEquals(1.0f, lut.entry(1, 0, 0)[0], 1e-6f)
        assertEquals(0.5f, lut.entry(0, 1, 0)[1], 1e-6f)
        assertEquals(0.25f, lut.entry(0, 0, 1)[2], 1e-6f)
    }

    @Test fun `rejects data appearing before the size declaration`() {
        assertTrue(err("0.0 0.0 0.0").message.contains("data before LUT size declaration"))
    }

    @Test fun `rejects a header-only file with no size declaration`() {
        assertTrue(err("TITLE \"nothing here\"\n").message.contains("no LUT_3D_SIZE"))
    }

    @Test fun `rejects too few data rows`() {
        assertTrue(err("LUT_3D_SIZE 2\n0.0 0.0 0.0").message.contains("expected 8 data rows"))
    }

    @Test fun `rejects too many data rows`() {
        val text = "LUT_3D_SIZE 2\n" + (0 until 9).joinToString("\n") { "0.0 0.0 0.0" }
        assertTrue(err(text).message.contains("more data rows"))
    }

    @Test fun `rejects a non-numeric data row`() {
        val text = "LUT_3D_SIZE 2\n" + (0 until 7).joinToString("\n") { "0.0 0.0 0.0" } + "\nfoo bar baz"
        assertTrue(err(text).message.contains("malformed data row"))
    }

    @Test fun `rejects an out of range size`() {
        assertTrue(err("LUT_3D_SIZE 1\n0 0 0").message.contains("out of range"))
    }

    @Test fun `rejects a size large enough to exhaust memory before reading any data`() {
        // The size is declared up front, so an oversized header would allocate hundreds of
        // megabytes before a single data row is validated.
        val r = err("LUT_3D_SIZE 256\n")
        assertTrue(r.message, r.message.contains("out of range"))
    }

    @Test fun `accepts the largest size real LUTs use`() {
        val lut = ok(identityText(65))
        assertEquals(65, lut.size)
    }

    @Test fun `rejects inverted domain`() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 1.0 1.0 1.0\nDOMAIN_MAX 0.0 0.0 0.0\n" +
            (0 until 8).joinToString("\n") { "0.0 0.0 0.0" }
        assertTrue(err(text).message.contains("DOMAIN_MAX must exceed"))
    }

    @Test fun `generated identity matches a parsed identity`() {
        assertEquals(LutData.identity(9), ok(identityText(9)))
    }
}
