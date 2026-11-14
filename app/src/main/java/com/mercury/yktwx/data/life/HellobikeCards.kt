package com.mercury.yktwx.data.life

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

data class HellobikeStudentAudit(val authState: Int?, val userType: Int?) {
    val studentApproved get() = authState == 2 && userType == 1
    val label get() = when (authState) {
        0 -> "未认证"
        1 -> "认证中，请耐心等待"
        2 -> when (userType) { 1 -> "学生认证已通过"; 2 -> "教职工认证已通过"; else -> "已认证" }
        3 -> "认证失败，请重新认证"
        else -> "认证状态暂未确认"
    }
}

data class HellobikeUserCard(val guid: String, val businessType: Int, val name: String,
    val remainDay: Int?, val status: Int?, val expireSecond: Long?, val description: String, val renewable: Boolean = false) {
    val statusLabel get() = when (status) {
        0 -> "生效中"; 10 -> "待启用"; 40 -> "冻结中"; 41 -> "待激活"
        50, 60, 70 -> "已失效"
        else -> status?.let { "状态 $it" } ?: "状态暂未返回"
    }
}
data class HellobikeUserCardPage(val cards: List<HellobikeUserCard>, val hasNext: Boolean)

/** Keep the server's price and promotion objects intact for tw.standard.preOrder. */
data class HellobikeCampusProduct(val key: String, val name: String, val price: String,
    val description: String, val raw: JsonObject, val section: String = "", val campus: Boolean = true) {
    // The campus Q/H/R components call preOrder for every returned package. They do not
    // use button.canClick/appLimit. Ordinary prePay only blocks canBuy > 1.
    val purchasable get() = raw.hbInt("businessType") in 1..2 && (campus || (raw.hbInt("canBuy") ?: 0) <= 1)
    val unavailableReason get() = if (raw.hbInt("businessType") !in 1..2) "商品信息不完整，请刷新" else if (campus) null else when (raw.hbInt("canBuy")) {
        2 -> "该账号已停止购卡"
        3 -> "骑行卡数量已达上限"
        else -> null
    }
    val signingRequired get() = raw.hbInt("paymentLimit") == 1
}
data class HellobikeCampusProducts(val title: String, val products: List<HellobikeCampusProduct>)

@Serializable
data class HellobikeCardPurchase(val name: String, val orders: List<HellobikePayOrder>, val amount: String,
    val businessType: String, val scene: String, val region: HellobikeRegion?,
    val attempt: HellobikePaymentAttempt? = null, val paid: Boolean = false,
    val paymentCancelled: Boolean = false, val signingFields: JsonObject = JsonObject(emptyMap())) {
    override fun toString() = "HellobikeCardPurchase([redacted])"
}

interface HellobikeCardService {
    suspend fun studentAudit(account: HellobikeAccount): HellobikeStudentAudit = throw HellobikeException("学生认证查询尚未接入")
    suspend fun userCards(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?,
        businessType: Int, page: Int): HellobikeUserCardPage = throw HellobikeException("我的卡查询尚未接入")
    suspend fun campusProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): HellobikeCampusProducts =
        throw HellobikeException("校园卡商品查询尚未接入")
    suspend fun ordinaryProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): List<HellobikeCampusProduct> =
        throw HellobikeException("骑行卡商品查询尚未接入")
    suspend fun preOrderCard(account: HellobikeAccount, product: HellobikeCampusProduct, region: HellobikeRegion?,
        position: HellobikePosition?): HellobikeCardPurchase = throw HellobikeException("校园卡购买尚未接入")
    suspend fun cardPayChannels(account: HellobikeAccount, purchase: HellobikeCardPurchase): List<HellobikePayOption> =
        throw HellobikeException("校园卡收银台尚未接入")
    suspend fun payCard(account: HellobikeAccount, purchase: HellobikeCardPurchase, orders: List<HellobikePayOrder>,
        option: HellobikePayOption): HellobikePayComponent = throw HellobikeException("校园卡收银台尚未接入")
}
