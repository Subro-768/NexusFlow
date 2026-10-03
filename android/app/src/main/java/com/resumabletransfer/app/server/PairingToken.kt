package com.resumabletransfer.app.server

import java.security.SecureRandom

/**
 * Pairing token for a receiver, and the constant-time check for it.
 *
 * ## Why
 *
 * The embedded receiver answers plain HTTP to whoever asks, on whatever network
 * it is on. Without a credential, any device on the same Wi-Fi could list what
 * this phone is receiving and write files into it. For a project whose premise is
 * "HTTP across a local network" that is the first question anyone technical asks,
 * so it is answered in code rather than in a paragraph.
 *
 * ## What it does not do
 *
 * The token is deliberately **not** fetched over the network. Discovery is UDP
 * broadcast: anything that can ask "who is out there?" can read the answer, so a
 * token served from `/health` or the discovery reply would be a published token.
 * It travels out of band only — in the QR the camera reads off the other screen,
 * or typed by a human from this one.
 *
 * That is a real limitation, not a solved problem. It authenticates *who may talk
 * to this receiver*, and it assumes the network is one you control. It is not
 * encryption, and it does not stop someone who can read your screen.
 *
 * ## Shape
 *
 * Eight characters from a 30-symbol alphabet with `0 O 1 I L` removed, so it
 * survives being read aloud or copied off a screen by hand.
 */
object PairingToken {

    /** Excluded because they are indistinguishable when spoken or transcribed. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8

    /** Header carrying the token. Prefixed to avoid colliding with a proxy's. */
    const val HEADER = "X-NexusFlow-Token"

    /** Query-parameter form, for pairing codes that are typed or pasted. */
    const val QUERY_PARAM = "t"

    private val random = SecureRandom()

    /** A fresh token, regenerated each time the receiver starts. */
    fun generate(): String = buildString {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /**
     * Constant-time comparison of a supplied token against the expected one.
     *
     * [MessageDigest.isEqual] is used rather than `==`: string comparison returns
     * at the first differing character, which leaks the prefix length of a guess
     * through timing. The lengths here are not secret, but the habit is the
     * point — a credential check that is written correctly once is not something
     * to re-audit per call site.
     */
    fun matches(expected: String?, supplied: String?): Boolean {
        if (expected.isNullOrEmpty() || supplied.isNullOrEmpty()) return false
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = supplied.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(a, b)
    }

    /**
     * Pull a token out of whatever a parsed request is carrying: the header
     * first, then the query parameter, so either convention authenticates.
     */
    fun from(headers: Map<String, String>, query: String?): String? {
        headers[HEADER.lowercase()]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (query.isNullOrEmpty()) return null
        return query.split("&")
            .firstOrNull { it.startsWith("$QUERY_PARAM=") }
            ?.substringAfter("$QUERY_PARAM=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}