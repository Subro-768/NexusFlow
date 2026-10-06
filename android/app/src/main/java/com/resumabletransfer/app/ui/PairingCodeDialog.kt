package com.resumabletransfer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.resumabletransfer.app.R

/**
 * Themed pairing-code entry, the fallback for when the camera is unusable.
 *
 * This used to be an `android.app.AlertDialog` with a bare `EditText`, which
 * rendered in the *platform* theme: a white box with teal buttons sitting in the
 * middle of a dark Solora screen. Nothing else in the app looks like that, and
 * on a dark UI a white modal is glaring enough to look like an error.
 *
 * Building it in Compose means it inherits the app's palette, shape and type
 * scale automatically, and keeps inheriting them if those change.
 */
@Composable
fun PairingCodeDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SoloraSurfaceCard,
            border = BorderStroke(1.dp, SoloraBorder),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.pairing_dialog_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = SoloraTextPrimary
                )
                Text(
                    text = stringResource(R.string.pairing_dialog_message),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = SoloraTextSecondary
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    isError = code.isNotBlank() && !PairingCode.isPlausible(code),
                    placeholder = {
                        Text(
                            "nexus://192.168.1.5:8000",
                            color = SoloraTextMuted,
                            fontSize = 13.sp
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = SoloraTextPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoloraCyan,
                        unfocusedBorderColor = SoloraBorder,
                        focusedTextColor = SoloraTextPrimary,
                        unfocusedTextColor = SoloraTextPrimary,
                        cursorColor = SoloraCyan,
                        errorBorderColor = SoloraAlertRed,
                        errorCursorColor = SoloraAlertRed,
                        focusedContainerColor = SoloraSurfaceElevated,
                        unfocusedContainerColor = SoloraSurfaceElevated,
                        errorContainerColor = SoloraSurfaceElevated
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (code.isNotBlank() && !PairingCode.isPlausible(code)) {
                    Text(
                        text = stringResource(R.string.pairing_dialog_bad_code),
                        fontSize = 11.sp,
                        color = SoloraAlertRed
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.cancel),
                            color = SoloraTextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { onSubmit(code.trim()) },
                        enabled = code.isNotBlank() && PairingCode.isPlausible(code),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoloraCyan,
                            contentColor = SoloraBgDark,
                            disabledContainerColor = SoloraSurfaceElevated,
                            disabledContentColor = SoloraTextMuted
                        ),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.pairing_dialog_connect),
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Every pairing-code form this app accepts, in one place.
 *
 * There are three of them, and they differ only in their prefix:
 *
 *   nexus://host:port[?t=TOKEN]   what the desktop Receiver screen puts in its QR
 *   http://host:port[?t=TOKEN]    a URL copied out of a browser bar
 *   host:port[?t=TOKEN]           what someone actually types
 *
 * The optional `?t=` carries the receiver's pairing token, so a device with no
 * working camera can still authenticate: read the 8 characters off the
 * receiver's screen, type them here, and the transfer is authorised.
 *
 * ## Why this exists
 *
 * The validator that used to live here and the parser in `MainActivity` had
 * drifted apart, and they disagreed on the most important case:
 *
 *  * the validator allowed a bare `host:port` but not a `?t=` suffix, so a
 *    hand-typed token was rejected as "does not look like a pairing code" --
 *    while the desktop side has always accepted exactly that form, and
 *    `applyPairingUri` reads `?t=` from any form;
 *  * the validator allowed bare `host:port`, but `applyPairingUri` had no branch
 *    for it and fell through to `host = null`, so the dialog accepted it and
 *    the app then refused it with "unrecognised".
 *
 * Both bugs were found the same way: typing a code by hand on a device whose
 * camera could not be used, on a network where discovery does not work.
 *
 * Parsing now happens once, here, and `applyPairingUri` uses these results
 * directly. A form that validates is a form that parses, so the two cannot
 * disagree again.
 */
internal object PairingCode {

    private val SCHEMES = listOf("nexus://", "http://", "https://")

    data class Target(val host: String, val port: Int?, val token: String?)

    /**
     * Parse a typed or pasted pairing code.
     *
     * Returns null for anything unrecognisable, which is what the dialog turns
     * into "that does not look like a pairing code" and what the QR path turns
     * into "unrecognised".
     */
    fun parse(code: String): Target? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        // No spaces anywhere: every accepted form is a single URI or host:port.
        if (trimmed.any { it.isWhitespace() }) return null

        val scheme = SCHEMES.firstOrNull { trimmed.startsWith(it) }
        // Everything after the scheme. Only the `//` that belongs to the
        // scheme is removed: a code like `nexus:///path` has no host at all and
        // must be rejected rather than turning "path" into one.
        val rest = if (scheme != null) {
            trimmed.removePrefix(scheme).removePrefix("//")
        } else {
            trimmed
        }
        // The query is optional, and only `t=` matters; a name-only code
        // (`nexus://receive/DESKTOP`) is handled by the caller, not here.
        val authority = rest.substringBefore('?').trimEnd('/')
        val query = rest.substringAfter('?', "")
        if (authority.isEmpty()) return null

        // The path, if any, is not part of the address and is dropped: these
        // codes are host:port plus a token, never a URL with a resource. Split
        // it off before looking for a port, or `host:8000/path` would make the
        // port unparseable.
        val withoutPath = authority.substringBefore('/')
        val host = withoutPath.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
        val portPart = withoutPath.substringAfter(':', "")
        val port = portPart.takeIf { it.isNotEmpty() }?.toIntOrNull()

        // A port that is present but not a number is a typo, not a default -- and a
        // number outside the legal range is a typo too, not something to clamp.
        if (portPart.isNotEmpty() && (port == null || port !in 1..65535)) return null

        return Target(host, port, query.tokenOrNull())
    }

    /** Cheap shape check, so OK is disabled on obvious rubbish. */
    fun isPlausible(code: String): Boolean = parse(code) != null

    /**
     * Pull `?t=` out of a pairing payload.
     *
     * Kept for the QR path, which must not fail on a payload this app happens
     * to be unable to parse: an unparseable code still needs to be searched for
     * by name rather than silently rejected.
     */
    fun tokenFrom(uri: String): String? {
        val query = uri.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split("&")
            .firstOrNull { it.startsWith("t=") }
            ?.substringAfter("t=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun String.tokenOrNull(): String? =
        split("&")
            .firstOrNull { it.startsWith("t=") }
            ?.substringAfter("t=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}