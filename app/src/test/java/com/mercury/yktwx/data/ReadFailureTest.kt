package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReadFailureTest {
    @Test fun classificationUsesTheExceptionTypeInsteadOfItsDisplayMessage() {
        val network = NetworkFailure("服务暂时无法访问")
        val business = YktException("网络不可用套餐，请改选其他套餐")
        assertEquals(ReadFailureKind.Network, network.readFailureKind())
        assertEquals(ReadFailureKind.Other, business.readFailureKind())
        assertTrue(hasNetworkFailure(network.message to network.readFailureKind()))
        assertFalse(hasNetworkFailure(business.message to business.readFailureKind()))
    }

    @Test fun allAuthenticationFailuresHaveTheSameCategory() {
        for (error in listOf(AuthenticationRequired("会话过期"), InvalidCredentials(), CaptchaRequired())) {
            assertTrue(error.isAuthenticationFailure())
            assertEquals(ReadFailureKind.Authentication, error.readFailureKind())
        }
        assertFalse(NetworkFailure().isAuthenticationFailure())
    }

    @Test fun clearedAndBusinessErrorsDoNotRecoverBecauseOfAnOlderNetworkCategory() {
        assertFalse(hasNetworkFailure(null to ReadFailureKind.Network))
        assertFalse(hasNetworkFailure("网络连接失败" to ReadFailureKind.Other))
        assertTrue(hasNetworkFailure("读取失败" to ReadFailureKind.Other, "中断" to ReadFailureKind.Network))
    }

    @Test fun cancellationIsPropagatedInsteadOfBecomingAnError() {
        val cancelled = CancellationException("cancelled")
        try {
            cancelled.readFailureKind()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
