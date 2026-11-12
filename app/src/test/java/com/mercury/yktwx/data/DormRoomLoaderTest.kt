package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DormRoomLoaderTest {
    private val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("building", 2, "楼栋"),
        FeeLevel("floor", 3, "楼层"), FeeLevel("room", 4, "房间"))
    private val lighting = FeeOption("fake-light", "示例101照明")
    private val air = FeeOption("fake-air", "示例101空调")
    private fun binding(option: FeeOption = lighting) = FeeRoomBinding.from(levels,
        mapOf("campus" to "fake-campus", "building" to "fake-building", "floor" to "fake-floor", "room" to option.id),
        mapOf("campus" to "示例校区", "building" to "示例楼栋", "floor" to "示例楼层", "room" to option.name))!!
    private fun config(project: FeeProject = FeeProject.Zhengdong, room: FeeRoomBinding? = binding()) =
        FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(100), 1, 10000, true, false), true, room)
    private data class Read(val project: FeeProject, val level: Int, val selections: Map<String, String>, val detail: Boolean)
    private inner class FakeFees : FeeService by feeApiFixture({ error("No live request") }) {
        val reads = mutableListOf<Read>()
        var pickerGate: CompletableDeferred<Unit>? = null
        var shortFailure = false
        var wrongTarget = false
        var duplicate = false
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int,
            selections: Map<String, String>, detail: Boolean): FeeQuery {
            reads += Read(project, level, selections.toMap(), detail)
            if (detail) return FeeQuery(thirdParty = buildJsonObject {
                selections.forEach { (key, value) -> put(key, value) }
                put("areaName", "当前校区"); put("buildingName", "当前楼栋"); put("floorName", "当前楼层")
                put("roomName", if (selections["room"] == air.id) air.name else lighting.name)
                if (wrongTarget) put("room", "different-target")
            }.toString(), display = listOf("剩余电量(度)" to "12.3"))
            if (level == 3) pickerGate?.await()
            if (shortFailure && level == 3 && reads.none { !it.detail && it.level == 0 }) return FeeQuery(tip = "短路径失败")
            val options = when (level) {
                0 -> listOf(FeeOption("fake-campus", "当前校区"))
                1 -> listOf(FeeOption("fake-building", "当前楼栋"))
                2 -> listOf(FeeOption("fake-floor", "当前楼层"))
                else -> if (project in setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)) listOf(lighting)
                    else listOf(lighting, air) + if (duplicate) listOf(FeeOption("fake-duplicate", air.name)) else emptyList()
            }
            return FeeQuery(levels, options)
        }
    }

    @Test fun boundMeterUsesOnlyIECWithoutLoadingAnyPicker() = runBlocking {
        val fees = FakeFees()
        val room = DormRoomLoader(fees).load("fake-token", config(FeeProject.Commercial))!!
        assertEquals(1, fees.reads.size)
        assertTrue(fees.reads.single().detail)
        assertEquals(4, fees.reads.single().level)
        assertEquals(binding().selections, fees.reads.single().selections)
        assertEquals("12.3", room.meters.single().readings.single().value)
        assertEquals("当前楼栋", room.currentBinding.names["building"])
    }

    @Test fun boundMeterIsPublishedBeforeSlowSiblingPickerAndNoUpperLevelsAreRead() = runBlocking {
        val fees = FakeFees().apply { pickerGate = CompletableDeferred() }
        val published = CompletableDeferred<DormRoom>()
        val work = async { DormRoomLoader(fees).load("fake-token", config(), onUpdate = { published.complete(it) }) }
        try {
            val partial = withTimeout(2000) { published.await() }
            assertTrue(work.isActive)
            assertEquals(lighting.id, partial.meters.single().room.selections["room"])
            assertEquals(listOf(DormMeterSlot(DormMeterKind.AirConditioning, FeeProject.Zhengdong)), partial.pendingMeters)
        } finally { fees.pickerGate!!.complete(Unit) }
        val complete = work.await()!!
        assertEquals(listOf(lighting.id, air.id), complete.meters.map { it.room.selections["room"] })
        assertTrue(complete.pendingMeters.isEmpty())
        assertEquals(3, fees.reads.size)
        assertEquals(listOf(3), fees.reads.filterNot { it.detail }.map { it.level })
        assertEquals(binding().selections - "room", fees.reads.single { !it.detail }.selections)
        assertEquals("当前楼栋", complete.meters.last().room.names["building"])
    }

    @Test fun failedShortPickerFallsBackToCompleteTraversalDuringThisRead() = runBlocking {
        val fees = FakeFees().apply { shortFailure = true }
        val updates = mutableListOf<DormRoom>()
        val room = DormRoomLoader(fees).load("fake-token", config(), onUpdate = updates::add)!!
        assertEquals(1, updates.size)
        assertEquals(lighting.id, updates.single().meters.single().room.selections["room"])
        assertEquals(listOf(lighting.id, air.id), room.meters.map { it.room.selections["room"] })
        assertEquals(listOf(3, 0, 1, 2, 3), fees.reads.filterNot { it.detail }.map { it.level })
    }

    @Test fun duplicateSiblingKeepsOnlyTheConfirmedBoundMeter() = runBlocking {
        val fees = FakeFees().apply { duplicate = true }
        val room = DormRoomLoader(fees).load("fake-token", config())!!
        assertEquals(lighting.id, room.meters.single().room.selections["room"])
        assertNotNull(room.notice)
        assertEquals(1, fees.reads.count { it.detail })
    }

    @Test fun aDifferentReturnedTargetNeverPublishesItsReading() = runBlocking {
        val fees = FakeFees().apply { wrongTarget = true }
        val room = DormRoomLoader(fees).load("fake-token", config())!!
        assertNull(room.meters.single().query)
        assertNotNull(room.meters.single().error)
        assertTrue(room.pendingMeters.isEmpty())
        assertEquals(1, fees.reads.count { it.detail })
    }

    @Test fun familyPairingUsesTheConfigurationAlreadyReadInThisRound() = runBlocking {
        val fees = FakeFees()
        val water = config(FeeProject.FamilyWater, null)
        val room = DormRoomLoader(fees).load("fake-token", config(FeeProject.FamilyElectric),
            otherConfiguration = { water })!!
        assertEquals(listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater), room.meters.map { it.project })
        assertEquals(1, fees.reads.count { it.project == FeeProject.FamilyElectric })
        assertEquals(5, fees.reads.count { it.project == FeeProject.FamilyWater })
    }

    @Test fun wenbeiChecksAllFloorsWithAtMostThreeIndependentRequests() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val threeEntered = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val fees = object : FeeService by feeApiFixture({ error("No live request") }) {
            override suspend fun feeQuery(token: String, project: FeeProject, level: Int,
                selections: Map<String, String>, detail: Boolean): FeeQuery {
                if (level == 2) return FeeQuery(levels, (1..6).map { FeeOption("air-floor-" + it, "楼层" + it) })
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                if (entered.incrementAndGet() == 3) threeEntered.complete(Unit)
                try {
                    gate.await()
                    val floor = selections.getValue("floor")
                    assertEquals("air-building", selections["building"])
                    assertFalse(selections.containsKey("room"))
                    return FeeQuery(levels, listOf(FeeOption(floor + "-room",
                        if (floor == "air-floor-5") "77-201空调" else "77-202空调")))
                } finally { active.decrementAndGet() }
            }
        }
        val source = FeeRoomBinding.from(levels, binding().selections,
            binding().names + ("building" to "7号楼照明") + ("room" to "7-201照明"))!!
        val work = async { DormMeterPairing(fees).wenbei("fake-token", FeeProject.Wenbei, levels, source,
            mapOf("building" to listOf(FeeOption("air-building", "7号楼空调")))) }
        try {
            withTimeout(2000) { threeEntered.await() }
            assertEquals(3, active.get())
        } finally { gate.complete(Unit) }
        val target = work.await()!!.second
        assertEquals("air-floor-5", target.selections["floor"])
        assertEquals("air-floor-5-room", target.selections["room"])
        assertEquals(6, entered.get())
        assertEquals(3, maximum.get())
    }

    @Test fun wenbeiUsesExactBuildingOptionsEvenWhenIECNamesOmitTheMeterSuffix() = runBlocking {
        val reads = mutableListOf<Read>()
        val source = FeeRoomBinding.from(levels, binding().selections,
            binding().names + ("building" to "7号楼照明") + ("room" to "7-201照明"))!!
        val fees = object : FeeService by feeApiFixture({ error("No live request") }) {
            override suspend fun feeQuery(token: String, project: FeeProject, level: Int,
                selections: Map<String, String>, detail: Boolean): FeeQuery {
                reads += Read(project, level, selections.toMap(), detail)
                if (detail) return FeeQuery(thirdParty = buildJsonObject {
                selections.forEach { (key, value) -> put(key, value) }
                    put("buildingName", "7号楼")
                    put("roomName", if (selections["building"] == "air-building") "77-201空调" else "7-201照明")
                }.toString(), display = listOf("剩余电量(度)" to "12.3"))
                return when (level) {
                    1 -> FeeQuery(levels, listOf(FeeOption("fake-building", "7号楼照明"), FeeOption("air-building", "7号楼空调")))
                    2 -> {
                        assertEquals("air-building", selections["building"])
                        FeeQuery(levels, listOf(FeeOption("air-floor", "二层")))
                    }
                    3 -> {
                        assertEquals("air-floor", selections["floor"])
                        FeeQuery(levels, listOf(FeeOption("air-room", "77-201空调")))
                    }
                    else -> error("No full traversal should be performed")
                }
            }
        }
        val room = DormRoomLoader(fees).load("fake-token", config(FeeProject.Wenbei, source))!!
        assertEquals(2, room.meters.size)
        assertEquals("air-room", room.meters.last().room.selections["room"])
        assertEquals(listOf(1, 2, 3), reads.filterNot { it.detail }.map { it.level })
        assertEquals(2, reads.count { it.detail })
    }
}
