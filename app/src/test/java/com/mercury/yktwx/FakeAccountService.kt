package com.mercury.yktwx

import com.mercury.yktwx.data.*

/** Account switches use the repository's login path without sending any real HTTP requests. */
internal class FakeAccountService : YktService by YktApi({ error("Unexpected real HTTP request") }) {
    override suspend fun paymentCard(token: String, account: String) = CampusCard(account, "校园卡", "fake-user", false, false, 0)
    override suspend fun login(credentials: Credentials) = Session("fake-token", Long.MAX_VALUE)
    override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit) = PaymentBatch(
        listOf("00000000000000000000"), 60,
        PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 0),
    )
}
