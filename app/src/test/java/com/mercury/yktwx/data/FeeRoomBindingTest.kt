package com.mercury.yktwx.data

import org.junit.Assert.*
import org.junit.Test

class FeeRoomBindingTest {
    private val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("room", 2, "房间"))
    private val values = mapOf("campus" to "demo-campus&示例校区", "room" to "demo-room&示例照明")
    private val names = mapOf("campus" to "示例校区", "room" to "示例照明")

    @Test fun roundTripKeepsFullValuesAndLabelsWithoutLoggingPersonalData() {
        val room = FeeRoomBinding.from(levels, values, names)!!
        val parsed = FeeRoomBinding.parse(room.sceneInfo())!!
        assertEquals(values, parsed.selections)
        assertEquals(names, parsed.names)
        assertEquals("示例照明", parsed.dialogLabel)
        assertEquals("示例校区 · 示例照明", parsed.label)
        assertTrue(room.sameTarget(parsed))
        assertFalse(room.toString().contains("示例"))
        assertFalse(room.toString().contains("demo-room"))
    }
    @Test fun conciseDialogLabelDoesNotRepeatBuildingAndFloorAlreadyInMeterName() {
        val room = FeeRoomBinding.parse("campus:demo-campus#\$#示例校区;building:demo-building#\$#示例A号楼;floor:demo-floor#\$#示例楼层;room:demo-room#\$#示例A-房间A照明")!!
        assertEquals("示例A-房间A照明", room.dialogLabel)
        assertTrue(room.label.contains("示例校区"))
        assertEquals("示例A号楼 · 示例楼层 · 房间A照明",
            FeeRoomBinding.parse(room.sceneInfo().replace("示例A-房间A照明", "房间A照明"))!!.dialogLabel)
    }
    @Test fun conciseDialogLabelSupportsFamilyMeterKeysAndRetainsLightingType() {
        val room = FeeRoomBinding.parse("loudong_id:demo-building#\$#示例A栋;louceng_id:demo-floor#\$#示例单元;room_id:demo-room#\$#示例A栋示例单元房间A水表")!!
        assertEquals("示例A栋示例单元房间A水表", room.dialogLabel)
    }
    @Test fun matchingUsesValuesNotDisplayNamesOrMapOrder() {
        val room = FeeRoomBinding.from(levels, values, names)!!
        val renamed = FeeRoomBinding.from(levels.reversed(), values, names + ("room" to "新名称"))!!
        assertTrue(room.sameTarget(renamed))
        assertFalse(room.sameTarget(FeeRoomBinding.from(levels, values + ("room" to "other-room&示例照明"), names)))
    }
    @Test fun legacySeparatorAndMissingLabelAreSupported() {
        val room = FeeRoomBinding.parse("campus:demo-campus&示例校区#示例校区;room:demo-room")!!
        assertEquals("demo-campus&示例校区", room.selections["campus"])
        assertEquals("示例校区", room.names["campus"])
        assertEquals("demo-room", room.names["room"])
    }
    @Test fun rejectsDuplicateKeysIncompleteSelectionsAndDelimiterInjection() {
        listOf("", "room:x;room:y", "room:x;", "room:x:y", "room:x#\$#label#\$#other", "room:x\n", "token$:x").forEach {
            assertNull(it, FeeRoomBinding.parse(it))
        }
        assertNull(FeeRoomBinding.from(levels, values - "room", names))
        assertNull(FeeRoomBinding.from(levels, values, names + ("room" to "x;building:y")))
    }
}
