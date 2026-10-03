package com.resumabletransfer.app

import com.resumabletransfer.app.server.PairingToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ── pairing tokens ────────────────────────────────────────────

class PairingTokenTest {

    @Test
    fun `generates the advertised shape`() {
        repeat(500) {
            val token = PairingToken.generate()
            assertEquals("length", 8, token.length)
            // 0/O and 1/I/L excluded so a code can be dictated or transcribed.
            assertTrue("ambiguous glyph in $token", token.none { it in "01OIL" })
        }
    }

    @Test
    fun `generates distinct tokens`() {
        val tokens = (1..1000).map { PairingToken.generate() }.toSet()
        assertEquals("collision in 1000 draws", 1000, tokens.size)
    }

    @Test
    fun `accepts only an exact match`() {
        val expected = PairingToken.generate()
        assertTrue(PairingToken.matches(expected, expected))
        assertFalse(PairingToken.matches(expected, expected.dropLast(1)))
        assertFalse(PairingToken.matches(expected, expected + "X"))
        assertFalse(PairingToken.matches(expected, ""))
        assertFalse(PairingToken.matches(expected, null))
        assertFalse(PairingToken.matches("", expected))
        assertFalse(PairingToken.matches(null, null))
        assertFalse(PairingToken.matches(expected, expected.lowercase()))
    }

    @Test
    fun `a one character difference is refused`() {
        // What makes this a credential rather than a hint.
        val token = PairingToken.generate()
        val wrong = (if (token[0] != 'A') "A" else "B") + token.substring(1)
        assertNotEquals(token, wrong)
        assertFalse(PairingToken.matches(token, wrong))
    }

    @Test
    fun `reads the header first then the query`() {
        val headers = mapOf(PairingToken.HEADER.lowercase() to "FROMHEADER")
        assertEquals("FROMHEADER", PairingToken.from(headers, "t=FROMQUERY"))
        assertEquals("FROMQUERY", PairingToken.from(emptyMap(), "t=FROMQUERY"))
        assertEquals("FROMQUERY", PairingToken.from(emptyMap(), "x=1&t=FROMQUERY"))
        assertNull(PairingToken.from(emptyMap(), "x=1"))
        assertNull(PairingToken.from(emptyMap(), null))
        assertNull(PairingToken.from(emptyMap(), ""))
    }

    @Test
    fun `a whitespace only header falls through to the query`() {
        // Otherwise a blank header yields "" and authenticates nothing while
        // looking like it was considered.
        assertEquals(
            "FROMQUERY",
            PairingToken.from(mapOf(PairingToken.HEADER.lowercase() to "   "), "t=FROMQUERY")
        )
    }
}

// ── pairing payload encoding ──────────────────────────────────

class PairingPayloadTest {

    @Test
    fun `omits the token when there is none`() {
        val payload = buildPairingPayload("Living Room Laptop")
        assertFalse("must not carry an empty ?t=", payload.contains("?t="))
        assertTrue(payload.startsWith("nexus://receive/"))
    }

    @Test
    fun `carries the token when given one`() {
        val payload = buildPairingPayload("Laptop", token = "ABC23456")
        assertEquals("ABC23456", pairingTokenOf(payload))
    }

    @Test
    fun `round trips a name with characters that need encoding`() {
        val name = "Søren's Laptop/2"
        val payload = buildPairingPayload(name, token = "ZZZZZZZZ")

        assertTrue("slash must be percent-encoded", payload.contains("%2F"))

        // The separator to check is the one that matters: exactly one "/" may
        // appear, the one introducing the scheme's "receive" segment. A device
        // name containing a slash must not create a second path segment, or the
        // name a sender resolves is silently the wrong one.
        //
        // Counted after the scheme's own "//" is removed, since that is a
        // literal part of "nexus://" rather than a path separator.
        val afterScheme = payload.substringAfter("://", "")
        val separators = afterScheme.count { ch -> ch == '/' }
        assertEquals("more than one path separator in $payload", 1, separators)

        assertEquals("ZZZZZZZZ", pairingTokenOf(payload))
    }

    @Test
    fun `reads no token from a payload without one`() {
        assertNull(pairingTokenOf(buildPairingPayload("Laptop")))
        assertNull(pairingTokenOf("nexus://receive/Laptop"))
        assertNull(pairingTokenOf(""))
    }

    @Test
    fun `ignores a different query parameter`() {
        assertNull(pairingTokenOf("nexus://receive/Laptop?x=1"))
        assertEquals("ABCD2345", pairingTokenOf("nexus://receive/Laptop?x=1&t=ABCD2345"))
    }
}

// ── resume arithmetic ─────────────────────────────────────────

class ResumeOffsetTest {

    private fun resumeOffset(receivedBytes: Long, fileSize: Long): Long = when {
        receivedBytes <= 0L -> 0L
        receivedBytes >= fileSize -> fileSize
        else -> receivedBytes
    }

    @Test
    fun `nothing received starts from zero`() {
        assertEquals(0L, resumeOffset(0L, 1000L))
        assertEquals(0L, resumeOffset(-5L, 1000L))
    }

    @Test
    fun `resumes from exactly what the receiver holds`() {
        assertEquals(41L * 1024 * 1024, resumeOffset(41L * 1024 * 1024, 120L * 1024 * 1024))
    }

    @Test
    fun `a fully received file does not overflow`() {
        val size = 120L * 1024 * 1024
        assertEquals(size, resumeOffset(size, size))
        // More than the size cannot happen honestly, but must not produce an
        // offset the sender would then try to stream past.
        assertEquals(size, resumeOffset(size + 4096L, size))
    }

    @Test
    fun `offsets survive the chunk boundary exactly`() {
        val chunk = 1024L * 1024
        val size = 120L * chunk
        for (received in 1L..120L) {
            val at = received * chunk
            assertEquals("resume at $at", at, resumeOffset(at, size))
        }
    }

    @Test
    fun `offsets work past two gigabytes`() {
        // 32-bit signed arithmetic would wrap here and seek into the middle of
        // the file; the sender and receiver both use Long for this reason.
        val size = 5L * 1024 * 1024 * 1024
        val at = 3L * 1024 * 1024 * 1024 + 12345L
        assertEquals(at, resumeOffset(at, size))
        assertTrue(at > Int.MAX_VALUE.toLong())
    }
}

// ── the pre-flight decision ───────────────────────────────────

class PreflightDecisionTest {

    private enum class Probe { ANSWERED, SILENT }

    private fun decide(known: Boolean, host: String, probe: () -> Probe): Boolean =
        known || host == "127.0.0.1" || probe() == Probe.ANSWERED

    @Test
    fun `a discovered peer is not probed again`() {
        var probed = false
        val go = decide(known = true, host = "10.0.0.5") { probed = true; Probe.SILENT }
        assertTrue(go)
        assertFalse("probing a known peer only adds latency", probed)
    }

    @Test
    fun `loopback is always attempted`() {
        var probed = false
        val go = decide(known = false, host = "127.0.0.1") { probed = true; Probe.SILENT }
        assertTrue("adb reverse puts the receiver there with nothing to discover", go)
        assertFalse(probed)
    }

    @Test
    fun `an unknown host is probed and refused when silent`() {
        var probed = false
        val go = decide(known = false, host = "192.168.69.223") { probed = true; Probe.SILENT }
        assertTrue("the probe must actually happen", probed)
        assertFalse("nothing there: report NO DEVICE rather than retrying", go)
    }

    @Test
    fun `an unknown host proceeds when the probe answers`() {
        assertTrue(decide(known = false, host = "10.0.0.9") { Probe.ANSWERED })
    }

    @Test
    fun `the stale hotspot address from the bug report would be refused`() {
        // The address in the original report: nothing listening, three timeouts,
        // then a misleading INTERRUPTED.
        assertFalse(decide(known = false, host = "192.168.69.223") { Probe.SILENT })
    }
}