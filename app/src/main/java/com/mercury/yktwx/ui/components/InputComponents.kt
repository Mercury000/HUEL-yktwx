package com.mercury.yktwx.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import top.yukonga.miuix.kmp.basic.TextField

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
