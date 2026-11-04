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
import top.yukonga.miuix.kmp.basic.TextField

/** Keep selection and IME composition locally when a parent echoes only the text. */
@Composable
internal fun DigitTextField(
    value: String, onValueChange: (String) -> Unit, label: String, maxDigits: Int,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    StableInputTextField(value, onValueChange, label, modifier, enabled, keyboardOptions, keyboardActions) { it.digitsOnly(maxDigits) }
}

/** The same cursor-preserving miuix field also accepts laundry's alphanumeric device codes. */
@Composable
internal fun DeviceCodeTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, keyboardActions: KeyboardActions = KeyboardActions.Default) {
    StableInputTextField(value, onValueChange, label, modifier, enabled,
        KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done), keyboardActions) { next ->
        if (next.text.length <= 256) next else next.copy(text = next.text.take(256),
            selection = TextRange(next.selection.start.coerceAtMost(256), next.selection.end.coerceAtMost(256)), composition = null)
    }
}

@Composable
private fun StableInputTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier,
    enabled: Boolean, keyboardOptions: KeyboardOptions, keyboardActions: KeyboardActions,
    transform: (TextFieldValue) -> TextFieldValue) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    var externalValue by remember { mutableStateOf(value) }
    if (value != externalValue) {
        externalValue = value
        if (draft.text != value) draft = TextFieldValue(value, TextRange(value.length))
    }
    TextField(draft, onValueChange = { next ->
        draft = transform(next)
        if (draft.text != value) onValueChange(draft.text)
    }, label = label, singleLine = true, useLabelAsPlaceholder = true, enabled = enabled,
        modifier = modifier.trackInputFocus(enabled), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
}

internal fun TextFieldValue.digitsOnly(maxDigits: Int): TextFieldValue {
    val digits = text.filter { it in '0'..'9' }.take(maxDigits)
    if (digits == text) return this
    fun offset(index: Int) = text.take(index).count { it in '0'..'9' }.coerceAtMost(maxDigits)
    fun range(value: TextRange) = TextRange(offset(value.start), offset(value.end))
    return copy(text = digits, selection = range(selection), composition = composition?.let(::range)?.takeUnless { it.collapsed })
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
    TextField(value = value, onValueChange = { draft ->
        val pattern = if (integerOnly) "[0-9]{0,$wholeDigits}" else "[0-9]{0,$wholeDigits}(\\.[0-9]{0,2})?"
        if (draft.matches(Regex(pattern))) onValueChange(draft)
    }, label = label, singleLine = true, useLabelAsPlaceholder = useLabelAsPlaceholder, enabled = enabled,
        modifier = modifier.trackInputFocus(enabled).onFocusChanged { onFocus(it.isFocused) },
        keyboardOptions = KeyboardOptions(keyboardType = if (integerOnly) KeyboardType.Number else KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }))
}
