package com.mercury.yktwx.data

/** No sensitive object ever renders a password, token or payment code through toString(). */
class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials([redacted])"
}
class Session(val accessToken: String, val expiresAtMillis: Long) {
    fun isFresh(now: Long = System.currentTimeMillis()) = now + 60_000 < expiresAtMillis
    override fun toString() = "Session([redacted])"
}
class PaymentAccount(val account: String, val payacc: String, val paytype: String, val name: String, val balanceCents: Long)
class PaymentBatch(val codes: List<String>, private val expiresSeconds: Long, val account: PaymentAccount) {
    // Match the official frontend's 60s display interval; never infer offline usability.
    val displayIntervalSeconds get() = minOf(60L, expiresSeconds).coerceAtLeast(1)
    override fun toString() = "PaymentBatch([redacted])"
}
open class YktException(message: String) : Exception(message)
class AuthenticationRequired(message: String = "登录已失效，请重新登录") : YktException(message)
class InvalidCredentials : YktException("学号或密码无效，请重新输入")
class CaptchaRequired : YktException("官网要求验证码，请在官方页面完成验证后重新登录")
class NetworkFailure(message: String = "网络连接失败，请检查网络后刷新") : YktException(message)
/** Account presentation remains usable even when the code endpoint declines or fails. */
enum class PaymentCodeProblem { Unavailable, Lost, Frozen, StatusUnverified }
internal class PaymentCodeUnavailable(
    val account: PaymentAccount,
    message: String,
    val problem: PaymentCodeProblem = PaymentCodeProblem.Unavailable,
) : YktException(message)

object KeyboardEncoder {
    val layouts = linkedMapOf(
        "numberKeyboard" to "0123456789",
        "lowerLetterKeyboard" to "qwertyuiopasdfghjklzxcvbnm",
        "upperLetterKeyboard" to "QWERTYUIOPASDFGHJKLZXCVBNM",
        "symbolKeyboard" to "*\\-[]{}/!,<>?~&@#.:+|`%'$;^\"_",
    )
    fun validate(password: String) {
        if (password.isEmpty() || password.any { char -> layouts.values.none { char in it } })
            throw YktException("密码包含官网安全键盘不支持的字符")
    }
    fun encode(password: String, mapping: Map<String, String>, uuid: String): String {
        validate(password)
        if (uuid.isBlank() || uuid.any { it <= ' ' || it >= '\u007f' || it == '$' })
            throw YktException("安全键盘标识异常，请稍后重试")
        val dictionary = mutableMapOf<Char, Char>()
        layouts.forEach { (key, layout) ->
            val encoded = mapping[key] ?: throw YktException("安全键盘映射异常")
            if (encoded.length != layout.length || encoded.any { it >= '\u007f' })
                throw YktException("安全键盘映射异常")
            layout.zip(encoded).forEach { (plain, cipher) -> dictionary[plain] = cipher }
        }
        return password.map { dictionary.getValue(it) }.joinToString("") + "\$1\$" + uuid
    }
}
