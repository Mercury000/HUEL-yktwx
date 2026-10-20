package com.mercury.yktwx.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {
    val Payment = ImageVector.Builder("Payment", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
            moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
            moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
            moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
            moveTo(8f, 8f); lineTo(11f, 8f); lineTo(11f, 11f); lineTo(8f, 11f); close()
            moveTo(14f, 8f); lineTo(16f, 8f); lineTo(16f, 11f)
            moveTo(8f, 14f); lineTo(8f, 16f); lineTo(11f, 16f)
            moveTo(14f, 14f); lineTo(16f, 14f); lineTo(16f, 16f); lineTo(14f, 16f); close()
        }
    }.build()
    val About = ImageVector.Builder("About", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
            moveTo(12f, 11f); lineTo(12f, 17f)
            moveTo(12f, 7f); lineTo(12f, 8f)
        }
    }.build()
}
