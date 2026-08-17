package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code, rendered dark-on-light.
 *
 * Deliberately *not* in the HUD's own colours. A QR code is read by assuming
 * its modules are darker than its background, and a glowing cyan code on a
 * near-black panel is an inverted one — which plenty of scanners, ZXing's own
 * default decoder included, will not read at all. So the code itself keeps
 * normal polarity, tinted towards the palette but no further, and the HUD
 * framing goes around it.
 */
private val QR_LIGHT = Color(0xFFE8FBFF)
private val QR_DARK = Color(0xFF0B0E1A)

/** Pixel size of the generated bitmap. Drawn unfiltered, so it stays crisp. */
private const val QR_BITMAP_SIZE = 512

@Composable
fun HudQrCode(
    content: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    // Encoding is pure and not cheap; it only changes when the code does.
    val image = remember(content) { encodeQr(content) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .hudGlow(HudCyan, rings = 2),
    ) {
        Image(
            painter = BitmapPainter(image, filterQuality = FilterQuality.None),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
    }
}

private fun encodeQr(content: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        QR_BITMAP_SIZE,
        QR_BITMAP_SIZE,
        mapOf(
            // A quiet zone of one module rather than the default four: the
            // panel around it already provides the margin a scanner needs,
            // and four wastes a third of the width on a phone screen.
            EncodeHintType.MARGIN to 1,
            // Q tolerates a screen being photographed at an angle, in the
            // rain, on a bike. The payload is 30 characters — there is room.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
        ),
    )

    val light = QR_LIGHT.toArgb()
    val dark = QR_DARK.toArgb()
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val row = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[row + x] = if (matrix.get(x, y)) dark else light
        }
    }

    return android.graphics.Bitmap
        .createBitmap(pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}
