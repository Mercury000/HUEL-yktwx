package com.mercury.yktwx.data.life

import org.junit.Assert.*
import org.junit.Test

class UnifiedDeviceCodeTest {
    @Test fun manualNumbersReach798WithoutAUrlAndPreserveLeadingZeros() {
        for (number in listOf("00001234", "12345678901", "1".repeat(32))) {
            assertEquals(DeviceScanRoute(LifeProvider.Hui798, LifeFeature.Water, number),
                UnifiedDeviceCode.identifyManual(" $number "))
        }
        val nqt = "12345678-1234-1234-1234-123456789abc"
        assertEquals(DeviceScanRoute(LifeProvider.Panggui, LifeFeature.Laundry, nqt),
            UnifiedDeviceCode.identifyManual(nqt))
    }

    @Test fun overlappingNumbersRequireTheChosenPlatformBeforeRouting() {
        val bike = "1234567890"
        val water = "123456789012345"
        for ((number, otherProvider) in listOf(bike to LifeProvider.Hellobike, water to LifeProvider.Panggui)) {
            assertEquals(setOf(LifeProvider.Hui798, otherProvider),
                UnifiedDeviceCode.manualCandidates(number).map { it.provider }.toSet())
            assertNull(UnifiedDeviceCode.identifyManual(number))
            assertEquals(DeviceScanRoute(LifeProvider.Hui798, LifeFeature.Water, number),
                UnifiedDeviceCode.identifyManual(number, LifeProvider.Hui798))
            assertEquals(otherProvider, UnifiedDeviceCode.identifyManual(number, otherProvider)?.provider)
        }
        assertNull(UnifiedDeviceCode.identifyManual(bike, LifeProvider.Panggui))
        assertNull(UnifiedDeviceCode.identifyManual(water, LifeProvider.Hellobike))
    }

    @Test fun manualEntryRejectsLinksWhileCameraKeepsExplicitQrRouting() {
        for ((url, provider) in listOf(
            "https://i.hnkzy.com/q/1/123456789012345" to LifeProvider.Hui798,
            "https://cloud.hnkzy.com/h5/?atype=1&id=123456789012345#/pages/app/index" to LifeProvider.Hui798,
            "https://h5.qiekj.com/skip?SN=123456789012345" to LifeProvider.Panggui,
            "https://c3x.me/?n=1234567890" to LifeProvider.Hellobike,
        )) {
            assertTrue(UnifiedDeviceCode.manualCandidates(url).isEmpty())
            assertNull(UnifiedDeviceCode.identifyManual(url, provider))
            assertEquals(provider, UnifiedDeviceCode.identify(url)?.provider)
        }
        assertEquals(LifeProvider.Panggui, UnifiedDeviceCode.identify("123456789012345")?.provider)
        assertNull(UnifiedDeviceCode.identify("1234567890"))
    }

    @Test fun invalidNumbersNeverProduceAPlatformCandidate() {
        for (raw in listOf("", "1234567", "1".repeat(33), "1234 5678", "1234567a", "１２３４５６７８")) {
            assertTrue(raw, UnifiedDeviceCode.manualCandidates(raw).isEmpty())
            assertNull(UnifiedDeviceCode.identifyManual(raw, LifeProvider.Hui798))
        }
    }
}
