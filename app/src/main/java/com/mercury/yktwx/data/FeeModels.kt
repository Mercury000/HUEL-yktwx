package com.mercury.yktwx.data

import kotlinx.serialization.json.*

/** Public catalog only. Location IDs and payment methods always come from the server. */
enum class FeeProject(val id: String, val label: String, val network: Boolean = false) {
    Network("412", "校园网费", true), Zhengdong("410", "郑东电费"),
    Wenbei("409", "文北电费"), FamilyElectric("448", "家属区电费"),
    FamilyWater("449", "家属区水费"), Commercial("408", "商业街水电费"),
}
class FeeConfiguration(
    val project: FeeProject, val amountRules: RechargeConfiguration,
    val roomBindingEnabled: Boolean = false, val roomBinding: FeeRoomBinding? = null,
    val invalidRoomBinding: Boolean = false,
) {
    override fun toString() = "FeeConfiguration([redacted])"
}
data class FeeLevel(val key: String, val number: Int, val name: String)
class FeeOption(val id: String, val name: String) { override fun toString() = "FeeOption([redacted])" }
class FeeQuery(
    val levels: List<FeeLevel> = emptyList(), val options: List<FeeOption> = emptyList(),
    val thirdParty: String? = null, val display: List<Pair<String, String>> = emptyList(),
    val tip: String? = null, val canPay: Boolean = true,
) { override fun toString() = "FeeQuery([redacted])" }
class FeeMethod(val id: String, val code: String, val name: String, val chooseAccount: Boolean, val noPassword: Int) {
    val wechat get() = code == "TSM"
    override fun toString() = "FeeMethod([redacted])"
}
class FeeOrder(val pending: PendingRecharge, val methods: List<FeeMethod>) {
    override fun toString() = "FeeOrder([redacted])"
}
class FeeAccount(val type: String, val name: String, val balanceCents: Long) {
    override fun toString() = "FeeAccount([redacted])"
}
class FeeAccountPreparation(val orderId: String, val methodId: String, val accounts: List<FeeAccount>, private val uuid: String, private val digits: String) {
    fun paymentFields(password: String): Map<String, String> {
        if (!password.matches(Regex("[0-9]{6}"))) throw YktException("请输入六位支付密码")
        if (uuid.isBlank() || digits.length != 10 || digits.toSet().size != 10 || digits.any { it !in '0'..'9' }) throw YktException("支付键盘数据异常")
        return mapOf("password" to password.map { digits.indexOf(it).digitToChar() }.joinToString(""), "uuid" to uuid)
    }
    override fun toString() = "FeeAccountPreparation([redacted])"
}
interface FeeService {
    suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration
    suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery
    suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?)
    suspend fun createFee(token: String, username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long): FeeOrder
    suspend fun restoreFeeOrder(token: String, order: PendingRecharge): FeeOrder = throw YktException("请在官网核实原缴费订单")
    suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod): FeeAccountPreparation
    suspend fun payFeeAccount(token: String, order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String)
    suspend fun launchFeeWechat(token: String, order: PendingRecharge, method: FeeMethod): WechatRechargeLaunch
    suspend fun feeResult(token: String, order: PendingRecharge): RechargeResult
}

internal fun JsonObject.jsonTargetEquals(other: JsonObject): Boolean = keys == other.keys && keys.all { key ->
    val left = scalarOrElement(key); val right = other.scalarOrElement(key)
    if (left is JsonObject && right is JsonObject) left.jsonTargetEquals(right)
    else if (left is Number && right is Number) java.math.BigDecimal(left.toString()).compareTo(java.math.BigDecimal(right.toString())) == 0
    else left == right
}

/** Canonical target identity ignores object key order and insignificant decimal zeroes. */
internal fun feeTargetFingerprint(payload: String): String {
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(value.getValue(it)) }
        is JsonArray -> value.toString()
        is JsonPrimitive -> if (!value.isString && value.content.toBigDecimalOrNull() != null)
            value.content.toBigDecimal().stripTrailingZeros().toPlainString() else value.toString()
    }
    val normalized = canonical(Json.parseToJsonElement(payload).jsonObject)
    return java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
