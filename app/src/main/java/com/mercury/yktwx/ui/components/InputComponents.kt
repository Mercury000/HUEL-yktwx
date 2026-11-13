package com.mercury.yktwx.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import top.yukonga.miuix.kmp.basic.TextField

/** Keep selection and IME composition locally when a parent echoes only the text. */
@Composable
internal fun DigitTextField(
    value: String, onValueChange: (String) -> Unit, label: String, maxDigits: Int,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    useLabelAsPlaceholder: Boolean = true,
) {
    StableTextField(value, onValueChange, label, modifier, enabled, keyboardOptions, keyboardActions,
        visualTransformation = visualTransformation, useLabelAsPlaceholder = useLabelAsPlaceholder) { it.digitsOnly(maxDigits) }
}

/** Recipient identifiers retain cursor offsets when pasted characters are filtered. */
@Composable
internal fun RecipientIdentifierTextField(
    value: String, onValueChange: (String) -> Unit, label: String, allowX: Boolean,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    StableTextField(value, onValueChange, label, modifier, enabled,
        KeyboardOptions(keyboardType = if (allowX) KeyboardType.Ascii else KeyboardType.Number),
        KeyboardActions.Default, useLabelAsPlaceholder = false) { next ->
        next.filteredCharacters(32) { it in '0'..'9' || (allowX && it in "Xx") }
    }
}

/** Captchas accept letters while retaining the same local cursor and IME state. */
@Composable
internal fun CaptchaTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, maxCharacters: Int = 8,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
    useLabelAsPlaceholder: Boolean = true) {
    StableTextField(value, onValueChange, label, modifier, enabled,
        keyboardOptions, KeyboardActions.Default, useLabelAsPlaceholder = useLabelAsPlaceholder) { next ->
        if (next.text.length <= maxCharacters) next else next.copy(text = next.text.take(maxCharacters),
            selection = TextRange(next.selection.start.coerceAtMost(maxCharacters), next.selection.end.coerceAtMost(maxCharacters)),
            composition = null)
    }
}

/** The same cursor-preserving miuix field also accepts laundry's alphanumeric device codes. */
@Composable
internal fun DeviceCodeTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, keyboardActions: KeyboardActions = KeyboardActions.Default) {
    StableTextField(value, onValueChange, label, modifier, enabled,
        KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done), keyboardActions, saveText = true) { next ->
        if (next.text.length <= 256) next else next.copy(text = next.text.take(256),
            selection = TextRange(next.selection.start.coerceAtMost(256), next.selection.end.coerceAtMost(256)), composition = null)
    }
}

@Composable
internal fun StableTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    saveText: Boolean = false,
    useLabelAsPlaceholder: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    acceptText: (String) -> Boolean = { true },
    transform: (TextFieldValue) -> TextFieldValue = { it }) {
    // Passwords and codes must not survive through a text field's private state. Safe device input
    // can save its cursor; phone/recipient drafts are restored by their owning model.
    val draftState = if (saveText) rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    } else remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var draft by draftState
    var externalValue by remember { mutableStateOf(value) }
    if (value != externalValue) {
        externalValue = value
        // Parent acknowledgements keep selection/composition; resets and presets replace the draft.
        if (draft.text != value) draft = TextFieldValue(value, TextRange(value.length))
    }
    TextField(draft, onValueChange = { next ->
        val transformed = transform(next)
        if (acceptText(transformed.text)) {
            draft = transformed
            if (draft.text != value) onValueChange(draft.text)
        }
    }, label = label, singleLine = true, useLabelAsPlaceholder = useLabelAsPlaceholder, enabled = enabled,
        modifier = modifier.trackInputFocus(enabled), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = visualTransformation, trailingIcon = trailingIcon)
}

internal fun TextFieldValue.digitsOnly(maxDigits: Int): TextFieldValue =
    filteredCharacters(maxDigits) { it in '0'..'9' }

private fun TextFieldValue.filteredCharacters(maxCharacters: Int, accept: (Char) -> Boolean): TextFieldValue {
    val filtered = text.filter(accept).take(maxCharacters)
    if (filtered == text) return this
    fun offset(index: Int) = text.take(index).count(accept).coerceAtMost(maxCharacters)
    fun range(value: TextRange) = TextRange(offset(value.start), offset(value.end))
    return copy(text = filtered, selection = range(selection), composition = composition?.let(::range)?.takeUnless { it.collapsed })
}

/** Accept unfinished amounts while editing; payment rules still validate integral cents. */
@Composable
internal fun AmountTextField(
    value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, integerOnly: Boolean = false, wholeDigits: Int = 8,
    imeAction: ImeAction = ImeAction.Done, onFocus: (Boolean) -> Unit = {},
    useLabelAsPlaceholder: Boolean = true,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val pattern = if (integerOnly) "[0-9]{0,$wholeDigits}" else "[0-9]{0,$wholeDigits}(\\.[0-9]{0,2})?"
    val accepted = remember(pattern) { Regex(pattern) }
    StableTextField(value = value, onValueChange = onValueChange,
        label = label, useLabelAsPlaceholder = useLabelAsPlaceholder, enabled = enabled,
        modifier = modifier.onFocusChanged { onFocus(it.isFocused) }, acceptText = { accepted.matches(it) },
        keyboardOptions = KeyboardOptions(keyboardType = if (integerOnly) KeyboardType.Number else KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }))
}
