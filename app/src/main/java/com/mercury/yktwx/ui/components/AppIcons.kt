package com.mercury.yktwx.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {
    val SaveRoom = ImageVector.Builder("SaveRoom", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(4f, 4f); lineTo(17f, 4f); lineTo(20f, 7f); lineTo(20f, 20f)
            lineTo(4f, 20f); close()
            moveTo(8f, 4f); lineTo(8f, 9f); lineTo(16f, 9f); lineTo(16f, 4f)
            moveTo(8f, 20f); lineTo(8f, 14f); lineTo(16f, 14f); lineTo(16f, 20f)
        }
    }.build()
    val UnbindRoom = ImageVector.Builder("UnbindRoom", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(9f, 14f); lineTo(7f, 16f); curveTo(5f, 18f, 2f, 15f, 4f, 13f); lineTo(7f, 10f)
            moveTo(15f, 10f); lineTo(17f, 8f); curveTo(19f, 6f, 22f, 9f, 20f, 11f); lineTo(17f, 14f)
            moveTo(10f, 7f); lineTo(9f, 4f)
            moveTo(14f, 17f); lineTo(15f, 20f)
            moveTo(3f, 3f); lineTo(21f, 21f)
        }
    }.build()
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
    val Logout = ImageVector.Builder("Logout", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(10f, 4f); lineTo(4f, 4f); lineTo(4f, 20f); lineTo(10f, 20f)
            moveTo(9f, 12f); lineTo(20f, 12f)
            moveTo(16f, 8f); lineTo(20f, 12f); lineTo(16f, 16f)
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
    val Chart = ImageVector.Builder("Chart", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(4f, 4f); lineTo(4f, 20f); lineTo(20f, 20f)
            moveTo(7f, 15f); lineTo(11f, 11f); lineTo(14f, 14f); lineTo(20f, 7f)
            moveTo(20f, 7f); lineTo(20f, 11f)
            moveTo(20f, 7f); lineTo(16f, 7f)
        }
    }.build()
    val Password = ImageVector.Builder("Password", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(7f, 10f); lineTo(7f, 7f); curveTo(7f, 1f, 17f, 1f, 17f, 7f); lineTo(17f, 10f)
            moveTo(4f, 10f); lineTo(20f, 10f); lineTo(20f, 21f); lineTo(4f, 21f); close()
            moveTo(12f, 14f); lineTo(12f, 17f)
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
