package com.mercury.yktwx.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** Nested controls use tint over the glass sheet, without another backdrop or text refraction. */
@Composable
internal fun glassControlFill(): Color {
    return GlassDialogButtonDefaults.buttonColors().color
}

@Composable
internal fun appearanceTextFieldColors(): TextFieldColors = if (LocalGlassOverlay.current)
    TextFieldDefaults.textFieldColors(backgroundColor = glassControlFill())
else TextFieldDefaults.textFieldColors()

@Composable
private fun overlayButtonColors(colors: ButtonColors): ButtonColors = when {
    colors == ButtonDefaults.buttonColors() -> GlassDialogButtonDefaults.buttonColors()
    colors == ButtonDefaults.buttonColorsPrimary() ->
        GlassDialogButtonDefaults.buttonColors(GlassDialogButtonRole.Primary)
    else -> colors
}

@Composable
internal fun AppButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius, minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight, colors: ButtonColors = ButtonDefaults.buttonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin, interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current, content: @Composable RowScope.() -> Unit,
) {
    if (LocalGlassOverlay.current) {
        GlassDialogButton(onClick = onClick, modifier = modifier, enabled = enabled, cornerRadius = cornerRadius,
            minWidth = minWidth, minHeight = minHeight, colors = overlayButtonColors(colors),
            insideMargin = insideMargin, interactionSource = interactionSource, content = content)
    } else Button(onClick, modifier, enabled, cornerRadius, minWidth, minHeight, colors, insideMargin,
        interactionSource, indication, content)
}

@Composable
internal fun AppTextButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius, minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight, colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin, textStyle: TextStyle? = null,
    interactionSource: MutableInteractionSource? = null, indication: Indication? = LocalIndication.current,
) {
    if (LocalGlassOverlay.current) {
        val buttonColors = ButtonColors(colors.color, colors.disabledColor, colors.textColor, colors.disabledTextColor)
        AppButton(onClick, modifier, enabled, cornerRadius, minWidth, minHeight, buttonColors, insideMargin,
            interactionSource, indication) { Text(text, style = textStyle ?: MiuixTheme.textStyles.button) }
    } else TextButton(text, onClick, modifier, enabled, cornerRadius, minWidth, minHeight, colors, insideMargin,
        textStyle, interactionSource, indication)
}

/** Preserve page cards and custom semantic colors; only nested neutral cards change their fill. */
@Composable
internal fun AppCard(
    modifier: Modifier = Modifier, cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin, colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None, showIndication: Boolean = false,
    holdDownState: Boolean = false, onClick: (() -> Unit)? = null, onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val neutral = colors.color == CardDefaults.defaultColors().color || colors.color == DashboardPalette.field
    val resolved = if (LocalGlassOverlay.current && neutral) colors.copy(color = glassControlFill()) else colors
    Card(modifier, cornerRadius, insideMargin, resolved, pressFeedbackType, showIndication, holdDownState,
        onClick, onLongPress, content)
}
