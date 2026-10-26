package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DormMetersTest {
    private val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("building", 2, "楼栋"), FeeLevel("floor", 3, "楼层"), FeeLevel("room", 4, "房间"))
    private val lighting = FeeOption("unrelated-light-id", "示例A栋-房间A照明")
    private val ac = FeeOption("different-ac-id", "示例A栋-房间A空调")
    private fun binding(option: FeeOption) = FeeRoomBinding.from(levels,
        mapOf("campus" to "campus-a", "building" to "building-a", "floor" to "floor-a", "room" to option.id),
        mapOf("campus" to "示例校区", "building" to "示例A栋", "floor" to "示例楼层", "room" to option.name))!!
    private fun config(room: FeeRoomBinding?) = FeeConfiguration(FeeProject.Zhengdong, "示例项目",
        RechargeConfiguration("410", "", "", 0, listOf(100), 1, 10000, true, false), true, room)
    private inner class FakeFees : FeeService by FeeApi({ error("Unexpected real request") }) {
        var options = listOf(ac, FeeOption("other-room", "示例A栋-房间B照明"), lighting)
        var failure: Exception? = null
        val details = mutableListOf<Map<String, String>>()
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery {
            assertEquals("fake-token", token)
            if (detail) {
                details += selections.toMap()
                if (selections["room"] == ac.id) failure?.let { throw it }
                // Both meters deliberately share the same account; their actual room IDs select the meter.
                return FeeQuery(thirdParty = "{\"account\":\"same-account\",\"room\":\"${selections["room"]}\"}",
                    display = listOf("信息" to if (selections["room"] == ac.id) "当前剩余电量为32.800度" else "当前剩余电量为86.40度"))
            }
            val choices = when (level) {
                0 -> listOf(FeeOption("campus-a", "示例校区"))
                1 -> listOf(FeeOption("building-a", "示例A栋"))
                2 -> listOf(FeeOption("floor-a", "示例楼层"))
                else -> { assertEquals("floor-a", selections["floor"]); options }
            }
            return FeeQuery(levels = levels, options = choices)
        }
    }
    @Test fun discoversTwoRealTargetsFromSingleAirConditioningBindingWithoutWrites() = runBlocking {
        val fees = FakeFees()
        val official = binding(ac)
        val room = DormMeterDiscovery(fees).discover("fake-token", config(official))!!
        assertSame(official, room.binding)
        assertEquals(listOf(DormMeterKind.Lighting, DormMeterKind.AirConditioning), room.meters.map { it.kind })
        assertEquals(listOf("86.4", "32.8"), room.meters.map { it.remainingKwh })
        assertEquals(listOf(lighting.id, ac.id), fees.details.map { it["room"] })
        assertTrue(fees.details.all { it["campus"] == "campus-a" && it["building"] == "building-a" && it["floor"] == "floor-a" })
        assertEquals("示例A栋-房间A", room.label)
    }
    @Test fun duplicateSiblingIsNeverGuessed() = runBlocking {
        val fees = FakeFees().apply { options = options + FeeOption("duplicate-id", lighting.name) }
        val room = DormMeterDiscovery(fees).discover("fake-token", config(binding(ac)))!!
        assertEquals(listOf(ac.id), room.meters.map { it.room.selections["room"] })
        assertNotNull(room.notice)
        assertEquals(1, fees.details.size)
    }
    @Test fun missingSiblingAndUnrecognizedLabelsKeepOnlyTheActualBoundMeter() {
        val missing = DormMeterDiscovery.siblings(ac, listOf(ac), FeeProject.Zhengdong)
        assertEquals(listOf(ac), missing.first)
        assertNotNull(missing.second)
        val unknown = FeeOption("other", "示例房间A")
        assertEquals(listOf(unknown), DormMeterDiscovery.siblings(unknown, listOf(unknown, lighting), FeeProject.Zhengdong).first)
        assertEquals(DormMeterKind.Other, DormMeterDiscovery.kind(unknown.name))
        assertEquals(listOf(ac), DormMeterDiscovery.siblings(ac, listOf(ac, lighting), FeeProject.FamilyWater).first)
    }
    @Test fun oneMeterFailureDoesNotEraseOtherMeterOrInventZero() = runBlocking {
        val fees = FakeFees().apply { failure = NetworkFailure() }
        val room = DormMeterDiscovery(fees).discover("fake-token", config(binding(lighting)))!!
        assertEquals("86.4", room.meters[0].remainingKwh)
        assertNull(room.meters[1].remainingKwh)
        assertNotNull(room.meters[1].error)
    }
    @Test fun noBindingMakesNoQueriesAndStaleTargetCannotSelectNearbyRoom() = runBlocking {
        val fees = FakeFees()
        assertNull(DormMeterDiscovery(fees).discover("fake-token", config(null)))
        fees.options = listOf(lighting)
        try { DormMeterDiscovery(fees).discover("fake-token", config(binding(ac))); fail("Expected stale target failure") }
        catch (_: YktException) { }
        assertTrue(fees.details.isEmpty())
    }
    @Test fun authenticationAndCancellationPropagateToAccountOwner() = runBlocking {
        for (failure in listOf(AuthenticationRequired(), CancellationException("fake cancellation"))) {
            val fees = FakeFees().apply { this.failure = failure }
            try { DormMeterDiscovery(fees).discover("fake-token", config(binding(ac))); fail("Expected propagation") }
            catch (e: Exception) { assertSame(failure, e) }
        }
    }
    @Test fun onlyObservedElectricityUnitsAreNormalized() {
        assertEquals("0", DormMeterDiscovery.remainingKwh("当前剩余电量为0.00度"))
        assertEquals("-1.5", DormMeterDiscovery.remainingKwh("-1.50 度"))
        assertNull(DormMeterDiscovery.remainingKwh("账户余额为86.4元"))
        assertNull(DormMeterDiscovery.remainingKwh("86.4 kWh"))
        assertNull(DormMeterDiscovery.remainingKwh("剩余电量未知"))
    }
}
