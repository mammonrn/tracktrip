package app.ptrip.tracktrip.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudTopBar
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Points the camera at someone else's invite QR.
 *
 * The scan is handed straight to the server rather than confirmed first: a
 * rider is standing in front of the phone they scanned, and a "are you sure"
 * step between them and the trip is a step for nobody.
 */
@Composable
fun ScanQrScreen(
    joining: Boolean,
    error: String?,
    onCodeScanned: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }

    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!granted) requestCamera.launch(Manifest.permission.CAMERA)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        HudTopBar(
            title = stringResource(R.string.qr_scan_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
        )

        error?.let { HudError(it) }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !granted -> CameraDenied(
                    askedAlready = asked,
                    onRetry = { requestCamera.launch(Manifest.permission.CAMERA) },
                )

                joining -> HudLoading()

                else -> CameraPreview(onCodeScanned = onCodeScanned)
            }
        }

        Text(
            text = stringResource(R.string.qr_scan_hint),
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        )
    }
}

@Composable
private fun CameraDenied(askedAlready: Boolean, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(
                if (askedAlready) R.string.qr_camera_denied else R.string.qr_camera_needed
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        HudPrimaryButton(text = stringResource(R.string.qr_camera_allow), onClick = onRetry)
    }
}

/**
 * ZXing's camera preview, wrapped for Compose.
 *
 * `decodeContinuous`, not `decodeSingle`: the first thing in frame is often
 * some other QR code entirely, and a scanner that stopped on the first
 * unrecognised one would need the rider to back out and start again. Instead
 * anything that isn't a join link is ignored and scanning carries on.
 */
@Composable
private fun CameraPreview(onCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    var handled by remember { mutableStateOf(false) }

    val barcodeView = remember {
        BarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        }
    }

    DisposableEffect(barcodeView) {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (handled) return
                val code = joinCodeFrom(result.text) ?: return
                handled = true
                barcodeView.pause()
                onCodeScanned(code)
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
        })
        barcodeView.resume()

        onDispose {
            barcodeView.pause()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { barcodeView }, modifier = Modifier.fillMaxSize())
        Viewfinder()
    }
}

/**
 * Four corner brackets: "aim here".
 *
 * Drawn in the accent blue rather than in white — white brackets vanish
 * against a bright wall or a phone screen, which is most of what a rider
 * points this at.
 */
@Composable
private fun Viewfinder() {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(1f)
            .drawBehind {
                val arm = size.minDimension * 0.18f
                val stroke = 3.dp.toPx()

                fun bracket(corner: Offset, dx: Float, dy: Float) {
                    drawLine(
                        AppPrimary,
                        corner,
                        Offset(corner.x + arm * dx, corner.y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        AppPrimary,
                        corner,
                        Offset(corner.x, corner.y + arm * dy),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }

                bracket(Offset(0f, 0f), 1f, 1f)
                bracket(Offset(size.width, 0f), -1f, 1f)
                bracket(Offset(0f, size.height), 1f, -1f)
                bracket(Offset(size.width, size.height), -1f, -1f)
            },
    )
}
