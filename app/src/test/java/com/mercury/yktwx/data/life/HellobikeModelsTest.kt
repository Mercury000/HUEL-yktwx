package com.mercury.yktwx.data.life

import org.junit.Assert.*
import org.junit.Test

class HellobikeModelsTest {
    @Test fun domainsRouteThreeProvidersAndDoNotGuessBareBicycleNumbers() {
        assertEquals(DeviceScanRoute(LifeProvider.Hellobike, LifeFeature.Ride, "1234567890"), UnifiedDeviceCode.identify("https://c3x.me/?n=1234567890"))
        assertEquals("REA-bike-reference", HellobikeDeviceCode.parse("http://c3x.me/?u=REA-bike-reference"))
        assertEquals("1234567890", HellobikeDeviceCode.manual("1234567890"))
        assertNull(HellobikeDeviceCode.manual("123456789"))
        assertNull(UnifiedDeviceCode.identify("1234567890"))
        assertEquals(DeviceScanRoute(LifeProvider.Hellobike, LifeFeature.Ride, "1234567890"), UnifiedDeviceCode.identifyManual(" 1234567890 "))
        assertNull(UnifiedDeviceCode.identifyManual("12345678901"))
        assertEquals(LifeProvider.Hui798, UnifiedDeviceCode.identifyManual("https://i.hnkzy.com/q/1/123456789012345")?.provider)
        assertEquals(LifeProvider.Panggui, UnifiedDeviceCode.identifyManual("https://h5.qiekj.com/skip?SN=123456789012345")?.provider)
        assertEquals(LifeProvider.Panggui, UnifiedDeviceCode.identify("https://h5.qiekj.com/skip?SN=123456789012345")?.provider)
        assertEquals(LifeProvider.Hui798, UnifiedDeviceCode.identify("https://i.hnkzy.com/q/1/123456789012345")?.provider)
        assertNull(LifeDeviceCode.parse("12345678-1234-1234-1234-123456789abc", LifeFeature.Ride))
    }
    @Test fun spoofedAndAmbiguousOrOtherVehicleLinksAreRejectedLocally() {
        for (url in listOf("https://c3x.me.example/?n=1234567890", "https://evil.test@c3x.me/?n=1234567890",
            "https://c3x.me:8443/?n=1234567890", "https://c3x.me/?n=1234567890#n=another",
            "https://c3x.me/?n=123&n=123", "https://c3x.me/?n=123&u=456", "https://c3x.me/?N=123",
            "https://c3x.me/?n=123&m=1", "https://c3x.me/?n=123&%65=1", "https://c3x.me/?n=%0A123", "file://c3x.me/?n=123")) {
            assertNull(url, HellobikeDeviceCode.parse(url))
        }
        assertEquals("123", HellobikeDeviceCode.parse("https://c3x.me/?n=123&u=123"))
    }
    @Test fun locationAndProofAgeBoundsRejectStaleFutureAndInaccuratePoints() {
        assertTrue(fixtureBikePosition.fresh(80_000)); assertFalse(fixtureBikePosition.fresh(80_001))
        assertFalse(fixtureBikePosition.fresh(49_999)); assertFalse(fixtureBikePosition.copy(accuracyMetres = 81f).fresh(50_000))
        assertFalse(fixtureBikePosition.copy(lat = Double.NaN).fresh(50_000))
        val check = HellobikePrecheck("bike", "ride", "rule", 50_000, fixtureBikeRegion, fixtureBikePosition)
        assertTrue(check.fresh(110_000)); assertFalse(check.fresh(110_001)); assertFalse(check.fresh(49_999))
        assertEquals(0.0, HellobikeCoordinates.distance(fixtureBikePosition, fixtureBikePosition), .001)
        assertEquals(0.0 to 0.0, HellobikeCoordinates.toGcj02(0.0, 0.0))
    }
    @Test fun endingAndClosingAnOrderCannotBePresentedAsPaymentSuccess() {
        val ended = fixtureBikeSession.copy(phase = HellobikePhase.Ended)
        assertFalse(ended.settled); assertFalse(ended.dismissible)
        val closed = ended.copy(settlement = HellobikeSettlement.Closed)
        assertFalse(closed.settled); assertTrue(closed.dismissible); assertEquals("订单已关闭", closed.title)
        assertTrue(ended.copy(settlement = HellobikeSettlement.Paid).settled)
    }
}
