package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class FeeRoomViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val fees = FakeFees()
    private val vault = MemoryVault()
    private val repository = AccountRepository(YktApi(), vault, feeService = fees)
    private val model = FeeViewModel(app, repository, 0L)
    private val store = ViewModelStore().apply { put("rooms", model) }

    @After fun cleanup() {
        fees.writeGate.complete(Unit)
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for room state")
    }
    private fun open(project: FeeProject = FeeProject.Wenbei, requestedRoom: FeeRoomBinding? = null) {
        model.open(project, vault.username, false, requestedRoom)
        await { !model.state.value.loading && model.state.value.config != null }
    }
    private fun selectRoom(index: Int = 0) {
        val state = model.state.value
        model.select(state.levels.last(), state.options.getValue(2)[index])
        await { !model.state.value.selecting && model.state.value.query != null }
    }

    @Test fun unboundSelectionOnlyPromptsAfterExplicitBindingRequest() {
        open(); selectRoom()
        assertNull(model.state.value.roomDialog)
        assertEquals(0, fees.writes)
        model.requestRoomAction()
        assertEquals(FeeRoomAction.Save, model.state.value.roomDialog?.action)
        model.dismissRoomDialog()
        assertNull(model.state.value.roomDialog)
        assertTrue(model.state.value.roomActionEnabled)
        selectRoom(1)
        assertNull(model.state.value.roomDialog)
        model.dismiss(); open(); selectRoom()
        assertNull(model.state.value.roomDialog)
        assertEquals(0, fees.writes)
    }
    @Test fun hallTemporaryPaymentStartsFreshAndNeverChangesExistingOfficialBinding() {
        val official = fees.room(0)
        fees.saved = official
        model.open(FeeProject.Wenbei, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        assertEquals(setOf("campus"), model.state.value.hiddenLevels)
        assertFalse(model.state.value.selections.containsKey("room"))
        selectRoom(1)
        assertNull(model.state.value.roomDialog)
        assertSame(official, model.state.value.roomBinding)
        model.createWithMethod(100, "ACCOUNTTSM")
        await { model.state.value.stage == FeeStage.Methods }
        assertEquals(fees.rooms[1].id, org.json.JSONObject(fees.createdTarget!!).getString("room"))
        assertEquals(0, fees.writes)
        assertSame(official, fees.saved)
    }
    @Test fun hallBindingRequiresExplicitRequestAndConfirmationAndCannotBindUnsupportedProject() {
        fees.saved = fees.room(0)
        model.open(FeeProject.Wenbei, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        selectRoom(1)
        assertNull(model.state.value.roomDialog)
        model.requestRoomAction()
        assertTrue(fees.saved!!.sameTarget(model.state.value.roomDialog!!.previous))
        assertEquals(0, fees.writes)
        model.dismissRoomDialog()
        assertEquals(0, fees.writes)
        model.requestRoomAction(); model.confirmRoomAction()
        await { !model.state.value.roomBusy && fees.writes == 1 }
        assertTrue(fees.room(1).sameTarget(fees.saved))
        model.open(FeeProject.Commercial, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        selectRoom(0); model.requestRoomAction()
        assertNull(model.state.value.roomDialog)
        assertEquals(1, fees.writes)
    }
    @Test fun familyTypeChangeKeepsHouseholdAndUsesEachProjectsActualIds() {
        fees.familyOptions = true
        model.open(FeeProject.FamilyElectric, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        model.select(model.state.value.levels.last(), model.state.value.options.getValue(2).first())
        await { model.state.value.query != null }
        val source = model.state.value.currentRoom!!
        assertEquals("family-448-room", source.selections["room"])
        model.selectFamilyProject(FeeProject.FamilyWater)
        await { !model.state.value.loading && model.state.value.query != null }
        val water = model.state.value
        assertEquals(FeeProject.FamilyWater, water.config!!.project)
        assertEquals("family-449-campus", water.selections["campus"])
        assertEquals("family-449-room", water.selections["room"])
        assertEquals(familyHouseholdKey(source), familyHouseholdKey(water.currentRoom!!))
        assertTrue(water.query!!.thirdParty!!.contains("family-449-room"))
        assertNull(water.roomDialog)
        assertEquals(0, fees.writes)
        assertEquals(0, fees.creations)
        model.selectFamilyProject(FeeProject.FamilyElectric)
        await { !model.state.value.loading && model.state.value.query != null }
        assertTrue(source.sameTarget(model.state.value.currentRoom))
    }
    @Test fun missingOrAmbiguousFamilyMeterRequiresReselectionAndCannotSubmit() {
        fees.familyOptions = true
        model.open(FeeProject.FamilyElectric, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        model.select(model.state.value.levels.last(), model.state.value.options.getValue(2).first())
        await { model.state.value.query != null }
        fees.familyWaterMissing = true
        model.selectFamilyProject(FeeProject.FamilyWater)
        await { !model.state.value.loading }
        assertNull(model.state.value.currentRoom)
        assertNull(model.state.value.query)
        assertNotNull(model.state.value.error)
        model.createWithMethod(100, "ACCOUNTTSM")
        assertEquals(0, fees.creations)
        fees.familyWaterMissing = false
        model.open(FeeProject.FamilyElectric, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        model.select(model.state.value.levels.last(), model.state.value.options.getValue(2).first())
        await { model.state.value.query != null }
        fees.familyWaterDuplicate = true
        model.selectFamilyProject(FeeProject.FamilyWater)
        await { !model.state.value.loading }
        assertNull(model.state.value.currentRoom)
        assertNull(model.state.value.query)
        assertNotNull(model.state.value.error)
        model.createWithMethod(100, "ACCOUNTTSM")
        assertEquals(0, fees.creations)
        assertEquals(0, fees.writes)
    }
    @Test fun fixedFamilyCardTargetRejectsTypeChange() {
        fees.familyOptions = true
        model.open(FeeProject.FamilyElectric, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        model.select(model.state.value.levels.last(), model.state.value.options.getValue(2).first())
        await { model.state.value.query != null }
        val target = model.state.value.currentRoom!!
        model.open(FeeProject.FamilyElectric, vault.username, false, requestedRoom = target)
        await { !model.state.value.loading && model.state.value.query != null }
        val before = model.state.value
        model.selectFamilyProject(FeeProject.FamilyWater)
        assertSame(before, model.state.value)
        assertEquals(0, fees.writes)
        assertEquals(0, fees.creations)
    }
    @Test fun unavailablePreferredMethodKeepsTheExistingOrderWithoutLaunchingOrRecreating() {
        model.open(FeeProject.Wenbei, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        selectRoom(1)
        model.createWithMethod(100, "ACCOUNTTSM")
        await { model.state.value.stage == FeeStage.Methods && !model.state.value.loading }
        assertEquals("所选支付方式不可用，请重新选择", model.state.value.error)
        assertEquals(1, fees.creations)
        assertEquals("TSM", model.state.value.order!!.methods.single().code)
        assertEquals(0, fees.writes)
        model.createWithMethod(100, "ACCOUNTTSM")
        assertEquals(1, fees.creations)
    }
    @Test fun mergedFamilyActionsStillCreateSeparateWaterAndElectricOrders() {
        model.open(FeeProject.FamilyElectric, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        selectRoom(0)
        model.createWithMethod(100, "ACCOUNTTSM")
        await { model.state.value.stage == FeeStage.Methods }
        assertEquals(FeeProject.FamilyElectric, fees.createdProject)
        assertEquals(fees.rooms[0].id, org.json.JSONObject(fees.createdTarget!!).getString("room"))

        model.open(FeeProject.FamilyWater, vault.username, false)
        await { !model.state.value.loading && model.state.value.config != null }
        selectRoom(1)
        model.createWithMethod(100, "ACCOUNTTSM")
        await { model.state.value.stage == FeeStage.Methods }
        assertEquals(FeeProject.FamilyWater, fees.createdProject)
        assertEquals(fees.rooms[1].id, org.json.JSONObject(fees.createdTarget!!).getString("room"))
        assertEquals(0, fees.writes)
    }

    @Test fun saveReadsBackAndUnbindKeepsCurrentSelectionAndQueryWithoutPromptingAgain() {
        open(); selectRoom(); model.requestRoomAction()
        val selections = model.state.value.selections
        val query = model.state.value.query
        model.confirmRoomAction()
        await { !model.state.value.roomBusy && fees.writes == 1 }
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertEquals("已保存为我的房间", model.state.value.roomNotice)
        assertEquals(FeeProject.Wenbei, fees.writtenProject)
        model.requestRoomAction(); model.confirmRoomAction()
        await { !model.state.value.roomBusy && fees.writes == 2 }
        assertNull(model.state.value.roomBinding)
        assertEquals(selections, model.state.value.selections)
        assertSame(query, model.state.value.query)
        assertNull(model.state.value.roomDialog)
        assertEquals(FeeRoomAction.Save, model.state.value.roomAction)
        assertEquals("已解绑", model.state.value.roomNotice)
        model.open(FeeProject.Wenbei, vault.username, false, model.state.value.currentRoom)
        await { !model.state.value.loading }
        assertNull(model.state.value.roomDialog)
        selectRoom(1)
        assertNull(model.state.value.roomDialog)
    }
    @Test fun requestedSavedRoomRestoresOptionsAndReselectingNeverPromptsAutomatically() {
        fees.saved = fees.room(0)
        open(requestedRoom = fees.saved)
        assertEquals(fees.saved!!.selections, model.state.value.selections)
        assertNotNull(model.state.value.query)
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertEquals(listOf(0, 1, 2), fees.queryLevels)
        selectRoom(1)
        assertNull(model.state.value.roomDialog)
        assertEquals(FeeRoomAction.Save, model.state.value.roomAction)
        model.requestRoomAction()
        assertTrue(model.state.value.roomDialog!!.previous!!.sameTarget(fees.room(0)))
        assertTrue(model.state.value.roomDialog!!.target.sameTarget(fees.room(1)))
        model.dismissRoomDialog(); selectRoom(0)
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertNull(model.state.value.roomDialog)
    }
    @Test fun dormSiblingOpensExactMeterAndPreservesOfficialBindingWithoutSavePrompt() {
        fees.saved = fees.room(0)
        model.open(FeeProject.Wenbei, vault.username, false, fees.room(1))
        await { !model.state.value.loading && model.state.value.query != null }
        assertTrue(model.state.value.currentRoom!!.sameTarget(fees.room(1)))
        assertTrue(model.state.value.roomBinding!!.sameTarget(fees.room(0)))
        assertNull(model.state.value.roomDialog)
        assertEquals(0, fees.writes)
        model.open(FeeProject.Wenbei, vault.username, false, model.state.value.currentRoom)
        await { !model.state.value.loading }
        assertNull(model.state.value.roomDialog)
        assertEquals(0, fees.writes)
    }
    @Test fun dormAmountCreatesOrderForExactSiblingWithoutWritingRoomOrPayingAutomatically() {
        fees.saved = fees.room(0)
        model.open(FeeProject.Wenbei, vault.username, false, fees.room(1))
        await { !model.state.value.loading && model.state.value.query != null }
        assertEquals(0, fees.creations)
        model.createWithMethod(100, "ACCOUNTTSM")
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Methods }
        assertEquals(1, fees.creations)
        val target = org.json.JSONObject(fees.createdTarget!!)
        assertEquals(fees.room(1).selections.getValue("room"), target.getString("room"))
        assertTrue(model.state.value.roomBinding!!.sameTarget(fees.room(0)))
        assertEquals(0, fees.writes)
        assertEquals(listOf("demo-method"), model.state.value.order!!.methods.map { it.id })
        model.cancelPayment()
        assertTrue(model.state.value.currentRoom!!.sameTarget(fees.room(1)))
        assertNull(model.state.value.order)
        assertEquals(1, fees.creations)
    }
    @Test fun unavailableDormMeterDoesNotCreateAnOrder() {
        fees.canPay = false
        model.open(FeeProject.Wenbei, vault.username, false, fees.room(0))
        await { !model.state.value.loading && model.state.value.query != null }
        model.createWithMethod(100, "ACCOUNTTSM")
        assertEquals(0, fees.creations)
        assertEquals(FeeStage.Query, model.state.value.stage)
    }
    @Test fun matchingSavedTargetCanBeUnboundEvenIfBalanceQueryFails() {
        fees.saved = fees.room(0); fees.detailFails = true
        open(requestedRoom = fees.saved)
        assertNull(model.state.value.query)
        assertNotNull(model.state.value.error)
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertTrue(model.state.value.roomActionEnabled)
    }
    @Test fun staleSavedOptionFallsBackToSelectionAndDoesNotPromptOrOverwrite() {
        fees.saved = FeeRoomBinding.parse("campus:demo-campus#\$#示例校区;room:missing-room#\$#已删除房间")
        open(requestedRoom = fees.saved)
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.query)
        assertNotNull(model.state.value.roomBinding)
        selectRoom()
        assertNull(model.state.value.roomDialog)
        assertEquals(FeeRoomAction.Save, model.state.value.roomAction)
        assertEquals(0, fees.writes)
    }
    @Test fun lostWriteResponseIsReconciledWithoutReplayingWrite() {
        fees.writeFailure = NetworkFailure()
        open(); selectRoom(); model.requestRoomAction(); model.confirmRoomAction()
        await { !model.state.value.roomBusy }
        assertEquals(1, fees.writes)
        assertEquals(FeeRoomAction.Unbind, model.state.value.roomAction)
        assertEquals("已保存为我的房间", model.state.value.roomNotice)
        assertNull(model.state.value.error)
    }
    @Test fun unknownReadBackDisablesWritesAndNeverClaimsSuccess() {
        open(); selectRoom(); model.requestRoomAction(); fees.readBackFails = true
        model.confirmRoomAction()
        await { !model.state.value.roomBusy }
        assertFalse(model.state.value.roomBindingKnown)
        assertFalse(model.state.value.roomActionEnabled)
        assertNull(model.state.value.roomNotice)
        model.requestRoomAction(); model.confirmRoomAction()
        assertEquals(1, fees.writes)
    }
    @Test fun failedWriteKeepsPreviousBindingAndAllowsManualRetryOnly() {
        fees.saved = fees.room(0)
        open(); selectRoom(1)
        fees.applyWrite = false; fees.writeFailure = NetworkFailure()
        model.requestRoomAction(); model.confirmRoomAction()
        await { !model.state.value.roomBusy }
        assertTrue(model.state.value.roomBinding!!.sameTarget(fees.room(0)))
        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.roomActionEnabled)
        assertEquals(1, fees.writes)
        assertNull(model.state.value.roomDialog)
    }
    @Test fun duplicateClicksAndLateWriteCannotMutateDismissedPage() {
        open(); selectRoom(); model.requestRoomAction(); fees.writeGate = CompletableDeferred()
        model.confirmRoomAction()
        await { fees.writes == 1 }
        model.confirmRoomAction(); model.requestRoomAction(); selectIgnored()
        assertEquals(1, fees.writes)
        model.dismiss(); fees.writeGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.config)
        assertNull(model.state.value.roomNotice)
    }
    private fun selectIgnored() {
        val state = model.state.value
        model.select(state.levels.last(), fees.rooms[1])
        assertEquals(fees.rooms[0].id, model.state.value.selections["room"])
    }
    @Test fun unsupportedProjectNeverOffersSave() {
        open(FeeProject.Commercial); selectRoom()
        assertFalse(model.state.value.config!!.roomBindingEnabled)
        assertFalse(model.state.value.roomActionEnabled)
        assertNull(model.state.value.roomDialog)
        model.requestRoomAction()
        assertNull(model.state.value.roomDialog)
    }
    @Test fun wrongAccountCannotWriteRoom() {
        open(); selectRoom(); model.requestRoomAction(); vault.username = "switched-user"
        model.confirmRoomAction()
        await { !model.state.value.roomBusy }
        assertEquals(0, fees.writes)
        assertFalse(model.state.value.roomBindingKnown)
    }

    private class MemoryVault : Vault {
        var username = "room-test-user"
        override fun credentials() = Credentials(username, "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }
    private class FakeFees : FeeService {
        val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("room", 2, "房间"))
        val rooms = listOf(FeeOption("demo-light&示例照明", "示例照明"), FeeOption("demo-air&示例空调", "示例空调"))
        val queryLevels = mutableListOf<Int>()
        var saved: FeeRoomBinding? = null
        var writes = 0
        var writtenProject: FeeProject? = null
        var detailFails = false
        var canPay = true
        var familyOptions = false
        var familyWaterMissing = false
        var familyWaterDuplicate = false
        var creations = 0
        var createdProject: FeeProject? = null
        var createdTarget: String? = null
        var readBackFails = false
        var applyWrite = true
        var writeFailure: Exception? = null
        var writeGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        fun room(index: Int) = FeeRoomBinding.from(levels,
            mapOf("campus" to "demo-campus", "room" to rooms[index].id),
            mapOf("campus" to "示例校区", "room" to rooms[index].name))!!
        override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
            if (readBackFails) throw NetworkFailure()
            return FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(100), 1, 10000, true, false),
                project != FeeProject.Commercial, saved)
        }
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery {
            queryLevels += level
            if (detail && detailFails) throw NetworkFailure()
            if (familyOptions && project in setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)) {
                val suffix = if (project == FeeProject.FamilyWater) "水表" else "电表"
                val options = if (level == 0) listOf(FeeOption("family-${project.id}-campus", "示例家属区")) else
                    if (project == FeeProject.FamilyWater && familyWaterMissing) emptyList() else
                        listOf(FeeOption("family-${project.id}-room", "示例A栋-房间A$suffix")) +
                            if (project == FeeProject.FamilyWater && familyWaterDuplicate)
                                listOf(FeeOption("family-449-duplicate", "示例A栋-房间A水表")) else emptyList()
                return if (detail) FeeQuery(thirdParty = org.json.JSONObject(selections).toString()) else FeeQuery(levels = levels, options = options)
            }
            return if (detail) FeeQuery(thirdParty = org.json.JSONObject(selections).toString(), display = listOf("剩余电量" to "100 度"), canPay = canPay)
                else FeeQuery(levels = levels, options = if (level == 0) listOf(FeeOption("demo-campus", "示例校区")) else rooms)
        }
        override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) {
            writes++; writtenProject = project
            writeGate.await()
            if (applyWrite) saved = room
            writeFailure?.let { throw it }
        }
        override suspend fun createFee(token: String, username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long): FeeOrder {
            creations++; createdProject = config.project; createdTarget = query.thirdParty
            return FeeOrder(PendingRecharge(username, query.thirdParty!!, config.project.id, amountCents, "DEMO-ORDER"),
                listOf(FeeMethod("demo-method", "TSM", "微信", false, 0)))
        }
        override suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod): FeeAccountPreparation = error("No payment")
        override suspend fun payFeeAccount(token: String, order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String) = error("No payment")
        override suspend fun launchFeeWechat(token: String, order: PendingRecharge, method: FeeMethod): WechatRechargeLaunch = error("No payment")
        override suspend fun feeResult(token: String, order: PendingRecharge): RechargeResult = error("No payment")
    }
}
