package com.apyfz.lutty.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Values pinned against colour-science 0.4.7 and the OPPO O-Log White Paper V1.
 * If any of these drift, the colour pipeline is wrong. See tools/verify_color.py.
 */
class ColorProfilesTest {

    @Test fun `apple log encodes 18 percent grey to the cross-checked value`() {
        assertEquals(0.4882724585268676, ColorProfiles.appleLogEncode(0.18), 1e-12)
    }

    @Test fun `apple log curve segments meet at the breakpoint`() {
        val logBranch = ColorProfiles.appleLogEncode(0.01)
        val gammaBranch = ColorProfiles.applePt
        assertEquals(0.20855531595464202, gammaBranch, 1e-15)
        assertTrue("segments disagree by ${abs(logBranch - gammaBranch)}", abs(logBranch - gammaBranch) < 3e-9)
    }

    @Test fun `apple log round trips`() {
        for (r in listOf(0.0, 0.005, 0.01, 0.18, 1.0, 8.0, 16.0)) {
            assertEquals(r, ColorProfiles.appleLogDecode(ColorProfiles.appleLogEncode(r)), 1e-9)
        }
    }

    @Test fun `o-log matches the white paper table within its own tolerance`() {
        assertEquals(0.0630990, ColorProfiles.oLogEncode(0.0), 1e-6)      // published 0.0631271
        assertEquals(0.3895914, ColorProfiles.oLogEncode(0.18), 1e-6)     // published 0.3895463
        assertEquals(0.9995548, ColorProfiles.oLogEncode(16.0), 1e-6)     // published 1.0
    }

    @Test fun `o-log round trips`() {
        for (r in listOf(0.0, 0.18, 0.39, 1.0, 16.0)) {
            assertEquals(r, ColorProfiles.oLogDecode(ColorProfiles.oLogEncode(r)), 1e-9)
        }
    }

    @Test fun `bt2020 to apple wide gamut preserves neutrals`() {
        val m = ColorProfiles.BT2020_TO_APPLE_WIDE_GAMUT
        for (row in 0..2) {
            val sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]
            assertEquals("row $row sums to $sum", 1.0, sum, 1e-9)
        }
    }

    @Test fun `gamut matrix inverts cleanly`() {
        val fwd = ColorProfiles.BT2020_TO_APPLE_WIDE_GAMUT
        val inv = ColorProfiles.APPLE_WIDE_GAMUT_TO_BT2020
        val v = doubleArrayOf(0.31, 0.47, 0.22)
        val back = ColorProfiles.apply3x3(inv, ColorProfiles.apply3x3(fwd, v))
        for (k in 0..2) assertEquals(v[k], back[k], 1e-12)
    }

    @Test fun `18 percent grey stays neutral through o-log to apple log 2`() {
        val grey = ColorProfiles.oLogEncode(0.18)
        val out = ColorProfiles.oLogToAppleLog2(doubleArrayOf(grey, grey, grey))
        for (k in 0..2) assertEquals(0.4882724585268676, out[k], 1e-9)
    }

    @Test fun `o-log to apple log 1 needs no matrix and stays neutral`() {
        val grey = ColorProfiles.oLogEncode(0.18)
        val out = ColorProfiles.oLogToAppleLog(doubleArrayOf(grey, grey, grey))
        for (k in 0..2) assertEquals(0.4882724585268676, out[k], 1e-9)
    }

    @Test fun `red log3g10 v3 matches colour-science`() {
        assertEquals(0.3333329120259919, ColorProfiles.log3g10Encode(0.18), 1e-12)
        assertEquals(0.0915514877147452, ColorProfiles.log3g10Encode(0.0), 1e-12)
        assertEquals(0.4934485197706815, ColorProfiles.log3g10Encode(1.0), 1e-12)
    }

    @Test fun `red log3g10 round trips`() {
        for (r in listOf(0.0, 0.18, 0.39, 1.0, 16.0)) {
            assertEquals(r, ColorProfiles.log3g10Decode(ColorProfiles.log3g10Encode(r)), 1e-9)
        }
    }

    @Test fun `nikon n-log matches colour-science`() {
        assertEquals(0.3636677701171387, ColorProfiles.nLogEncode(0.18), 1e-12)
        assertEquals(0.1243726278963715, ColorProfiles.nLogEncode(0.0), 1e-12)
        assertEquals(0.6050830889540567, ColorProfiles.nLogEncode(1.0), 1e-12)
    }

    @Test fun `nikon n-log round trips`() {
        for (r in listOf(0.0, 0.18, 0.39, 1.0, 8.0)) {
            assertEquals(r, ColorProfiles.nLogDecode(ColorProfiles.nLogEncode(r)), 1e-9)
        }
    }

    @Test fun `red wide gamut to apple wide gamut preserves neutrals`() {
        val m = ColorProfiles.RED_WIDE_GAMUT_TO_APPLE_WIDE_GAMUT
        for (row in 0..2) {
            val sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]
            assertEquals("row $row sums to $sum", 1.0, sum, 5e-6)
        }
    }

    @Test fun `18 percent grey stays neutral through red log3g10 to apple log 2`() {
        val grey = ColorProfiles.log3g10Encode(0.18)
        val out = ColorProfiles.redLog3G10ToAppleLog2(doubleArrayOf(grey, grey, grey))
        for (k in 0..2) assertEquals(0.4882724585268676, out[k], 1e-6)
    }

    @Test fun `18 percent grey stays neutral through n-log to apple log 2`() {
        val grey = ColorProfiles.nLogEncode(0.18)
        val out = ColorProfiles.nLogToAppleLog2(doubleArrayOf(grey, grey, grey))
        for (k in 0..2) assertEquals(0.4882724585268676, out[k], 1e-9)
    }

    @Test fun `fujifilm f-log2 matches colour-science`() {
        assertEquals(0.39100724189123004, ColorProfiles.fLog2Encode(0.18), 1e-12)
        assertEquals(0.092864, ColorProfiles.fLog2Encode(0.0), 1e-12)
        assertEquals(0.5682193704444426, ColorProfiles.fLog2Encode(1.0), 1e-12)
    }

    @Test fun `fujifilm f-log2 round trips`() {
        for (r in listOf(0.0, 0.18, 0.39, 1.0, 8.0)) {
            assertEquals(r, ColorProfiles.fLog2Decode(ColorProfiles.fLog2Encode(r)), 1e-9)
        }
    }

    @Test fun `18 percent grey stays neutral through f-log2 to apple log 2`() {
        val grey = ColorProfiles.fLog2Encode(0.18)
        val out = ColorProfiles.fLog2ToAppleLog2(doubleArrayOf(grey, grey, grey))
        for (k in 0..2) assertEquals(0.4882724585268676, out[k], 1e-9)
    }
}
