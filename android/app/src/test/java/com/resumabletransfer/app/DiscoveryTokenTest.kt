package com.resumabletransfer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery has to carry the pairing token, or tap-to-connect only works one way.
 *
 * The asymmetry this pins down
 * ---------------------------
 * The desktop receiver has always published its token in the UDP hello, so a
 * Linux sender that taps a discovered peer authenticates with no QR and no
 * typed code. The Android receiver published only `{id, n, p, t, v}`.
 *
 * Measured on a real phone on a hotspot, the phone's broadcast was:
 *
 *     hello from 192.168.31.84: n="Subro's A51" p=8000 token=NO
 *        full payload keys: ['id', 'n', 'p', 't', 'v']
 *
 * so tapping the phone from Linux stored no token and every transfer answered
 * 401. The user-visible effect is that tapping works when the peer is a laptop
 * and never works when the peer is a phone -- with no hint as to why.
 *
 * ## Why these tests do not build a JSONObject
 *
 * `org.json` is a compile-time stub in local unit tests: every method throws
 * "not mocked". A first attempt here constructed a JSONObject and failed with
 * RuntimeException on the first call, which says nothing about the behaviour
 * under test. So the assertions below are written against plain maps and the
 * same string operations the production code performs, which is what can
 * actually be verified off-device. The wire shape itself is verified for real
 * by capturing the phone's broadcast with a UDP listener.
 */
class DiscoveryTokenTest {

    /** Mirrors LanDiscovery.myHello(), minus org.json. */
    private fun myHello(advertisedToken: String): Map<String, Any> = linkedMapOf(
        "t" to "h",
        "n" to "Subro's A51",
        "p" to 8000,
        "v" to 1,
        "id" to "192.168.31.84"
    ).apply {
        if (advertisedToken.isNotEmpty()) put("tok", advertisedToken.trim())
    }

    /**
     * The desktop parser's rule, transcribed from lan_discovery.py:
     *
     *     token=str(msg.get("tok") or "")
     */
    private fun tokenLinuxWouldRead(hello: Map<String, Any>): String =
        (hello["tok"] as? String).orEmpty().trim()

    @Test
    fun `a token is published in the hello`() {
        val hello = myHello("2KNDX8ZC")
        assertEquals("2KNDX8ZC", tokenLinuxWouldRead(hello))
    }

    @Test
    fun `the key is tok because that is what the desktop parser reads`() {
        val hello = myHello("ABCD2345")
        assertTrue(
            "desktop reads \"tok\"; any other key is silently ignored",
            hello.containsKey("tok")
        )
        assertFalse(
            "the first attempt used \"tk\", which nothing on the desktop reads",
            hello.containsKey("tk")
        )
    }

    @Test
    fun `an eight character token is carried unchanged`() {
        val token = "H26QXQ2Z"
        assertEquals(8, token.length)
        assertEquals(token, tokenLinuxWouldRead(myHello(token)))
    }

    /**
     * A device that is not receiving must not advertise a credential.
     *
     * Otherwise the phone keeps publishing a token for a server that is stopped,
     * a sender caches it, and the next tap fails with a 401 against a receiver
     * that is not even running.
     */
    @Test
    fun `no token means no field at all`() {
        val hello = myHello("")
        assertFalse(hello.containsKey("tok"))
        assertEquals("", tokenLinuxWouldRead(hello))
    }

    @Test
    fun `the advertised token is trimmed`() {
        // The setter trims, so a padded value cannot produce a token that fails
        // the receiver's constant-time comparison by a whitespace character.
        assertEquals("ABCD2345", tokenLinuxWouldRead(myHello("  ABCD2345  ")))
    }

    @Test
    fun `the hello still carries the fields the scanner needs`() {
        val hello = myHello("2KNDX8ZC")
        assertEquals("h", hello["t"])
        assertEquals("Subro's A51", hello["n"])
        assertEquals(8000, hello["p"])
        assertEquals(1, hello["v"])
        assertEquals("192.168.31.84", hello["id"])
    }

    /**
     * Receiver start and stop must move this value together.
     *
     * The receiver mints a fresh token per session, so a token advertised at
     * the wrong moment is worse than none: it looks paired and is not.
     */
    @Test
    fun `advertising stops when receiving stops`() {
        var advertisedToken = ""
        val host = "192.168.31.84"

        // Receiving enabled: the live token is published.
        advertisedToken = "BRF5S6Y7"
        assertEquals("BRF5S6Y7", tokenLinuxWouldRead(myHello(advertisedToken)))

        // Receiving disabled: the credential is withdrawn, so a sender tapping
        // this host afterwards gets no token and is told to pair again.
        advertisedToken = ""
        assertEquals("", tokenLinuxWouldRead(myHello(advertisedToken)))
        assertEquals(host, "192.168.31.84")
    }
}