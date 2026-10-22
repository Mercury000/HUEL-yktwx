package com.mercury.yktwx.data

/** Only selected fields from the website; sensitive models never stringify their contents. */
enum class CampusCardAction(val label: String) { ManageState("挂失 / 解挂"), OthersRecharge("代人充值"), Lost("挂失"), Unlost("解挂") }
enum class RecipientQueryType(val key: String, val label: String) { StudentNumber("sno", "学工号"), Identity("id", "证件号") }
class CampusCard(val account: String, val name: String, val sno: String, val lost: Boolean, val frozen: Boolean, val status: Int) {
    val label get() = name.ifBlank { "校园卡" } + " · " + maskedIdentifier(sno.ifBlank { account })
    override fun toString() = "CampusCard([redacted])"
}
class CardServiceConfiguration(val cards: List<CampusCard>, val lostNeedsPassword: Boolean, val unlostNeedsPassword: Boolean)
class RecipientCaptcha(val key: String, val image: ByteArray) {
    override fun toString() = "RecipientCaptcha([redacted])"
}
class RechargeRecipient(val cardAccount: String, val targetId: String, val name: String, val sno: String, val accountName: String, val balanceCents: Long) {
    val label get() = listOf(name, sno.ifBlank { cardAccount }).filter { it.isNotBlank() }.joinToString(" · ")
    override fun toString() = "RechargeRecipient([redacted])"
}
class CardOperationRejected(message: String) : YktException(message)
/** No state-changing request was started; the user can retry after correcting the error. */
internal class CardOperationNotStarted(message: String) : YktException(message)
/** A write may have run or was accepted, but its final state could not be established. */
internal class CardOperationUnconfirmed(message: String) : YktException(message)

class RecipientLookupRejected(val invalidCaptcha: Boolean, message: String) : YktException(message)
interface CardService {
    suspend fun cardConfiguration(token: String): CardServiceConfiguration
    suspend fun changeCardState(token: String, card: CampusCard, action: CampusCardAction, password: String): List<CampusCard>
    suspend fun recipientCaptcha(token: String): RecipientCaptcha
    suspend fun rechargeRecipients(token: String, type: RecipientQueryType, value: String, captcha: RecipientCaptcha, code: String): List<RechargeRecipient>
    suspend fun recipientRechargeConfiguration(token: String, recipient: RechargeRecipient): RechargeConfiguration
}
internal fun maskedIdentifier(value: String) = if (value.length <= 4) "****" else value.take(2) + "****" + value.takeLast(4)

/** Mapping indices are digit values only for the fixed Number keyboard (order=0). */
internal object NumberKeyboardEncoder {
    fun encode(password: String, mapping: String, uuid: String): String {
        if (!password.matches(Regex("[0-9]{6}"))) throw YktException("请输入 6 位查询密码")
        if (mapping.length != 10 || mapping.any { it <= ' ' || it >= '\u007f' || it == '$' } || mapping.toSet().size != 10)
            throw YktException("数字安全键盘映射异常，请重试")
        if (uuid.isBlank() || uuid.any { it <= ' ' || it >= '\u007f' || it == '$' }) throw YktException("数字安全键盘标识异常，请重试")
        return "1\$1\$" + password.map { mapping[it - '0'] }.joinToString("") + "\$1\$" + uuid
    }
}
