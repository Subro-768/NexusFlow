package com.resumabletransfer.app

import com.resumabletransfer.app.ui.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing-code parsing, including the two bugs this replaced.
 *
 * Both original bugs were found the same way: pairing a device whose camera
 * could not be used, on a network where discovery does not work, so the code had
 * to be typed by hand.
 *
 *  1. `isPlausiblePairingCode` accepted a bare `host:port` but not a `?t=`
 *     suffix, so a hand-typed token was rejected as "does not look like a
 *     pairing code" -- even though the desktop receiver has always supported
 *     typing a code, and `applyPairingUri` reads `?t=` from any form.
 *  2. The validator accepted bare `host:port`, but `applyPairingUri` had no
 *     branch for it: it fell through to `host = null` and the app then refused
 *     the code it had just accepted.
 *
 * Parsing now lives in one place, so a form that validates is a form that
 * parses. These tests pin that invariant: every case asserted plausible is also
 * asserted to produce a usable host.
 */
class PairingCodeTest {

    private fun parsed(code: String) = PairingCode.parse(code)

    // ---- The forms that must work -------------------------------------------------

    @Test
    fun `nexus scheme with port and token`() {
        val t = parsed("nexus://10.3.184.58:8000/?t=XYGUF4MM")
        assertEquals("10.3.184.58", t?.host)
        assertEquals(8000, t?.port)
        assertEquals("XYGUF4MM", t?.token)
    }

    @Test
    fun `nexus scheme without token`() {
        val t = parsed("nexus://192.168.1.5:8000")
        assertEquals("192.168.1.5", t?.host)
        assertEquals(8000, t?.port)
        assertNull(t?.token)
    }

    /** Regression for bug 1: this exact string is what a person types. */
    @Test
    fun `bare host and port with token is accepted`() {
        val t = parsed("10.3.184.58:8000/?t=XYGUF4MM")
        assertEquals("10.3.184.58", t?.host)
        assertEquals(8000, t?.port)
        assertEquals("XYGUF4MM", t?.token)
    }

    /** Regression for bug 2: the validator accepted this, so it must parse. */
    @Test
    fun `bare host and port without token parses to a usable host`() {
        val t = parsed("10.3.184.58:8000")
        assertEquals("10.3.184.58", t?.host)
        assertEquals(8000, t?.port)
    }

    @Test
    fun `bare host with no port`() {
        val t = parsed("10.3.184.58")
        assertEquals("10.3.184.58", t?.host)
        assertNull(t?.port)
    }

    @Test
    fun `mdns style hostname is accepted`() {
        assertEquals("desktop.local", parsed("desktop.local")?.host)
    }

    /** The old http branch existed, so removing it must not regress URL pasting. */
    @Test
    fun `http url with token still parses`() {
        val t = parsed("http://10.3.184.58:8000/?t=ABCD2345")
        assertEquals("10.3.184.58", t?.host)
        assertEquals(8000, t?.port)
        assertEquals("ABCD2345", t?.token)
    }

    @Test
    fun `https url without port`() {
        val t = parsed("https://host.example/?t=ZZZZ9999")
        assertEquals("host.example", t?.host)
        assertNull(t?.port)
        assertEquals("ZZZZ9999", t?.token)
    }

    /**
     * A token written as `/t=` with no `?` is part of the path, not a query.
     *
     * The old `http://` branch took everything up to the first `/` as the
     * address, so it dropped the token entirely -- pairing would have looked
     * like it worked and then failed with a 401 on the first transfer.
     */
    @Test
    fun `token after a slash without a question mark is not a token`() {
        val t = parsed("https://host.example/t=ZZZZ9999")
        assertEquals("host.example", t?.host)
        assertNull(t?.port)
        assertNull(t?.token)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val t = parsed("  nexus://10.0.0.5:8000/?t=AAAAAAAA  ")
        assertEquals("10.0.0.5", t?.host)
        assertEquals("AAAAAAAA", t?.token)
    }

    @Test
    fun `extra query parameters are ignored`() {
        val t = parsed("10.3.184.58:8000/?t=XYGUF4MM&x=1")
        assertEquals("XYGUF4MM", t?.token)
    }

    @Test
    fun `a path is not part of the address`() {
        val t = parsed("http://10.3.184.58:8000/transfer")
        assertEquals("10.3.184.58", t?.host)
        assertEquals(8000, t?.port)
    }

    // ---- Rubbish that must be rejected ---------------------------------------------

    @Test
    fun `empty and blank are rejected`() {
        assertNull(parsed(""))
        assertNull(parsed("   "))
        assertFalse(PairingCode.isPlausible(""))
    }

    @Test
    fun `whitespace inside is rejected`() {
        assertNull(parsed("hello world"))
    }

    @Test
    fun `non numeric port is rejected rather than crashing`() {
        assertNull(parsed("10.3.184.58:80a0"))
    }

    @Test
    fun `out of range port is rejected`() {
        assertNull(parsed("10.3.184.58:70000"))
        assertNull(parsed("10.3.184.58:99999/x"))
    }

    @Test
    fun `a scheme with no host is rejected`() {
        assertNull(parsed("nexus://"))
        assertNull(parsed("nexus:///path"))
    }

    /** What adb input text produced, and what a bad paste can look like. */
    @Test
    fun `a missing scheme is rejected`() {
        assertNull(parsed("://10.3.184.58:8000/?t=XYGUF4MM"))
    }

    // ---- The invariant --------------------------------------------------------------

    /**
     * Whatever the dialog calls plausible, the parser must be able to use.
     *
     * This is the property that was violated: the validator and the parser were
     * separate implementations and drifted apart.
     */
    @Test
    fun `anything plausible parses to a non blank host`() {
        val codes = listOf(
            "nexus://10.3.184.58:8000",
            "nexus://10.3.184.58:8000/?t=XYGUF4MM",
            "10.3.184.58:8000",
            "10.3.184.58:8000/?t=XYGUF4MM",
            "10.3.184.58",
            "192.168.1.5:8000",
            "desktop.local",
            "http://10.3.184.58:8000/?t=ABCD2345",
            "https://host.example/?t=ZZZZ9999",
            "http://h:8000/transfer",
            "  nexus://10.0.0.5:8000/?t=AAAAAAAA  ",
            "10.3.184.58:8000/?t="
        )
        for (code in codes) {
            assertTrue("expected '$code' to be plausible", PairingCode.isPlausible(code))
            assertTrue(
                "expected '$code' to yield a host",
                !parsed(code)?.host.isNullOrBlank()
            )
        }
    }

    @Test
    fun `rubbish is not plausible`() {
        val rubbish = listOf(
            "", "   ", "hello world", "10.3.184.58:80a0",
            "10.3.184.58:70000", "nexus://", "nexus:///path",
            "://10.3.184.58:8000/?t=XYGUF4MM"
        )
        for (code in rubbish) {
            assertFalse("expected '$code' to be rejected", PairingCode.isPlausible(code))
        }
    }

    /**
     * The token helper the QR path uses must never throw on a payload the parser
     * cannot read: an unparseable code still has to be searched for by name.
     */
    @Test
    fun `tokenFrom tolerates payloads this parser rejects`() {
        assertEquals("XYGUF4MM", PairingCode.tokenFrom("nexus://receive/NEXUS%20FLOW?t=XYGUF4MM"))
        assertEquals("XYGUF4MM", PairingCode.tokenFrom(":::://not-a-code?t=XYGUF4MM"))
        assertNull(PairingCode.tokenFrom("nexus://10.0.0.5:8000"))
        assertNull(PairingCode.tokenFrom("nexus://10.0.0.5:8000?t="))
        assertNull(PairingCode.tokenFrom("no query here"))
    }
}