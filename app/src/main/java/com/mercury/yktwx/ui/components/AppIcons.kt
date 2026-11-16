package com.mercury.yktwx.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.glass.Flashlight
import top.yukonga.miuix.kmp.icon.glass.Keyboard
import top.yukonga.miuix.kmp.icon.glass.WiFi

object AppIcons {
    // Measured artwork bounds in a 24-unit box keep official and custom glyphs balanced.
    val CampusCard = MiuixIcons.BankCards.fitGlyph(2f, 4.25f, 20f, 15.5f)
    val QueryPassword = MiuixIcons.Lock.fitGlyph(4.05f, 2f, 15.9f, 20f)
    val Settings = MiuixIcons.Settings.fitGlyph(2.85f, 2.05f, 18.3f, 19.9f)
    val Info = MiuixIcons.Info.fitGlyph(2f, 2f, 20f, 20f)
    val Check = MiuixIcons.Ok.fitGlyph(2.025f, 3.35f, 19.95f, 17.275f)
    val Close = MiuixIcons.Close.fitGlyph(3.125f, 3f, 17.75f, 17.75f, extent = 16f)
    val DateDropdown = MiuixIcons.ChevronForward.fitGlyph(6.475f, 2f, 11.05f, 20f,
        extent = 16f, rotation = 90f)
    val Keyboard = MiuixIcons.Glass.Keyboard.fitGlyph(1.9f, 4.05f, 20.2f, 15.9f)
    val Flashlight = MiuixIcons.Glass.Flashlight.fitGlyph(6.275f, 1.775f, 11.45f, 20.45f)
    val Network = MiuixIcons.Glass.WiFi.fitGlyph(1.725f, 4.6f, 20.55f, 14.8f)

    val PaymentQrCode = ImageVector.Builder("PaymentQrCode", 24.dp, 24.dp, 24f, 24f).apply {
        // Dense QR artwork needs more inset than the surrounding navigation icons.
        group(scaleX = .85f, scaleY = .85f, translationX = 1.8f, translationY = 1.8f) {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
                roundedRect(3f, 3f, 6.8f, 6.8f, 1.45f)
                roundedRect(14.2f, 3f, 6.8f, 6.8f, 1.45f)
                roundedRect(3f, 14.2f, 6.8f, 6.8f, 1.45f)
            }
            path(fill = SolidColor(Color.Black)) {
                roundedRect(5.5f, 5.5f, 1.8f, 1.8f, .4f)
                roundedRect(16.7f, 5.5f, 1.8f, 1.8f, .4f)
                roundedRect(5.5f, 16.7f, 1.8f, 1.8f, .4f)
                moveTo(13.7f, 13.2f); lineTo(17.5f, 13.2f)
                quadTo(18f, 13.2f, 18f, 13.7f); lineTo(18f, 15f)
                quadTo(18f, 15.5f, 17.5f, 15.5f); lineTo(15.5f, 15.5f); lineTo(15.5f, 17.5f)
                quadTo(15.5f, 18f, 15f, 18f); lineTo(13.7f, 18f)
                quadTo(13.2f, 18f, 13.2f, 17.5f); lineTo(13.2f, 13.7f)
                quadTo(13.2f, 13.2f, 13.7f, 13.2f); close()
                roundedRect(19.1f, 13.2f, 2.3f, 2.3f, .5f)
                roundedRect(16.7f, 16.7f, 2.3f, 2.3f, .5f)
                roundedRect(13.2f, 19.1f, 2.3f, 2.3f, .5f)
                moveTo(19.6f, 19.1f); lineTo(20.9f, 19.1f)
                quadTo(21.4f, 19.1f, 21.4f, 19.6f); lineTo(21.4f, 20.9f)
                quadTo(21.4f, 21.4f, 20.9f, 21.4f); lineTo(17.1f, 21.4f)
                quadTo(16.6f, 21.4f, 16.6f, 20.9f); lineTo(16.6f, 19.6f)
                quadTo(16.6f, 19.1f, 17.1f, 19.1f); close()
            }
        }
    }.build()

    val Water = businessIcon("Water", 5f, 2.5f, 14f, 19.5f,
        filled = "M12 2.5C10.5 4.4 5 10.4 5 15a7 7 0 0 0 14 0c0-4.6-5.5-10.6-7-12.5Z " +
            "M7.4 14.1c-.4 2.2.5 4 2.3 4.8a.9.9 0 0 0 .8-1.6c-1.1-.5-1.6-1.5-1.3-2.9a.9.9 0 0 0-1.8-.3Z")

    val Laundry = businessIcon("Laundry", 2.25f, 1.5f, 19.5f, 21f,
        outlined = "M6.1 2.6h11.8a2.7 2.7 0 0 1 2.7 2.7v13.4a2.7 2.7 0 0 1-2.7 2.7H6.1a2.7 2.7 0 0 1-2.7-2.7V5.3a2.7 2.7 0 0 1 2.7-2.7Z " +
            "M3.6 7h16.8M6.8 4.8h1.5 " +
            "M16.7 14.1a4.7 4.7 0 1 1-9.4 0 4.7 4.7 0 0 1 9.4 0Z " +
            "M7.5 14.3c2-2.1 5 2.1 9 0")

    val Ride = businessIcon("Ride", .7f, 2.9f, 22.6f, 18.1f,
        outlined = "M9 16.3a3.6 3.6 0 1 1-7.2 0 3.6 3.6 0 0 1 7.2 0Z " +
            "M22.2 16.3a3.6 3.6 0 1 1-7.2 0 3.6 3.6 0 0 1 7.2 0Z " +
            "M5.4 16.3l4.1-8.1h6.3l-4 8.1H5.4l4.1-8.1 2.3 8.1M8.5 5.9h3.2M18.6 16.3 15.7 4h3")

    val AirConditioning = businessIcon("AirConditioning", 2.9f, 1.9f, 18.2f, 20.2f,
        outlined = "M12 3v18M4 7.4l16 9.2M4 16.6l16-9.2 " +
            "M9.3 5.2 12 7.9l2.7-2.7M9.3 18.8 12 16.1l2.7 2.7 " +
            "M4.5 10.7l3.7-1-1-3.7M16.8 18l-1-3.7 3.7-1 " +
            "M4.5 13.3l3.7 1-1 3.7M16.8 6l-1 3.7 3.7 1")

    val Electricity = businessIcon("Electricity", 4.7f, 2.7f, 14.6f, 18.6f,
        filled = "M13 3 5 13h6l-1 8 9-11h-6Z",
        outlined = "M13 3 5 13h6l-1 8 9-11h-6Z", strokeWidth = .6f)

    val Bill = businessIcon("Bill", 4.1f, 1.6f, 15.8f, 20.3f,
        outlined = "M5.2 3.7q0-1 1-1h11.6q1 0 1 1v17.1l-2.7-1.7-2.1 1.7-2.1-1.7-2.1 1.7-2.1-1.7-2.5 1.7Z " +
            "M8.6 7.9h6.8M8.6 12h6.8M8.6 16.1H13")

    val Pending = businessIcon("Pending", 2.9f, 2.9f, 18.2f, 18.2f,
        outlined = "M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0ZM12 7v5l3.5 2")

    val CardState = businessIcon("CardState", 1.9f, 3.9f, 20.2f, 16.2f,
        outlined = "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z " +
            "M3 9h18M6.5 14h3M14 13l4 4M18 13l-4 4")

    val OthersRecharge = businessIcon("OthersRecharge", 1.4f, 2.4f, 21.2f, 19.2f,
        outlined = "M10.5 6.7a3.2 3.2 0 1 1-6.4 0 3.2 3.2 0 0 1 6.4 0Z " +
            "M2.5 20.5v-3.1a4.8 4.8 0 0 1 9.6 0v3.1 " +
            "M14.4 11.8h7.1M18.3 8.6l3.2 3.2-3.2 3.2M15 19.5h6.5")
}

/** All business glyphs occupy at most 18 units; outlined details share rounded joins. */
private fun businessIcon(name: String, left: Float, top: Float, width: Float, height: Float,
    filled: String? = null, outlined: String? = null, strokeWidth: Float = 2.2f): ImageVector {
    val scale = 18f / maxOf(width, height)
    return ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        group(scaleX = scale, scaleY = scale,
            translationX = 12f - (left + width / 2f) * scale,
            translationY = 12f - (top + height / 2f) * scale) {
            if (filled != null) addPath(pathData = PathParser().parsePathString(filled).toNodes(),
                fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
            if (outlined != null) addPath(pathData = PathParser().parsePathString(outlined).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
}

/** Preserve official artwork while matching its visible extent to the business glyphs. */
private fun ImageVector.fitGlyph(left: Float, top: Float, width: Float, height: Float,
    extent: Float = 18f, rotation: Float = 0f): ImageVector {
    val source = this
    val scale = extent / maxOf(width, height)
    return ImageVector.Builder("$name.Business", 24.dp, 24.dp, 24f, 24f,
        tintColor = tintColor, tintBlendMode = tintBlendMode, autoMirror = autoMirror).apply {
        group(rotate = rotation, pivotX = 12f, pivotY = 12f) {
            group(scaleX = scale * 24f / source.viewportWidth, scaleY = scale * 24f / source.viewportHeight,
                translationX = 12f - (left + width / 2f) * scale,
                translationY = 12f - (top + height / 2f) * scale) {
                copyGroup(source.root)
            }
        }
    }.build()
}

private fun ImageVector.Builder.copyGroup(source: VectorGroup) {
    group(name = source.name, rotate = source.rotation, pivotX = source.pivotX, pivotY = source.pivotY,
        scaleX = source.scaleX, scaleY = source.scaleY, translationX = source.translationX,
        translationY = source.translationY, clipPathData = source.clipPathData) {
        for (node in source) when (node) {
            is VectorGroup -> copyGroup(node)
            is VectorPath -> addPath(pathData = node.pathData, pathFillType = node.pathFillType,
                name = node.name, fill = node.fill, fillAlpha = node.fillAlpha,
                stroke = node.stroke, strokeAlpha = node.strokeAlpha, strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap, strokeLineJoin = node.strokeLineJoin,
                strokeLineMiter = node.strokeLineMiter, trimPathStart = node.trimPathStart,
                trimPathEnd = node.trimPathEnd, trimPathOffset = node.trimPathOffset)
        }
    }
}

private fun PathBuilder.roundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float) {
    moveTo(x + radius, y)
    lineTo(x + width - radius, y)
    arcTo(radius, radius, 0f, false, true, x + width, y + radius)
    lineTo(x + width, y + height - radius)
    arcTo(radius, radius, 0f, false, true, x + width - radius, y + height)
    lineTo(x + radius, y + height)
    arcTo(radius, radius, 0f, false, true, x, y + height - radius)
    lineTo(x, y + radius)
    arcTo(radius, radius, 0f, false, true, x + radius, y)
    close()
}
