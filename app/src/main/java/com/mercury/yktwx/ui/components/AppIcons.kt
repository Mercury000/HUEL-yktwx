package com.mercury.yktwx.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {

    val DateDropdown = ImageVector.Builder("DateDropdown", 16.dp, 16.dp, 16f, 16f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
            moveTo(3.5f, 5.5f); lineTo(8f, 10f); lineTo(12.5f, 5.5f)
        }
    }.build()
    val AirConditioning = ImageVector.Builder("AirConditioning", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f) {
            moveTo(12f, 3f); lineTo(12f, 21f)
            moveTo(4.2f, 7.5f); lineTo(19.8f, 16.5f)
            moveTo(4.2f, 16.5f); lineTo(19.8f, 7.5f)
            moveTo(9f, 5f); lineTo(12f, 8f); lineTo(15f, 5f)
            moveTo(9f, 19f); lineTo(12f, 16f); lineTo(15f, 19f)
            moveTo(4.4f, 11f); lineTo(8.4f, 9.9f); lineTo(7.3f, 5.8f)
            moveTo(16.7f, 18.2f); lineTo(15.6f, 14.1f); lineTo(19.6f, 13f)
            moveTo(7.3f, 18.2f); lineTo(8.4f, 14.1f); lineTo(4.4f, 13f)
            moveTo(19.6f, 11f); lineTo(15.6f, 9.9f); lineTo(16.7f, 5.8f)
        }
    }.build()

    val Electricity = ImageVector.Builder("Electricity", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(13f, 3f); lineTo(5f, 13f); lineTo(11f, 13f)
            lineTo(10f, 21f); lineTo(19f, 10f); lineTo(13f, 10f); close()
        }
    }.build()
    val Water = ImageVector.Builder("Water", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(12f, 3f); curveTo(9f, 7f, 5f, 11f, 5f, 15f)
            curveTo(5f, 23f, 19f, 23f, 19f, 15f)
            curveTo(19f, 11f, 15f, 7f, 12f, 3f); close()
        }
    }.build()
    val Network = ImageVector.Builder("Network", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(3f, 8f); curveTo(8f, 3f, 16f, 3f, 21f, 8f)
            moveTo(6f, 12f); curveTo(9f, 9f, 15f, 9f, 18f, 12f)
            moveTo(9f, 16f); curveTo(11f, 14f, 13f, 14f, 15f, 16f)
            moveTo(12f, 19f); lineTo(12f, 20f)
        }
    }.build()
    val Hall = ImageVector.Builder("Hall", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
            moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
            moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
        }
    }.build()
    val Bill = ImageVector.Builder("Bill", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f)
            lineTo(16.5f, 19f); lineTo(14f, 21f); lineTo(11.5f, 19f); lineTo(9f, 21f)
            lineTo(6.5f, 19f); lineTo(5f, 20.5f); close()
            moveTo(8f, 8f); lineTo(16f, 8f)
            moveTo(8f, 12f); lineTo(16f, 12f)
            moveTo(8f, 16f); lineTo(13f, 16f)
        }
    }.build()

    val CardState = ImageVector.Builder("CardState", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
            moveTo(3f, 9f); lineTo(21f, 9f)
            moveTo(7f, 14f); lineTo(10f, 14f)
            moveTo(14f, 13f); lineTo(18f, 17f); moveTo(18f, 13f); lineTo(14f, 17f)
        }
    }.build()
    val OthersRecharge = ImageVector.Builder("OthersRecharge", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(10f, 5f); curveTo(10f, 1f, 4f, 1f, 4f, 5f); curveTo(4f, 9f, 10f, 9f, 10f, 5f); close()
            moveTo(2f, 17f); lineTo(2f, 14f); curveTo(2f, 10f, 12f, 10f, 12f, 14f); lineTo(12f, 17f)
            moveTo(14f, 10f); lineTo(22f, 10f); moveTo(18f, 6f); lineTo(22f, 10f); lineTo(18f, 14f)
            moveTo(15f, 19f); lineTo(22f, 19f)
        }
    }.build()

}
