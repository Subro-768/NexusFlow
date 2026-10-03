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
                    isError = code.isNotBlank() && !isPlausiblePairingCode(code),
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

                if (code.isNotBlank() && !isPlausiblePairingCode(code)) {
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
                        enabled = code.isNotBlank() && isPlausiblePairingCode(code),
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
 * Cheap shape check, so OK is disabled on obvious rubbish instead of failing
 * after the dialog closes.
 *
 * Deliberately permissive: `nexus://receive/<name>` has no port, a bare
 * `host:port` is accepted, and an `http://` URL is accepted. This is a typo
 * guard, not a parser -- the real parsing lives in applyPairingUri and must
 * never reject something it can actually handle.
 */
internal fun isPlausiblePairingCode(code: String): Boolean {
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return false
    // No spaces anywhere: every accepted form is a single URI or host:port.
    if (trimmed.any { it.isWhitespace() }) return false
    return trimmed.startsWith("nexus://") ||
        trimmed.startsWith("http://") ||
        trimmed.startsWith("https://") ||
        trimmed.matches(Regex("^[\\w.-]+(:\\d{1,5})?$"))
}