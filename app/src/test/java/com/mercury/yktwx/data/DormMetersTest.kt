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
    private fun config(room: FeeRoomBinding?) = FeeConfiguration(FeeProject.Zhengdong, RechargeConfiguration("410", "", listOf(100), 1, 10000, true, false), true, room)
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
        assertEquals(listOf("86.40", "32.800"), room.meters.map { it.readings.firstOrNull()?.value })
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
        assertEquals("86.40", room.meters[0].readings.firstOrNull()?.value)
        assertNull(room.meters[1].readings.firstOrNull()?.value)
        assertNotNull(room.meters[1].error)
        assertEquals(ReadFailureKind.Network, room.meters[1].errorKind)
        assertNull(room.meters[0].errorKind)
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
            catch (e: Exception) {
                assertEquals(failure.javaClass, e.javaClass)
                assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it === failure })
            }
        }
    }
    @Test fun onlyObservedElectricityUnitsAreNormalizedAndDecimalPlacesArePreserved() {
        assertEquals("0.00", DormMeterDiscovery.remainingKwh("当前剩余电量为0.00度"))
        assertEquals("-1.50", DormMeterDiscovery.remainingKwh("-1.50 度"))
        assertEquals("100", DormMeterDiscovery.remainingKwh("100度"))
        assertEquals("32.800", DormMeterDiscovery.remainingKwh(" 当前剩余电量为： 32.800 度。 "))
        assertNull(DormMeterDiscovery.remainingKwh("账户余额为86.4元"))
        assertNull(DormMeterDiscovery.remainingKwh("86.4 kWh"))
        assertNull(DormMeterDiscovery.remainingKwh("剩余电量未知"))
    }

    @Test fun wenbeiPairsBuildingBranchesUsingReturnedFloorAndRoomIds() = runBlocking {
        val details = mutableListOf<Map<String, String>>()
        val selected = FeeRoomBinding.from(levels,
            mapOf("campus" to "wb", "building" to "light-building", "floor" to "light-floor-2", "room" to "light-room-201"),
            mapOf("campus" to "文北", "building" to "5号楼照明", "floor" to "二层", "room" to "5-201照明"))!!
        var duplicateFloor = false
        var extraFloor = false
        var repeatedRoom = false
        var roomOnOtherFloor = false
        var otherFloorUnavailable = false
        val service = object : FeeService by FeeApi({ error("Unexpected real request") }) {
            override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery {
                assertEquals(FeeProject.Wenbei, project)
                if (detail) {
                    details += selections.toMap()
                    return FeeQuery(thirdParty = "{}", display = listOf("信息" to "当前剩余电量为12.3度"))
                }
                val air = selections["building"] == "air-building"
                val choices = when (level) {
                    0 -> listOf(FeeOption("wb", "文北"))
                    1 -> listOf(FeeOption("light-building", "5号楼照明"), FeeOption("air-building", "5号楼空调"))
                    2 -> if (air) listOf(FeeOption("air-floor-35", "2层")) +
                        (if (duplicateFloor) listOf(FeeOption("ambiguous-floor", "2层")) else emptyList()) +
                        (if (extraFloor) listOf(FeeOption("other-floor", "三层")) else emptyList())
                        else listOf(FeeOption("light-floor-2", "二层"))
                    else -> if (air) {
                        val other = selections["floor"] == "other-floor"
                        assertTrue(selections["floor"] in listOf("air-floor-35", "other-floor", "ambiguous-floor"))
                        assertFalse(selections.containsKey("room"))
                        if (other && otherFloorUnavailable) return FeeQuery(tip = "模拟查询失败")
                        val matching = if (other) repeatedRoom || roomOnOtherFloor else !roomOnOtherFloor
                        (if (matching) listOf(FeeOption(if (other) "other-air-room" else "air-room-777", "55-201空调")) else emptyList()) +
                            listOf(FeeOption("wrong-room", "55-202空调"))
                    } else listOf(FeeOption("light-room-201", "5-201照明"))
                }
                return FeeQuery(levels, choices)
            }
        }
        val cfg = FeeConfiguration(FeeProject.Wenbei, config(selected).amountRules, true, selected)
        val room = DormMeterDiscovery(service).discover("fake-token", cfg)!!
        assertEquals(2, room.meters.size)
        assertEquals("air-building", details[1]["building"])
        assertEquals("air-floor-35", details[1]["floor"])
        assertEquals("air-room-777", details[1]["room"])
        assertEquals("55-201空调", room.meters[1].room.dialogLabel)
        assertSame(selected, room.binding)
        extraFloor = true; roomOnOtherFloor = true; details.clear()
        val shifted = DormMeterDiscovery(service).discover("fake-token", cfg)!!
        assertEquals(2, shifted.meters.size)
        assertEquals("other-floor", details[1]["floor"])
        assertEquals("other-air-room", details[1]["room"])
        roomOnOtherFloor = false; otherFloorUnavailable = true; details.clear()
        val incomplete = DormMeterDiscovery(service).discover("fake-token", cfg)!!
        assertEquals(1, incomplete.meters.size)
        assertEquals("light-room-201", details.single()["room"])
        otherFloorUnavailable = false
        roomOnOtherFloor = false; repeatedRoom = true; details.clear()
        val duplicate = DormMeterDiscovery(service).discover("fake-token", cfg)!!
        assertEquals(1, duplicate.meters.size)
        assertEquals("light-room-201", details.single()["room"])
        duplicateFloor = true; details.clear()
        val ambiguous = DormMeterDiscovery(service).discover("fake-token", cfg)!!
        assertEquals(1, ambiguous.meters.size)
        assertEquals("light-room-201", details.single()["room"])
        assertNull(ambiguous.notice)
        duplicateFloor = false; repeatedRoom = false; details.clear()
        val oldNames = FeeRoomBinding.from(levels, selected.selections,
            selected.names + ("building" to "旧楼栋照明") + ("room" to "旧房间照明"))!!
        val renamed = DormMeterDiscovery(service).discover("fake-token", FeeConfiguration(
            FeeProject.Wenbei, cfg.amountRules, true, oldNames))!!
        assertSame(oldNames, renamed.binding)
        assertEquals(2, renamed.meters.size)
        assertEquals("5-201照明", renamed.meters[0].room.names["room"])
        assertEquals("55-201空调", renamed.meters[1].room.names["room"])
    }

    @Test fun familyMergePreservesSeparateProjectsAndPaymentTargets() {
        fun family(project: FeeProject, number: String): DormRoom {
            val suffix = if (project == FeeProject.FamilyWater) "水表" else "电表"
            val target = FeeRoomBinding.from(levels,
                mapOf("campus" to "family", "building" to "b10", "floor" to "unit1", "room" to "${project.id}-$number"),
                mapOf("campus" to "家属区", "building" to "10号楼", "floor" to "1单元", "room" to "10号楼1单元$number$suffix"))!!
            return DormRoom(project, target, listOf(DormMeter(DormMeterKind.Other, target,
                FeeQuery(thirdParty = "{\"project\":\"${project.id}\"}"))), 0)
        }
        val electric = family(FeeProject.FamilyElectric, "001")
        val water = family(FeeProject.FamilyWater, "001")
        val merged = mergeFamilyRooms(listOf(electric, water)).single()
        assertEquals(listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater), merged.meters.map { it.project })
        assertSame(electric.binding, merged.meters[0].room)
        assertSame(water.binding, merged.meters[1].room)
        assertEquals(listOf(electric, water), merged.bindings)
        val differentRoom = family(FeeProject.FamilyWater, "002")
        val mixed = mergeFamilyRooms(listOf(electric, differentRoom))
        assertEquals(2, mixed.size)
        assertEquals(listOf(electric, differentRoom), mixed.flatMap { it.bindings })
        assertEquals(listOf(electric.binding, differentRoom.binding), mixed.flatMap { it.meters }.map { it.room })
        val staleWater = FeeRoomBinding.from(levels, water.binding.selections,
            water.binding.names + ("building" to "历史楼栋") + ("room" to "历史房间水表"))!!
        val renamedWater = DormRoom(FeeProject.FamilyWater, staleWater, water.meters, 0)
        val renamedMerged = mergeFamilyRooms(listOf(electric, renamedWater)).single()
        assertSame(staleWater, renamedMerged.bindings.last().binding)
        assertSame(water.binding, renamedMerged.meters.last().room)
        assertEquals(water.label, renamedWater.label)
        val failedWater = DormRoom(water.project, water.binding, listOf(DormMeter(DormMeterKind.Other,
            water.binding, null, "服务暂时无法访问", errorKind = ReadFailureKind.Network)), 0)
        val failedMerged = mergeFamilyRooms(listOf(electric, failedWater)).single()
        assertEquals(ReadFailureKind.Network, failedMerged.meters.last().errorKind)
    }

    @Test fun oneFamilyBindingDiscoversTheOtherServiceWithoutWritingABinding() = runBlocking {
        fun familyBinding(project: FeeProject, roomId: String, roomName: String) = FeeRoomBinding.from(levels,
            mapOf("campus" to "family", "building" to "10", "floor" to "unit-1", "room" to roomId),
            mapOf("campus" to "河南财经政法大学", "building" to "10号楼", "floor" to "10号楼1单元", "room" to roomName))!!
        val waterBinding = familyBinding(FeeProject.FamilyWater, "water-001", "10号楼1单元001水表")
        val electricBinding = familyBinding(FeeProject.FamilyElectric, "electric-001", "10号楼1单元001电表")
        var boundProject = FeeProject.FamilyWater
        var staleNames = false
        var writes = 0
        val service = object : FeeService by FeeApi({ error("Unexpected real request") }) {
            override suspend fun feeConfiguration(token: String, project: FeeProject) =
                FeeConfiguration(project, config(null).amountRules, true,
                    if (project != boundProject) null else {
                        val actual = if (project == FeeProject.FamilyWater) waterBinding else electricBinding
                        if (staleNames) FeeRoomBinding.from(levels, actual.selections,
                            actual.names + ("building" to "历史楼栋") + ("room" to "历史房间")) else actual
                    })
            override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery {
                val binding = if (project == FeeProject.FamilyWater) waterBinding else electricBinding
                if (detail) return FeeQuery(thirdParty = org.json.JSONObject(binding.selections).toString(),
                    display = listOf(if (project == FeeProject.FamilyWater) "剩余水量(吨)" to "45.86" else "剩余电量(度)" to "1806.75"))
                val options = when (level) {
                    0 -> binding.names["campus"] to binding.selections["campus"]
                    1 -> binding.names["building"] to binding.selections["building"]
                    2 -> binding.names["floor"] to binding.selections["floor"]
                    else -> binding.names["room"] to binding.selections["room"]
                }.let { listOf(FeeOption(it.second!!, it.first!!)) }
                return FeeQuery(levels, options)
            }
            override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) { writes++ }
        }
        for ((project, renamed) in listOf(FeeProject.FamilyWater to false, FeeProject.FamilyElectric to false,
            FeeProject.FamilyWater to true, FeeProject.FamilyElectric to true)) {
            boundProject = project
            staleNames = renamed
            val room = DormMeterDiscovery(service).discover("fake-token", service.feeConfiguration("fake-token", project))!!
            val merged = mergeFamilyRooms(listOf(room)).single()
            assertEquals(listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater), merged.meters.map { it.project })
            assertTrue(electricBinding.sameTarget(merged.meters[0].room))
            assertTrue(waterBinding.sameTarget(merged.meters[1].room))
            assertEquals(listOf(project), merged.bindings.map { it.project })
            assertEquals("度", merged.meters[0].readings.single().unit)
            assertEquals("吨", merged.meters[1].readings.single().unit)
            assertEquals("45.86", merged.meters[1].readings.single().value)
            assertEquals(listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater),
                mergeFamilyRooms(listOf(merged)).single().meters.map { it.project })
        }
        assertEquals(0, writes)
    }

    @Test fun readingsRetainEachReturnedQuantityAndNeverConvertWaterToCurrency() {
        assertEquals(DormMeterReading("剩余水量", "28.60", "吨"), meterReading("剩余水量(吨)", "28.60"))
        assertEquals(DormMeterReading("账户余额", "12.50", "元"), meterReading("账户余额（元）", "12.50"))
        assertEquals(DormMeterReading("剩余电量", "86.40", "度"), meterReading("信息", "当前剩余电量为86.40度"))
        assertEquals(DormMeterReading("说明", "计量器状态未知", ""), meterReading("说明", "计量器状态未知"))
        assertEquals(DormMeterReading("剩余水量(吨)", "未知", ""), meterReading("剩余水量(吨)", "未知"))
        val meter = DormMeter(DormMeterKind.Other, binding(lighting), FeeQuery(display =
            listOf("剩余水量(吨)" to "28.60", "账户余额(元)" to "12.50")), project = FeeProject.FamilyWater)
        assertEquals(listOf("吨", "元"), meter.readings.map { it.unit })
    }
}
