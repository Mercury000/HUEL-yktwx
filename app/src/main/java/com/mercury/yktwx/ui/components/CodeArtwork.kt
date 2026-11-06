package com.mercury.yktwx.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Each current code owns its artwork; replacing it never displays a retained older code. */
@Composable
internal fun CodeArtwork(code: String, format: BarcodeFormat, width: Int, height: Int, modifier: Modifier, description: String) {
    val result = remember(code, format, width, height) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var bitmap by result
    LaunchedEffect(result) {
        bitmap = withContext(Dispatchers.Default) {
            val matrix = encodePaymentCode(code, format, width, height)
            val pixels = IntArray(width * height) { index ->
                if (matrix[index % width, index / width]) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT
            }
            android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }
    val shape = RoundedCornerShape(if (format == BarcodeFormat.QR_CODE) 16.dp else 6.dp)
    Box(modifier.background(Color.White, shape), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = description, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None) }
    }
}
