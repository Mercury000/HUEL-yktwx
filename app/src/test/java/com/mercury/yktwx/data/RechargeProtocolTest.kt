package com.mercury.yktwx.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RechargeProtocolTest {
    private fun pending() = PendingRecharge("123456", "fake-account", "401", 1000, 0, "FAKE-ORDER", "81")
    private fun form(order: String = "FAKE-ORDER", host: String = "wx.tenpay.com", extra: String = ""): String {
        val redirect = URLEncoder.encode("https://yktwx2.huel.edu.cn/payment/?name=result&orderid=" + order + "&payid=81", StandardCharsets.UTF_8)
        return "<form method='POST' action='https://" + host + "/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=fake-prepay&amp;ct=1&amp;sign=fake-sign&amp;package=fake&amp;redirect_url=" + redirect + "'><input name='' value='' type='hidden'>" + extra + "</form><script>DO_NOT_EXECUTE()</script>"
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected sanitized error") } catch (e: YktException) { assertFalse(e.message.orEmpty().contains("fake-sign")) }
    }
    @Test fun decimalInputUsesExactCentsWithoutFloatingPoint() {
        assertEquals(1000L, RechargeMoney.cents("10"))
        assertEquals(1L, RechargeMoney.cents("0.01"))
        assertEquals("10.00", RechargeMoney.yuan(1000))
        assertEquals("50", RechargeMoney.label(5000))
        listOf("", "0", "-1", "1e2", "1.001", "NaN", "1.", ".5", " 10").forEach { assertNull(RechargeMoney.cents(it)) }
        assertEquals(1000, RechargeMoney.responseCents(10))
        assertEquals(1001, RechargeMoney.responseCents("10.01"))
        rejected { RechargeMoney.responseCents("10.001") }
    }
    @Test fun amountRulesHonorConfiguredOptionsAndLimits() {
        val fixed = RechargeConfiguration("401", "fake", "统一账户", 0, listOf(1000, 5000), 1000, 5000, false, false)
        assertNull(fixed.amountError(1000))
        assertNotNull(fixed.amountError(2000))
        assertNotNull(fixed.amountError(100))
        assertNotNull(fixed.amountError(6000))
        val ints = RechargeConfiguration("401", "fake", "统一账户", 0, emptyList(), 1, 50000, true, true)
        assertNotNull(ints.amountError(1001))
        assertNull(ints.amountError(1000))
    }
    @Test fun frontendSignatureMatchesOfflineFakeFixture() {
        val p = RechargeProtocol.signed(mapOf("amount" to "10.00", "empty" to "", "zero" to "0"), "20261021070000", "fake-nonce")
        assertEquals("FA3F6B67B782E184E69476952C3147405517C81FE761B48A67BEF0D6F332632E", p["SIGN"])
        assertFalse(p.containsKey("SECRET_KEY"))
        assertEquals("SHA256", p["SIGN_TYPE"])
    }
    @Test fun schoolRedirectOnlyExtractsOrderWithoutLeakingToken() {
        val order = RechargeProtocol.orderFromLocation("https://yktwx2.huel.edu.cn/payment/?orderid=FAKE-ORDER&paytypeid=81&paytype=CAMPUSCARD&chooseAccount=false&token=fake-token")
        assertEquals("FAKE-ORDER", order.orderId)
        assertFalse(order.toString().contains("fake-token"))
        rejected { RechargeProtocol.orderFromLocation("https://evil.invalid/payment/?orderid=FAKE-ORDER&paytype=CAMPUSCARD") }
        rejected { RechargeProtocol.orderFromLocation("http://yktwx2.huel.edu.cn/payment/?orderid=FAKE-ORDER&paytype=CAMPUSCARD") }
        rejected { RechargeProtocol.orderFromLocation("https://yktwx2.huel.edu.cn/payment/?orderid=ONE&orderid=TWO&paytype=CAMPUSCARD") }
    }
    @Test fun paymentFormCanContainScriptsButNeverExecutesThem() {
        val action = RechargeProtocol.paymentFormAction(form(), pending())
        assertEquals("wx.tenpay.com", action.host)
        assertEquals("fake-prepay", RechargeProtocol.query(action)["prepay_id"])
        rejected { RechargeProtocol.paymentFormAction(form(host = "evil.invalid"), pending()) }
        rejected { RechargeProtocol.paymentFormAction(form(order = "OTHER"), pending()) }
        rejected { RechargeProtocol.paymentFormAction(form(extra = "<input name='token' value='fake-token'>"), pending()) }
        rejected { RechargeProtocol.paymentFormAction(form() + form(), pending()) }
    }
    @Test fun onlyMatchingWechatPaymentUriMayLaunch() {
        val uri = "weixin://wap/pay?prepayid%3Dfake-prepay&package=fake-package&noncestr=fake-nonce&sign=fake-sign"
        val launch = RechargeProtocol.wechatUri("<script>var url='" + uri + "';</script>", "fake-prepay")
        assertEquals(uri, launch.uri)
        assertFalse(launch.toString().contains("fake-sign"))
        rejected { RechargeProtocol.wechatUri("weixin://dl/chat?user=fake") }
        rejected { RechargeProtocol.wechatUri(uri, "other-prepay") }
        rejected { RechargeProtocol.wechatUri(uri + "&token=fake-token") }
        rejected { RechargeProtocol.wechatUri(uri + " " + uri.replace("fake-sign", "other-sign")) }
    }
}
