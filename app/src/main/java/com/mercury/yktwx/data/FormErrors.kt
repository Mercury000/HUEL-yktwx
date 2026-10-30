package com.mercury.yktwx.data

/** Fields that invalidate a rejection of an earlier input. Empty scopes remain until retry. */
enum class FormField { Username, Password, OldPassword, NewPassword, Confirmation, Recipient, Captcha, Amount, PaymentMethod, PaymentAccount }

interface ScopedInputError { val fields: Set<FormField> }

internal class InputRejected(message: String, override val fields: Set<FormField>) : YktException(message), ScopedInputError

internal fun inputErrorFields(error: Exception): Set<FormField> = when (error) {
    is InvalidCredentials -> setOf(FormField.Username, FormField.Password)
    is ScopedInputError -> error.fields
    else -> emptySet()
}
