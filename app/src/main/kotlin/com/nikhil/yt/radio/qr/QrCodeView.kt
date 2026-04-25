/*
 * Velune Web Radio — QrCodeView
 *
 * Compose canvas renderer for [QrCode] matrices. High-contrast by default,
 * follows the active Material colour scheme, includes a quiet zone, and
 * exposes the encoded text via the semantics tree so TalkBack reads it.
 */

package com.nikhil.yt.radio.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Render a QR code for [text].
 *
 * @param text          payload — typically a https URL
 * @param sizeDp        side length, including the quiet zone
 * @param ecc           error correction level. Medium is a good default; bump to High
 *                      if the QR will be printed and/or partially obscured (logo, etc.)
 * @param contentDesc   accessible description; defaults to the text itself
 */
@Composable
fun QrCodeView(
    text: String,
    sizeDp: Dp = 220.dp,
    ecc: QrEcc = QrEcc.Medium,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    background: Color = MaterialTheme.colorScheme.surface,
    contentDesc: String? = null,
    modifier: Modifier = Modifier,
) {
    val qr = remember(text, ecc) {
        runCatching { QrEncoder.encode(text, ecc) }.getOrNull()
    }
    val sem = Modifier.semantics {
        contentDescription = contentDesc ?: "QR code for $text"
    }
    Canvas(
        modifier = modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(sem),
    ) {
        if (qr == null) return@Canvas
        drawQr(qr, foreground)
    }
}

private fun DrawScope.drawQr(qr: QrCode, foreground: Color) {
    // Quiet zone of 4 modules per spec — done by drawing modules into the inner area.
    val quiet = 4
    val total = qr.size + quiet * 2
    val module = minOf(size.width, size.height) / total
    val originX = (size.width - module * total) / 2f
    val originY = (size.height - module * total) / 2f
    for (y in 0 until qr.size) {
        for (x in 0 until qr.size) {
            if (qr.get(x, y)) {
                val px = originX + (x + quiet) * module
                val py = originY + (y + quiet) * module
                drawRect(
                    color = foreground,
                    topLeft = Offset(px, py),
                    size = Size(module + 0.5f, module + 0.5f), // half-pixel overlap eliminates seams
                )
            }
        }
    }
}
